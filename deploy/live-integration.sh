#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

usage() {
  echo 'Usage: deploy/live-integration.sh {build|up|status|logs|stop} [--namespace migrate-kitchensink-test]'
}

action=${1:-help}
case "$action" in
  build|up|status|logs|stop) shift ;;
  help|-h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

namespace=migrate-kitchensink-test
cluster=${KS_KIND_CLUSTER:-kitchensink}
while (( $# )); do
  case "$1" in
    --namespace) namespace=${2:?Missing namespace}; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ ! "$namespace" =~ ^migrate-kitchensink-[a-z0-9]([-a-z0-9]*[a-z0-9])?$ || ${#namespace} -gt 63 ]]; then
  echo 'Choose a namespace starting with migrate-kitchensink-.' >&2
  exit 2
fi

kube=(kubectl --context "kind-$cluster")
in_ns=("${kube[@]}" --namespace "$namespace")
image_lock=.run/deploy/live-integration-images.tsv
mongo_version=8.0.38

mongo_image_id() {
  local reference=$1 image_environment
  image_environment=$(docker image inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$reference" 2>/dev/null) || return 1
  if ! grep -Fxq "MONGO_VERSION=$mongo_version" <<< "$image_environment"; then
    return 1
  fi
  docker image inspect --format '{{.Id}}' "$reference"
}

build_images() (
  local jar=modules/kitchensink-live-integration/target/kitchensink-live-integration.jar
  if [[ ! -f "$jar" ]]; then
    echo "Missing $jar. Run mvn -P live-integration -pl modules/kitchensink-live-integration -am clean package with JDK 21 first." >&2
    exit 1
  fi

  local context lock_tmp= mongo_id relay_id role image_id image
  if ! mongo_id=$(mongo_image_id "mongo:$mongo_version") && ! mongo_id=$(mongo_image_id mongo:8.0); then
    docker pull "mongo:$mongo_version"
    mongo_id=$(mongo_image_id "mongo:$mongo_version")
  fi

  mkdir -p .run/deploy
  context=$(mktemp -d "${TMPDIR:-/tmp}/migrate-kitchensink-live-integration.XXXXXX")
  trap 'rm -rf "$context"; rm -f "$lock_tmp"' EXIT
  lock_tmp=$(mktemp .run/deploy/.live-integration-images.XXXXXX)
  cp deploy/images/live-integration/Dockerfile "$jar" "$context/"
  docker build --provenance=false --iidfile "$context/image-id" "$context"
  relay_id=$(cat "$context/image-id")
  for role in mongodb live-integration; do
    case "$role" in
      mongodb)
        image_id=$mongo_id
        # The kubelet verifies previously pulled images against their repository name.
        image="mongo:$mongo_version"
        ;;
      live-integration)
        image_id=$relay_id
        image="migrate-kitchensink-$role:content-${image_id#sha256:}"
        ;;
    esac
    [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]
    docker tag "$image_id" "$image"
    printf '%s\t%s\t%s\n' "$role" "$image" "$image_id" >> "$lock_tmp"
  done
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

load_mongo_image() (
  local image=$1 platform archive_dir
  platform=$("${kube[@]}" get nodes -o 'jsonpath={.items[0].status.nodeInfo.operatingSystem}/{.items[0].status.nodeInfo.architecture}')
  if [[ ! "$platform" =~ ^[a-z0-9]+/[a-z0-9]+$ ]]; then
    echo 'Unable to determine the kind node platform.' >&2
    exit 1
  fi
  # The Docker image store may have only one platform cached for a multi-platform tag.
  archive_dir=$(mktemp -d "${TMPDIR:-/tmp}/migrate-kitchensink-mongodb-image.XXXXXX")
  trap 'rm -rf "$archive_dir"' EXIT
  docker image save --platform "$platform" --output "$archive_dir/image.tar" "$image"
  kind load image-archive "$archive_dir/image.tar" --name "$cluster"
)

load_images() {
  local role image image_id actual_id expected_image
  if [[ ! -s "$image_lock" ]]; then
    echo 'No live integration image lock found. Run ./deploy/live-integration.sh build first.' >&2
    exit 1
  fi

  mongo_image= relay_image=
  while IFS=$'\t' read -r role image image_id; do
    case "$role" in
      mongodb) expected_image="mongo:$mongo_version" ;;
      live-integration) expected_image="migrate-kitchensink-$role:content-${image_id#sha256:}" ;;
      *) echo "Unknown image role: $role" >&2; exit 1 ;;
    esac
    if [[ ! "$image_id" =~ ^sha256:[0-9a-f]{64}$ || "$image" != "$expected_image" ]]; then
      echo "Invalid image lock: $image_lock" >&2
      exit 1
    fi
    case "$role" in
      mongodb) mongo_image=$image; actual_id=$(mongo_image_id "$image") ;;
      live-integration) relay_image=$image; actual_id=$(docker image inspect --format '{{.Id}}' "$image") ;;
    esac
    if [[ "$actual_id" != "$image_id" ]]; then
      echo "Local image differs from the build lock: $image" >&2
      exit 1
    fi
  done < "$image_lock"
  : "${mongo_image:?MongoDB image missing from build lock}" "${relay_image:?Relay image missing from build lock}"
  load_mongo_image "$mongo_image"
  kind load docker-image "$relay_image" --name "$cluster"
}

read_password() {
  local secret=$1 key=$2 password
  password=$("${in_ns[@]}" get secret "$secret" -o "jsonpath={.data.$key}" | openssl base64 -d -A)
  if [[ ! "$password" =~ ^[a-f0-9]{48}$ ]]; then
    echo "Secret $secret must contain a generated $key." >&2
    exit 1
  fi
  printf '%s\n' "$password"
}

ensure_mongo_credentials() {
  local secret=mongodb-credentials root_password relay_password
  if [[ -z "$("${in_ns[@]}" get secret "$secret" --ignore-not-found -o name)" ]]; then
    if [[ -n "$("${in_ns[@]}" get pvc mongodb-data --ignore-not-found -o name)" ]]; then
      echo 'Restore secret mongodb-credentials before starting with the existing mongodb-data volume.' >&2
      exit 1
    fi
    root_password=$(openssl rand -hex 24)
    relay_password=$(openssl rand -hex 24)
    if ! printf 'MONGO_ROOT_PASSWORD=%s\nMONGO_RELAY_PASSWORD=%s\n' "$root_password" "$relay_password" |
      "${in_ns[@]}" create secret generic "$secret" --from-env-file=/dev/stdin >/dev/null 2>&1; then
      "${in_ns[@]}" get secret "$secret" -o name >/dev/null
    fi
  fi
  read_password "$secret" MONGO_ROOT_PASSWORD >/dev/null
  read_password "$secret" MONGO_RELAY_PASSWORD >/dev/null
}

provision_mongo_relay() {
  if ! "${in_ns[@]}" exec statefulset/mongodb --container mongodb -- sh -ec '
    exec mongosh --host 127.0.0.1 --quiet --norc \
      --username "$MONGO_INITDB_ROOT_USERNAME" --password "$MONGO_INITDB_ROOT_PASSWORD" \
      --authenticationDatabase admin --file /opt/kitchensink-mongo/provision-members-relay.js
  ' >/dev/null 2>&1; then
    echo 'Unable to provision the MongoDB relay account and Liquibase permissions.' >&2
    exit 1
  fi
}

provision_h2_relay() {
  local secret=h2-relay-credentials generated_password relay_password setup
  if [[ -z "$("${in_ns[@]}" get secret "$secret" --ignore-not-found -o name)" ]]; then
    generated_password=$(openssl rand -hex 24)
    if ! printf 'H2_RELAY_PASSWORD=%s\n' "$generated_password" |
      "${in_ns[@]}" create secret generic "$secret" --from-env-file=/dev/stdin >/dev/null 2>&1; then
      "${in_ns[@]}" get secret "$secret" -o name >/dev/null
    fi
  fi
  relay_password=$(read_password "$secret" H2_RELAY_PASSWORD)

  IFS= read -r -d '' setup <<'H2_SETUP' || true
IFS= read -r relay_password
case "$relay_password" in ""|*[!a-f0-9]*) exit 2 ;; esac

# Feed credentials to H2 Shell through stdin and keep SQL errors out of logs.
run_h2_shell() {
  {
    printf '%s\n' 'jdbc:h2:tcp://127.0.0.1:9092/kitchensink' 'org.h2.Driver' maintenance "$H2_MAINT_PASSWORD"
    cat
    printf '%s\n' exit
  } | java -cp /opt/h2.jar org.h2.tools.Shell -properties null
}

if ! result=$(run_h2_shell 2>&1 <<'SQL'
SELECT 'KITCHENSINK_CAPTURE_READY' AS STATUS FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = 'PUBLIC' AND TRIGGER_NAME = 'MEMBER_INSERT_CAPTURE' AND EVENT_MANIPULATION = 'INSERT' AND EVENT_OBJECT_TABLE = 'MEMBER' AND EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'MEMBER_INSERT_OUTBOX');
SQL
) || ! printf '%s\n' "$result" | grep -qx KITCHENSINK_CAPTURE_READY; then
  echo 'H2 insert capture is unavailable. Deploy the updated H2 image before starting the relay.' >&2
  exit 1
fi

if ! result=$(run_h2_shell 2>&1 <<SQL
CREATE USER IF NOT EXISTS members_relay PASSWORD '$relay_password';
ALTER USER members_relay SET PASSWORD '$relay_password';
GRANT SELECT ON Member TO members_relay;
GRANT SELECT, UPDATE, DELETE ON MEMBER_INSERT_OUTBOX TO members_relay;
SELECT 'KITCHENSINK_RELAY_READY' AS STATUS;
SQL
) || ! printf '%s\n' "$result" | grep -qx KITCHENSINK_RELAY_READY || printf '%s\n' "$result" | grep -q 'Error:'; then
  echo 'Unable to provision the H2 relay account.' >&2
  exit 1
fi
H2_SETUP
  printf '%s\n' "$relay_password" | "${in_ns[@]}" exec -i statefulset/h2 --container h2 -- sh -eu -c "$setup"
}

wait_for_relay() {
  local attempt logs
  for (( attempt=0; attempt<45; attempt++ )); do
    if logs=$("${in_ns[@]}" logs deployment/live-integration --tail=100 2>/dev/null) &&
      [[ "$logs" == *'Started LiveIntegrationApplication'* ]]; then
      return
    fi
    sleep 2
  done
  echo 'The relay did not finish startup. Inspect it with ./deploy/live-integration.sh logs.' >&2
  exit 1
}

case "$action" in
  build)
    build_images
    ;;
  up)
    require_owner
    "${in_ns[@]}" rollout status statefulset/h2 --timeout=180s
    load_images
    provision_h2_relay
    ensure_mongo_credentials
    sed "s|IMAGE_MONGODB|$mongo_image|g" deploy/k8s/mongodb.yaml | "${in_ns[@]}" apply -f -
    "${in_ns[@]}" rollout status statefulset/mongodb --timeout=240s
    provision_mongo_relay
    sed "s|IMAGE_LIVE_INTEGRATION|$relay_image|g" deploy/k8s/live-integration.yaml | "${in_ns[@]}" apply -f -
    "${in_ns[@]}" rollout status deployment/live-integration --timeout=240s
    wait_for_relay
    printf 'Live integration ready in %s.\n' "$namespace"
    ;;
  status)
    require_owner
    "${in_ns[@]}" get deployment,statefulset,service,pvc,pod -l 'app in (mongodb,live-integration)' -o wide
    ;;
  logs)
    require_owner
    exec "${in_ns[@]}" logs --follow deployment/live-integration
    ;;
  stop)
    require_owner
    "${in_ns[@]}" scale deployment/live-integration --replicas=0
    if [[ -n "$("${in_ns[@]}" get pods -l app=live-integration -o name)" ]]; then
      "${in_ns[@]}" wait --for=delete pod -l app=live-integration --timeout=180s
    fi
    "${in_ns[@]}" scale statefulset/mongodb --replicas=0
    ;;
esac
