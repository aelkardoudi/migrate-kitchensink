#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

usage() {
  echo 'Usage: deploy/demo.sh {build|up|status|forward|stop} [--namespace migrate-kitchensink-final] [--port 18201]'
}

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

action=${1:-help}
case "$action" in
  build|up|status|forward|stop) shift ;;
  help|-h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

namespace=migrate-kitchensink-final
port=18201
cluster=${KS_KIND_CLUSTER:-kitchensink}
while (( $# )); do
  case "$1" in
    --namespace) namespace=${2:?Missing namespace}; shift 2 ;;
    --port) port=${2:?Missing port}; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

[[ "$namespace" =~ ^migrate-kitchensink-[a-z0-9]([-a-z0-9]*[a-z0-9])?$ && ${#namespace} -le 63 ]] ||
  fail 'Choose a namespace starting with migrate-kitchensink-.'
[[ "$port" =~ ^[1-9][0-9]{0,4}$ && "$port" -le 65535 ]] || fail 'Choose a port from 1 to 65535.'

kube=(kubectl --context "kind-$cluster")
in_ns=("${kube[@]}" --namespace "$namespace")
image_lock=.run/deploy/images.tsv
mongo_ref=mongo:8.0.38

build_images() (
  local war=modules/kitchensink/target/kitchensink.war
  local jar=modules/new-kitchensink-boot/target/new-kitchensink-boot.jar
  [[ -f "$war" && -f "$jar" ]] || fail 'Build the WAR and Spring Boot JAR first with mvn clean package.'

  local ctx archive config role repository image_id ref
  ctx=$(mktemp -d "${TMPDIR:-/tmp}/migrate-kitchensink-images.XXXXXX")
  trap 'rm -rf "$ctx"' EXIT
  archive=${KS_WILDFLY_ZIP:-$ctx/wildfly-28.0.1.Final.zip}
  if [[ -z "${KS_WILDFLY_ZIP:-}" ]]; then
    curl -fL --retry 2 -o "$archive" https://github.com/wildfly/wildfly/releases/download/28.0.1.Final/wildfly-28.0.1.Final.zip
  fi
  printf '%s  %s\n' 8000605d800fcb003bac939ee25971b051775df6eb766d8e8de2c0e52dc071fe "$archive" | shasum -a 256 -c -

  mkdir -p "$ctx/legacy/app" "$ctx/modern"
  unzip -q "$archive" -d "$ctx/legacy"
  mv "$ctx/legacy/wildfly-28.0.1.Final" "$ctx/legacy/wildfly"
  unzip -q "$war" -d "$ctx/legacy/app"
  [[ ! -f "$ctx/legacy/app/WEB-INF/kitchensink-quickstart-ds.xml" &&
     ! -f "$ctx/legacy/app/WEB-INF/classes/META-INF/persistence.xml" ]] ||
    fail 'The WAR contains old H2 configuration. Rebuild it from the current checkout.'
  config=$ctx/legacy/wildfly/standalone/configuration/standalone.xml
  sed -e '/<datasource .*pool-name="ExampleDS"/,/<\/datasource>/d' \
      -e '/<drivers>/,/<\/drivers>/d' \
      -e 's/ datasource="java:jboss\/datasources\/ExampleDS"//' "$config" > "$config.tmp"
  if grep -Eq 'ExampleDS|jdbc:h2|<driver name="h2"' "$config.tmp"; then
    fail 'WildFly still has a configured H2 datasource.'
  fi
  mv "$config.tmp" "$config"

  cp deploy/images/legacy/{Dockerfile,start.sh} "$ctx/legacy/"
  cp deploy/images/modern/Dockerfile "$jar" "$ctx/modern/"
  for role in legacy modern; do
    repository="migrate-kitchensink-$role"
    docker build --provenance=false --build-arg SOURCE_DATE_EPOCH=1704067200 \
      --iidfile "$ctx/$role.id" "$ctx/$role"
    image_id=$(cat "$ctx/$role.id")
    [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Invalid image ID for $role."
    ref="$repository:content-${image_id#sha256:}"
    docker tag "$image_id" "$ref"
    printf '%s\t%s\t%s\n' "$role" "$ref" "$image_id" >> "$ctx/images.tsv"
  done

  docker image inspect "$mongo_ref" >/dev/null 2>&1 || docker pull "$mongo_ref"
  image_id=$(docker image inspect --format '{{.Id}}' "$mongo_ref")
  [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || fail 'Invalid MongoDB image ID.'
  printf '%s\t%s\t%s\n' mongodb "$mongo_ref" "$image_id" >> "$ctx/images.tsv"

  mkdir -p .run/deploy
  local final_lock
  final_lock=$(mktemp .run/deploy/.images.XXXXXX)
  cp "$ctx/images.tsv" "$final_lock"
  mv "$final_lock" "$image_lock"
  cat "$image_lock"
)

require_owner() {
  local owner
  owner=$("${kube[@]}" get namespace "$namespace" -o 'jsonpath={.metadata.labels.app\.kubernetes\.io/part-of}')
  [[ "$owner" == migrate-kitchensink ]] || fail "Namespace $namespace belongs to another deployment."
}

require_mongo_target() {
  local modern storage h2 lock service gate
  lock=$("${in_ns[@]}" get configmap mongo-cutover-lock --ignore-not-found -o name)
  [[ -z "$lock" ]] || fail "Finish the active cutover in $namespace before using this deployment."
  for service in modern public-web; do
    gate=$("${in_ns[@]}" get service "$service" --ignore-not-found -o 'jsonpath={.spec.selector.kitchensink-cutover}')
    [[ -z "$gate" ]] || fail "Service $service is still fenced in $namespace."
  done
  modern=$("${in_ns[@]}" get deployment modern --ignore-not-found -o name)
  storage=$("${in_ns[@]}" get deployment modern --ignore-not-found \
    -o 'jsonpath={.metadata.annotations.kitchensink\.io/member-store}')
  h2=$("${in_ns[@]}" get statefulset/h2 persistentvolumeclaim/h2-data --ignore-not-found -o name)
  if [[ "$storage" != mongodb && ( -n "$modern" || -n "$h2" ) ]]; then
    fail "Namespace $namespace has not cut over to MongoDB. Complete the cutover from migration/07-mongodb-cutover before upgrading."
  fi
}

load_mongo_image() (
  local platform archive_dir
  platform=$("${kube[@]}" get nodes \
    -o 'jsonpath={.items[0].status.nodeInfo.operatingSystem}/{.items[0].status.nodeInfo.architecture}')
  [[ "$platform" =~ ^[a-z0-9]+/[a-z0-9]+$ ]] || fail 'Unable to determine the kind node platform.'
  archive_dir=$(mktemp -d "${TMPDIR:-/tmp}/migrate-kitchensink-mongodb.XXXXXX")
  trap 'rm -rf "$archive_dir"' EXIT
  docker image save --platform "$platform" --output "$archive_dir/image.tar" "$mongo_ref"
  kind load image-archive "$archive_dir/image.tar" --name "$cluster"
)

load_images() {
  [[ -s "$image_lock" ]] || fail 'No image lock found. Run ./deploy/demo.sh build first.'
  local role ref image_id actual mongo_loaded=
  legacy_ref= modern_ref=
  while IFS=$'\t' read -r role ref image_id; do
    [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Invalid image lock $image_lock."
    case "$role" in
      legacy|modern) [[ "$ref" == "migrate-kitchensink-$role:content-${image_id#sha256:}" ]] ||
          fail "Invalid $role image in $image_lock." ;;
      mongodb) [[ "$ref" == "$mongo_ref" ]] || fail "Invalid MongoDB image in $image_lock." ;;
      *) fail "Unknown image role $role. Run ./deploy/demo.sh build again." ;;
    esac
    actual=$(docker image inspect --format '{{.Id}}' "$ref")
    [[ "$actual" == "$image_id" ]] || fail "Local image differs from the build lock for $ref."
    case "$role" in
      legacy) legacy_ref=$ref ;;
      modern) modern_ref=$ref ;;
      mongodb) mongo_loaded=1 ;;
    esac
  done < "$image_lock"
  [[ -n "$legacy_ref" && -n "$modern_ref" && -n "$mongo_loaded" ]] || fail "Incomplete image lock $image_lock."
  kind load docker-image "$legacy_ref" "$modern_ref" --name "$cluster"
  load_mongo_image
}

ensure_password() {
  local secret=$1 key=$2 password
  if [[ -z "$("${in_ns[@]}" get secret "$secret" --ignore-not-found -o name)" ]]; then
    password=$(openssl rand -hex 24)
    if ! printf '%s=%s\n' "$key" "$password" |
      "${in_ns[@]}" create secret generic "$secret" --from-env-file=/dev/stdin >/dev/null 2>&1; then
      "${in_ns[@]}" get secret "$secret" -o name >/dev/null
    fi
  fi
  password=$("${in_ns[@]}" get secret "$secret" -o "jsonpath={.data.$key}" | openssl base64 -d -A)
  [[ "$password" =~ ^[a-f0-9]{48}$ ]] || fail "Secret $secret must contain the generated $key."
  printf '%s\n' "$password"
}

provision_mongo_accounts() {
  if ! printf '%s\n' "$1" "$2" |
    "${in_ns[@]}" exec -i statefulset/mongodb --container mongodb -- sh -eu -c '
      IFS= read -r MONGO_MEMBER_API_PASSWORD
      IFS= read -r MONGO_MIGRATION_PASSWORD
      export MONGO_MEMBER_API_PASSWORD MONGO_MIGRATION_PASSWORD
      exec mongosh kitchensink --host 127.0.0.1 --quiet --norc \
        --username "$MONGO_INITDB_ROOT_USERNAME" --password "$MONGO_INITDB_ROOT_PASSWORD" \
        --authenticationDatabase admin --eval "$1"
    ' sh '
      function ensureRole(role, privileges) {
        if (db.getRole(role)) db.updateRole(role, {privileges, roles: []});
        else db.createRole({role, privileges, roles: []});
      }
      function ensureUser(user, pwd, roles) {
        if (db.getUser(user)) db.updateUser(user, {pwd, roles});
        else db.createUser({user, pwd, roles});
      }
      ensureRole("memberApiWriter", [
        {resource: {db: "kitchensink", collection: "members"}, actions: ["find", "insert"]},
        {resource: {db: "kitchensink", collection: "counters"}, actions: ["find", "update"]},
      ]);
      ensureUser("members_api", process.env.MONGO_MEMBER_API_PASSWORD, [{role: "memberApiWriter", db: "kitchensink"}]);
      ensureRole("liquibaseMetadata", ["DATABASECHANGELOG", "DATABASECHANGELOGLOCK"].map(collection => ({
        resource: {db: "kitchensink", collection}, actions: ["collMod"],
      })));
      ensureUser("members_migrator", process.env.MONGO_MIGRATION_PASSWORD,
        [{role: "readWrite", db: "kitchensink"}, {role: "liquibaseMetadata", db: "kitchensink"}]);
    ' >/dev/null 2>&1; then
    fail 'Unable to provision the MongoDB API and Liquibase accounts.'
  fi
}

up() {
  local existing_namespace api_password migration_password current_store
  existing_namespace=$("${kube[@]}" get namespace "$namespace" --ignore-not-found -o name)
  if [[ -n "$existing_namespace" ]]; then
    require_owner
    require_mongo_target
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

  if [[ -z "$("${in_ns[@]}" get secret mongodb-credentials --ignore-not-found -o name)" &&
        -n "$("${in_ns[@]}" get pvc mongodb-data --ignore-not-found -o name)" ]]; then
    fail 'Restore secret mongodb-credentials for the existing MongoDB volume before starting.'
  fi
  current_store=$("${in_ns[@]}" get deployment modern --ignore-not-found \
    -o 'jsonpath={.metadata.annotations.kitchensink\.io/member-store}')
  if [[ "$current_store" == mongodb &&
        -z "$("${in_ns[@]}" get secret mongodb-member-api-credentials --ignore-not-found -o name)" ]]; then
    fail 'Restore secret mongodb-member-api-credentials before upgrading the MongoDB application.'
  fi

  ensure_password mongodb-credentials MONGO_ROOT_PASSWORD >/dev/null
  api_password=$(ensure_password mongodb-member-api-credentials MONGO_MEMBER_API_PASSWORD)
  migration_password=$(ensure_password mongodb-migration-credentials MONGO_MIGRATION_PASSWORD)
  sed "s|IMAGE_MONGODB|$mongo_ref|g" deploy/k8s/mongodb.yaml | "${in_ns[@]}" apply -f -
  "${in_ns[@]}" rollout status statefulset/mongodb --timeout=240s
  provision_mongo_accounts "$api_password" "$migration_password"
  sed "s|IMAGE_MODERN|$modern_ref|g" deploy/k8s/modern.yaml | "${in_ns[@]}" apply -f -
  "${in_ns[@]}" rollout status deployment/modern --timeout=240s
  sed "s|IMAGE_LEGACY|$legacy_ref|g" deploy/k8s/legacy.yaml | "${in_ns[@]}" apply -f -
  "${in_ns[@]}" rollout status deployment/legacy --timeout=240s
  printf 'Local access: ./deploy/demo.sh forward --namespace %s --port %s\n' "$namespace" "$port"
}

wait_for_pods_to_stop() {
  local selector=$1 pods
  pods=$("${in_ns[@]}" get pod -l "$selector" -o name)
  if [[ -n "$pods" ]]; then
    "${in_ns[@]}" wait --for=delete pod -l "$selector" --timeout=180s
  fi
}

case "$action" in
  build) build_images ;;
  up) up ;;
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
    require_mongo_target
    "${in_ns[@]}" scale deployment/legacy deployment/modern --replicas=0
    wait_for_pods_to_stop 'app in (legacy,modern)'
    "${in_ns[@]}" scale statefulset/mongodb --replicas=0
    wait_for_pods_to_stop app=mongodb
    ;;
esac
