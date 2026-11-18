#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

usage() {
  echo 'Usage: deploy/demo.sh {up|status|forward|stop} [--namespace migrate-kitchensink-dev] [--port 18201]'
}

action=${1:-help}
case "$action" in
  up|status|forward|stop) shift ;;
  help|-h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

namespace=migrate-kitchensink-dev
port=18201
cluster=${KS_KIND_CLUSTER:-kitchensink}
while (( $# )); do
  case "$1" in
    --namespace) namespace=${2:?Missing namespace}; shift 2 ;;
    --port) port=${2:?Missing port}; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ ! "$namespace" =~ ^migrate-kitchensink-[a-z0-9]([-a-z0-9]*[a-z0-9])?$ || ${#namespace} -gt 63 ]]; then
  echo 'Choose a namespace starting with migrate-kitchensink-.' >&2
  exit 2
fi
if [[ ! "$port" =~ ^[1-9][0-9]{0,4}$ || "$port" -gt 65535 ]]; then
  echo 'Choose a port from 1 to 65535.' >&2
  exit 2
fi

kube=(kubectl --context "kind-$cluster")
in_ns=("${kube[@]}" --namespace "$namespace")

require_owner() {
  local owner
  owner=$("${kube[@]}" get namespace "$namespace" -o 'jsonpath={.metadata.labels.app\.kubernetes\.io/part-of}')
  if [[ "$owner" != migrate-kitchensink ]]; then
    echo "Namespace $namespace belongs to another deployment." >&2
    exit 1
  fi
}

load_images() {
  local lock=.run/deploy/images.tsv role ref image_id actual_id
  if [[ ! -s "$lock" ]]; then
    echo 'No image lock found. Run ./deploy/build-images.sh first.' >&2
    exit 1
  fi
  h2_ref= legacy_ref=
  while IFS=$'\t' read -r role ref image_id; do
    if [[ ! "$image_id" =~ ^sha256:[0-9a-f]{64}$ || "$ref" != "migrate-kitchensink-$role:content-${image_id#sha256:}" ]]; then
      echo "Invalid image lock: $lock" >&2
      exit 1
    fi
    case "$role" in
      h2) h2_ref=$ref ;;
      legacy) legacy_ref=$ref ;;
      *) echo "Unknown image role: $role" >&2; exit 1 ;;
    esac
    actual_id=$(docker image inspect --format '{{.Id}}' "$ref")
    if [[ "$actual_id" != "$image_id" ]]; then
      echo "Local image differs from the build lock: $ref" >&2
      exit 1
    fi
  done < "$lock"
  : "${h2_ref:?H2 image missing from build lock}" "${legacy_ref:?WildFly image missing from build lock}"
  kind load docker-image "$h2_ref" "$legacy_ref" --name "$cluster"
}

case "$action" in
  up)
    existing_namespace=$("${kube[@]}" get namespace "$namespace" --ignore-not-found -o name)
    if [[ -n "$existing_namespace" ]]; then
      require_owner
    fi
    load_images
    if [[ -z "$existing_namespace" ]]; then
      "${kube[@]}" create -f - <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: $namespace
  labels:
    app.kubernetes.io/part-of: migrate-kitchensink
EOF
    fi
    if [[ -z "$("${in_ns[@]}" get secret h2-credentials --ignore-not-found -o name)" ]]; then
      if [[ -n "$("${in_ns[@]}" get pvc h2-data --ignore-not-found -o name)" ]]; then
        echo "Restore secret h2-credentials before starting with the existing h2-data volume." >&2
        exit 1
      fi
      maintenance_password=$(openssl rand -hex 24)
      application_password=$(openssl rand -hex 24)
      printf 'H2_MAINT_PASSWORD=%s\nH2_APP_PASSWORD=%s\n' "$maintenance_password" "$application_password" |
        "${in_ns[@]}" create secret generic h2-credentials --from-env-file=/dev/stdin
    fi
    sed -e "s|IMAGE_H2|$h2_ref|g" -e "s|IMAGE_LEGACY|$legacy_ref|g" deploy/k8s/baseline.yaml |
      "${in_ns[@]}" apply -f -
    "${in_ns[@]}" rollout status statefulset/h2 --timeout=180s
    "${in_ns[@]}" rollout status deployment/legacy --timeout=240s
    printf 'Local access: ./deploy/demo.sh forward --namespace %s --port %s\n' "$namespace" "$port"
    ;;
  status)
    require_owner
    "${in_ns[@]}" get deployment,statefulset,service,pvc,pod -o wide
    ;;
  forward)
    require_owner
    exec "${in_ns[@]}" port-forward --address 127.0.0.1 svc/public-web "$port:8080"
    ;;
  stop)
    require_owner
    "${in_ns[@]}" scale deployment/legacy --replicas=0
    if [[ -n "$("${in_ns[@]}" get pods -l app=legacy -o name)" ]]; then
      "${in_ns[@]}" wait --for=delete pod -l app=legacy --timeout=180s
    fi
    "${in_ns[@]}" scale statefulset/h2 --replicas=0
    if [[ -n "$("${in_ns[@]}" get pods -l app=h2 -o name)" ]]; then
      "${in_ns[@]}" wait --for=delete pod -l app=h2 --timeout=180s
    fi
    ;;
esac
