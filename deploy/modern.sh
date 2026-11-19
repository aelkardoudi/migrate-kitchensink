#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

usage() {
  echo 'Usage: deploy/modern.sh {build|up|status|forward|stop} [--namespace migrate-kitchensink-dev] [--port 18203]'
}

action=${1:-help}
case "$action" in
  build|up|status|forward|stop) shift ;;
  help|-h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

namespace=migrate-kitchensink-dev
port=18203
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
image_repository=migrate-kitchensink-modern
image_lock=.run/deploy/modern-image.tsv

build_image() (
  local jar=modules/new-kitchensink-boot/target/new-kitchensink-boot.jar
  if [[ ! -f "$jar" ]]; then
    echo "Missing $jar. Run mvn -Pmodern -pl .,modules/new-kitchensink-boot -am clean package with JDK 21 first." >&2
    exit 1
  fi

  mkdir -p .run/deploy
  local context lock_tmp= image_id image
  context=$(mktemp -d "${TMPDIR:-/tmp}/migrate-kitchensink-modern.XXXXXX")
  trap 'rm -rf "$context"; rm -f "$lock_tmp"' EXIT
  lock_tmp=$(mktemp .run/deploy/.modern-image.XXXXXX)
  cp deploy/images/modern/Dockerfile "$jar" "$context/"
  docker build --provenance=false --iidfile "$context/image-id" "$context"
  image_id=$(cat "$context/image-id")
  [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]
  image="$image_repository:content-${image_id#sha256:}"
  docker tag "$image_id" "$image"
  printf '%s\t%s\n' "$image" "$image_id" > "$lock_tmp"
  mv "$lock_tmp" "$image_lock"
  cat "$image_lock"
)

require_owner() {
  local owner
  owner=$("${kube[@]}" get namespace "$namespace" --ignore-not-found -o 'jsonpath={.metadata.labels.app\.kubernetes\.io/part-of}')
  if [[ "$owner" != migrate-kitchensink ]]; then
    echo 'Select a migrate-kitchensink namespace containing the H2 StatefulSet and Service.' >&2
    exit 1
  fi
}

load_image() {
  local image_id actual_id
  if [[ ! -s "$image_lock" ]]; then
    echo 'No modern image lock found. Run ./deploy/modern.sh build first.' >&2
    exit 1
  fi
  IFS=$'\t' read -r image image_id < "$image_lock"
  if [[ ! "$image_id" =~ ^sha256:[0-9a-f]{64}$ || "$image" != "$image_repository:content-${image_id#sha256:}" ]]; then
    echo "Invalid image lock: $image_lock" >&2
    exit 1
  fi
  actual_id=$(docker image inspect --format '{{.Id}}' "$image")
  if [[ "$actual_id" != "$image_id" ]]; then
    echo "Local image differs from the build lock: $image" >&2
    exit 1
  fi
  kind load docker-image "$image" --name "$cluster"
}

provision_h2_reader() {
  local secret=h2-read-credentials generated_password reader_password
  if [[ -z "$("${in_ns[@]}" get secret "$secret" --ignore-not-found -o name)" ]]; then
    generated_password=$(openssl rand -hex 24)
    if ! printf 'H2_READ_PASSWORD=%s\n' "$generated_password" |
      "${in_ns[@]}" create secret generic "$secret" --from-env-file=/dev/stdin >/dev/null 2>&1; then
      # Reuse the winning Secret when concurrent deployments both tried to create it.
      "${in_ns[@]}" get secret "$secret" -o name >/dev/null
    fi
  fi

  reader_password=$("${in_ns[@]}" get secret "$secret" -o 'jsonpath={.data.H2_READ_PASSWORD}' | openssl base64 -d -A)
  if [[ ! "$reader_password" =~ ^[a-f0-9]{48}$ ]]; then
    echo "Secret $secret must contain a generated H2_READ_PASSWORD." >&2
    exit 1
  fi

  printf '%s\n' "$reader_password" |
    "${in_ns[@]}" exec -i statefulset/h2 --container h2 -- sh -eu -c '
      IFS= read -r reader_password
      case "$reader_password" in ""|*[!a-f0-9]*) exit 2 ;; esac
      umask 077
      grant_sql=$(mktemp)
      trap "rm -f \"\$grant_sql\"" EXIT
      printf "CREATE USER IF NOT EXISTS members_readonly PASSWORD '\''%s'\'';\nALTER USER members_readonly SET PASSWORD '\''%s'\'';\nGRANT SELECT ON Member TO members_readonly;\n" \
        "$reader_password" "$reader_password" > "$grant_sql"
      if ! java -cp /opt/h2.jar org.h2.tools.RunScript \
        -url jdbc:h2:tcp://127.0.0.1:9092/kitchensink -user maintenance \
        -password "$H2_MAINT_PASSWORD" -script "$grant_sql" >/dev/null 2>&1; then
        echo "Unable to provision the H2 read-only member account." >&2
        exit 1
      fi
    '
}

case "$action" in
  build)
    build_image
    ;;
  up)
    require_owner
    "${in_ns[@]}" rollout status statefulset/h2 --timeout=180s
    load_image
    provision_h2_reader
    sed "s|IMAGE_MODERN|$image|g" deploy/k8s/modern.yaml | "${in_ns[@]}" apply -f -
    "${in_ns[@]}" rollout status deployment/modern --timeout=240s
    printf 'Local access: ./deploy/modern.sh forward --namespace %s --port %s\n' "$namespace" "$port"
    ;;
  status)
    require_owner
    "${in_ns[@]}" get deployment,service,pod -l app=modern -o wide
    ;;
  forward)
    require_owner
    exec "${in_ns[@]}" port-forward --address 127.0.0.1 svc/modern "$port:8080"
    ;;
  stop)
    require_owner
    "${in_ns[@]}" scale deployment/modern --replicas=0
    ;;
esac
