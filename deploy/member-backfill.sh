#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

usage() {
  cat <<'HELP'
Usage: deploy/member-backfill.sh build
       deploy/member-backfill.sh run --run-id history-01 [--max-member-id 500] [--namespace migrate-kitchensink-test]
       deploy/member-backfill.sh verify [--namespace migrate-kitchensink-test]
       deploy/member-backfill.sh status [--namespace migrate-kitchensink-test]
       deploy/member-backfill.sh logs --job JOB_NAME [--namespace migrate-kitchensink-test]
HELP
}

action=${1:-help}
case "$action" in
  build|run|verify|status|logs) shift ;;
  help|-h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

namespace=migrate-kitchensink-test
run_id=
max_member_id=
job=
cluster=${KS_KIND_CLUSTER:-kitchensink}
while (( $# )); do
  case "$1" in
    --namespace) namespace=${2:?Missing namespace}; shift 2 ;;
    --run-id) run_id=${2:?Missing run ID}; shift 2 ;;
    --max-member-id)
      if [[ "$action" != run ]]; then
        echo 'Use --max-member-id with run.' >&2
        exit 2
      fi
      max_member_id=${2:?Missing max member ID}
      shift 2
      ;;
    --job) job=${2:?Missing Job name}; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ ! "$namespace" =~ ^migrate-kitchensink-[a-z0-9]([-a-z0-9]*[a-z0-9])?$ || ${#namespace} -gt 63 ]]; then
  echo 'Choose a namespace starting with migrate-kitchensink-.' >&2
  exit 2
fi
if [[ "$action" == run && ( ! "$run_id" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ || ${#run_id} -gt 63 ) ]]; then
  echo 'Pass --run-id followed by a lowercase ID of up to 63 characters.' >&2
  exit 2
fi
if [[ "$action" == logs && ! "$job" =~ ^member-backfill-(verify-)?[a-z0-9]+$ ]]; then
  echo 'Pass the Kubernetes Job name with --job.' >&2
  exit 2
fi

validate_max_member_id() {
  local LC_ALL=C
  [[ "$max_member_id" =~ ^[0-9]+$ ]] || return 1
  max_member_id=${max_member_id#"${max_member_id%%[!0]*}"}
  max_member_id=${max_member_id:-0}
  [[ ${#max_member_id} -lt 19 || ( ${#max_member_id} -eq 19 && ! "$max_member_id" > 9223372036854775807 ) ]]
}

max_member_id_arg='# BACKFILL_MAX_MEMBER_ID'
if [[ -n "$max_member_id" ]]; then
  if ! validate_max_member_id; then
    echo 'Pass --max-member-id an integer from 0 to 9223372036854775807.' >&2
    exit 2
  fi
  max_member_id_arg="- \"maxMemberId=$max_member_id\""
fi

kube=(kubectl --context "kind-$cluster")
in_ns=("${kube[@]}" --namespace "$namespace")
image_repository=migrate-kitchensink-member-backfill
image_lock=.run/deploy/member-backfill-image.tsv

build_image() (
  local jar=modules/kitchensink-member-backfill/target/kitchensink-member-backfill.jar
  if [[ ! -f "$jar" ]]; then
    echo "Missing $jar. Run mvn -Pbatch -pl modules/kitchensink-member-backfill -am clean package with JDK 21 first." >&2
    exit 1
  fi

  mkdir -p .run/deploy
  local context lock_tmp= image_id image
  context=$(mktemp -d "${TMPDIR:-/tmp}/migrate-kitchensink-member-backfill.XXXXXX")
  trap 'rm -rf "$context"; rm -f "$lock_tmp"' EXIT
  lock_tmp=$(mktemp .run/deploy/.member-backfill-image.XXXXXX)
  cp deploy/images/member-backfill/Dockerfile "$jar" "$context/"
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
    echo 'Select a migrate-kitchensink namespace with H2 and live integration running.' >&2
    exit 1
  fi
}

wait_for_services() {
  local app
  for app in "$@"; do
    if ! "${in_ns[@]}" wait --for=condition=Ready pod -l "app=$app" --timeout=90s; then
      echo "Start $app in $namespace before launching the Job." >&2
      exit 1
    fi
  done
}

require_no_active_job() {
  local known active
  known=$("${in_ns[@]}" get jobs -l app=member-backfill -o 'jsonpath={range .items[*]}{.metadata.name}{"\t"}{.status.conditions[?(@.status=="True")].type}{"\n"}{end}')
  active=$(awk -F '\t' '$1 && $2 !~ /(^| )(Complete|Failed)( |$)/ {print $1}' <<< "$known")
  if [[ -n "$active" ]]; then
    printf 'A member backfill Job is still active:\n%s\n' "$active" >&2
    exit 1
  fi
}

load_image() {
  local image_id actual_id
  if [[ ! -s "$image_lock" ]]; then
    echo 'No member backfill image lock found. Run ./deploy/member-backfill.sh build first.' >&2
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
  local secret=h2-backfill-credentials generated_password reader_password
  if [[ -z "$("${in_ns[@]}" get secret "$secret" --ignore-not-found -o name)" ]]; then
    generated_password=$(openssl rand -hex 24)
    if ! printf 'H2_BACKFILL_PASSWORD=%s\n' "$generated_password" |
      "${in_ns[@]}" create secret generic "$secret" --from-env-file=/dev/stdin >/dev/null 2>&1; then
      "${in_ns[@]}" get secret "$secret" -o name >/dev/null
    fi
  fi
  reader_password=$("${in_ns[@]}" get secret "$secret" -o 'jsonpath={.data.H2_BACKFILL_PASSWORD}' | openssl base64 -d -A)
  if [[ ! "$reader_password" =~ ^[a-f0-9]{48}$ ]]; then
    echo "Secret $secret must contain a generated H2_BACKFILL_PASSWORD." >&2
    exit 1
  fi

  printf '%s\n' "$reader_password" |
    "${in_ns[@]}" exec -i statefulset/h2 --container h2 -- sh -eu -c '
      IFS= read -r reader_password
      case "$reader_password" in ""|*[!a-f0-9]*) exit 2 ;; esac
      umask 077
      grant_sql=$(mktemp)
      trap "rm -f \"\$grant_sql\"" EXIT
      printf "%s\n" \
        "CREATE USER IF NOT EXISTS members_backfill PASSWORD '\''$reader_password'\'';" \
        "ALTER USER members_backfill SET PASSWORD '\''$reader_password'\'';" \
        "GRANT SELECT ON PUBLIC.MEMBER TO members_backfill;" \
        "GRANT SELECT ON PUBLIC.MEMBER_INSERT_OUTBOX TO members_backfill;" > "$grant_sql"
      if ! java -cp /opt/h2.jar org.h2.tools.RunScript \
        -url jdbc:h2:tcp://127.0.0.1:9092/kitchensink -user maintenance \
        -password "$H2_MAINT_PASSWORD" -script "$grant_sql" >/dev/null 2>&1; then
        echo "Unable to provision the H2 member backfill reader." >&2
        exit 1
      fi
    '
}

case "$action" in
  build)
    build_image
    ;;
  run|verify)
    require_owner
    if [[ "$action" == run ]]; then
      wait_for_services h2 mongodb live-integration
      require_no_active_job
    else
      wait_for_services h2 mongodb
    fi
    "${in_ns[@]}" get secret mongodb-credentials -o name >/dev/null
    load_image
    provision_h2_reader

    manifest=deploy/k8s/member-backfill-verify.yaml
    replacements=(-e "s|IMAGE_MEMBER_BACKFILL|$image|g")
    if [[ "$action" == run ]]; then
      "${in_ns[@]}" apply -f deploy/k8s/member-backfill-data.yaml
      manifest=deploy/k8s/member-backfill.yaml
      replacements+=(-e "s|BACKFILL_RUN_ID|$run_id|g" -e "s|# BACKFILL_MAX_MEMBER_ID|$max_member_id_arg|")
    fi
    job=$(sed "${replacements[@]}" "$manifest" |
      "${in_ns[@]}" create -f - -o 'jsonpath={.metadata.name}')
    printf 'Follow Job %s: ./deploy/member-backfill.sh logs --job %s --namespace %s\n' "$job" "$job" "$namespace"
    ;;
  status)
    require_owner
    "${in_ns[@]}" get jobs,pods,pvc -l 'app in (member-backfill,member-backfill-verify)' -o wide
    ;;
  logs)
    require_owner
    exec "${in_ns[@]}" logs --follow "job/$job"
    ;;
esac
