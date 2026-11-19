#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

usage() {
  echo 'Usage: deploy/mongo-cutover.sh {prepare|cutover} [--namespace migrate-kitchensink-test] [--drain-timeout 60]'
}

action=${1:-help}
case "$action" in
  prepare|cutover) shift ;;
  help|-h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

namespace=migrate-kitchensink-test
cluster=${KS_KIND_CLUSTER:-kitchensink}
drain_timeout=60
while (( $# )); do
  case "$1" in
    --namespace) namespace=${2:?Missing namespace}; shift 2 ;;
    --drain-timeout) drain_timeout=${2:?Missing drain timeout}; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

if [[ ! "$namespace" =~ ^migrate-kitchensink-[a-z0-9]([-a-z0-9]*[a-z0-9])?$ || ${#namespace} -gt 63 ]]; then
  fail 'Choose a namespace starting with migrate-kitchensink-.'
fi
if [[ ! "$cluster" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]]; then
  fail 'KS_KIND_CLUSTER must be a kind cluster name.'
fi
if [[ ! "$drain_timeout" =~ ^[1-9][0-9]{0,2}$ ]] || (( drain_timeout > 600 )); then
  fail 'Choose a drain timeout from 1 to 600 seconds.'
fi

kube=(kubectl --context "kind-$cluster")
in_ns=("${kube[@]}" --namespace "$namespace")
image_lock=.run/deploy/modern-image.tsv
state_dir=".run/deploy/mongo-cutover/$cluster/$namespace"
lock=mongo-cutover-lock
mongo_secret=mongodb-member-api-credentials
writer_key=MONGO_MEMBER_API_PASSWORD
store_annotation=kitchensink.io/member-store

preflight() {
  local owner known active service gate
  owner=$("${kube[@]}" get namespace "$namespace" -o 'jsonpath={.metadata.labels.app\.kubernetes\.io/part-of}')
  [[ "$owner" == migrate-kitchensink ]] || fail 'Choose a namespace owned by migrate-kitchensink.'
  previous_store=$("${in_ns[@]}" get deployment modern -o 'jsonpath={.metadata.annotations.kitchensink\.io/member-store}')
  [[ -z "$previous_store" || "$previous_store" == h2 ]] || fail 'This namespace has already switched away from H2.'
  [[ -z "$("${in_ns[@]}" get configmap "$lock" --ignore-not-found -o name)" ]] || fail 'A cutover lock already exists.'
  for service in modern public-web; do
    gate=$("${in_ns[@]}" get service "$service" -o 'jsonpath={.spec.selector.kitchensink-cutover}')
    [[ -z "$gate" ]] || fail "Service $service has an existing cutover fence."
  done
  known=$("${in_ns[@]}" get jobs -l app=member-backfill -o 'jsonpath={range .items[*]}{.metadata.name}{"\t"}{.status.conditions[?(@.status=="True")].type}{"\n"}{end}')
  active=$(awk -F '\t' '$1 && $2 !~ /(^| )(Complete|Failed)( |$)/ {print $1}' <<< "$known")
  [[ -z "$active" ]] || fail 'Wait for the active member backfill Jobs to finish before cutover.'
  for service in h2 mongodb live-integration; do
    "${in_ns[@]}" wait --for=condition=Ready pod -l "app=$service" --timeout=30s
  done
}

read_image() {
  local id actual
  [[ -s "$image_lock" ]] || fail 'Run ./deploy/modern.sh build before preparing the cutover.'
  IFS=$'\t' read -r image id < "$image_lock"
  [[ "$id" =~ ^sha256:[0-9a-f]{64}$ && "$image" == "migrate-kitchensink-modern:content-${id#sha256:}" ]] ||
    fail 'The modern image lock is invalid.'
  actual=$(docker image inspect --format '{{.Id}}' "$image")
  [[ "$actual" == "$id" ]] || fail 'The local modern image differs from the build lock.'
}

mongo_admin() {
  "${in_ns[@]}" exec statefulset/mongodb --container mongodb -- sh -ec '
    exec mongosh kitchensink --host 127.0.0.1 --quiet --norc \
      --username "$MONGO_INITDB_ROOT_USERNAME" --password "$MONGO_INITDB_ROOT_PASSWORD" \
      --authenticationDatabase admin --eval "$1"
  ' sh "$1"
}

ensure_mongo_writer() {
  local password
  if [[ -z "$("${in_ns[@]}" get secret "$mongo_secret" --ignore-not-found -o name)" ]]; then
    password=$(openssl rand -hex 24)
    if ! printf '%s=%s\n' "$writer_key" "$password" |
      "${in_ns[@]}" create secret generic "$mongo_secret" --from-env-file=/dev/stdin >/dev/null 2>&1; then
      "${in_ns[@]}" get secret "$mongo_secret" -o name >/dev/null
    fi
  fi
  password=$("${in_ns[@]}" get secret "$mongo_secret" -o "jsonpath={.data.$writer_key}" | openssl base64 -d -A)
  [[ "$password" =~ ^[a-f0-9]{48}$ ]] || fail 'The MongoDB API Secret must contain a generated password.'

  if ! printf '%s\n' "$password" | "${in_ns[@]}" exec -i statefulset/mongodb --container mongodb -- sh -eu -c '
    IFS= read -r MONGO_MEMBER_API_PASSWORD
    export MONGO_MEMBER_API_PASSWORD
    exec mongosh kitchensink --host 127.0.0.1 --quiet --norc \
      --username "$MONGO_INITDB_ROOT_USERNAME" --password "$MONGO_INITDB_ROOT_PASSWORD" \
      --authenticationDatabase admin --eval "$1"
  ' sh '
    const role = "memberApiWriter";
    const privileges = [
      {resource: {db: "kitchensink", collection: "members"}, actions: ["find", "insert"]},
      {resource: {db: "kitchensink", collection: "counters"}, actions: ["find", "update"]},
    ];
    if (db.getRole(role)) db.updateRole(role, {privileges, roles: []});
    else db.createRole({role, privileges, roles: []});
    const user = "members_api";
    const spec = {pwd: process.env.MONGO_MEMBER_API_PASSWORD, roles: [{role, db: "kitchensink"}]};
    if (db.getUser(user)) db.updateUser(user, spec);
    else db.createUser({user, ...spec});
    if (!db.members.getIndexes().some(index => index.name === "members_email_unique" && index.unique)) {
      throw new Error("Missing unique member email index");
    }
  ' >/dev/null 2>&1; then
    fail 'Unable to provision the MongoDB API account or verify the unique email index.'
  fi
}

h2_sql() {
  "${in_ns[@]}" exec statefulset/h2 --container h2 -- sh -ec '
    exec java -cp /opt/h2.jar org.h2.tools.Shell -url jdbc:h2:tcp://127.0.0.1:9092/kitchensink \
      -user maintenance -password "$H2_MAINT_PASSWORD" -list -sql "$1"
  ' sh "$1"
}

h2_count() {
  local result count
  result=$(h2_sql "$1" 2>/dev/null) || return 1
  if [[ "$result" == *Error:* ]]; then return 1; fi
  count=$(sed -n -E 's/^SINGLE_COUNT: ([0-9]+)$/\1/p' <<< "$result")
  [[ "$count" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$count"
}

wait_for_outbox() {
  local deadline=$((SECONDS + drain_timeout)) count
  while true; do
    count=$(h2_count 'SELECT COUNT(*) AS SINGLE_COUNT FROM PUBLIC.MEMBER_INSERT_OUTBOX') ||
      fail 'Unable to read the H2 outbox count.'
    if [[ "$count" == 0 ]]; then
      echo 'H2 outbox drained.'
      return
    fi
    if (( SECONDS >= deadline )); then fail "H2 outbox still contains $count events after ${drain_timeout}s."; fi
    sleep 1
  done
}

fingerprint() {
  node -e '
    const { createHash } = require("node:crypto");
    const { createInterface } = require("node:readline");
    (async () => {
      const digest = createHash("sha256");
      let count = 0n;
      let declaredCount;
      let lastId;
      for await (const line of createInterface({input: process.stdin, crlfDelay: Infinity})) {
        if (line.startsWith("Error:")) throw Error();
        if (line.startsWith("MEMBER_COUNT:")) {
          const raw = line.slice(13).trim();
          if (declaredCount !== undefined || !/^\d+$/.test(raw)) throw Error();
          declaredCount = BigInt(raw);
        }
        if (!line.startsWith("MEMBER_JSON:")) continue;
        const row = JSON.parse(line.slice(12));
        if (!Array.isArray(row) || row.length !== 4 || typeof row[0] !== "string" || !/^-?\d+$/.test(row[0]) ||
            !row.slice(1).every(value => value === null || typeof value === "string")) throw Error();
        const id = BigInt(row[0]);
        if (lastId !== undefined && id <= lastId) throw Error();
        lastId = id;
        count++;
        digest.update(JSON.stringify(row)).update("\n");
      }
      if (declaredCount !== count) throw Error();
      process.stdout.write(`${count} ${lastId ?? 0n} ${digest.digest("hex")}\n`);
    })().catch(() => { process.stderr.write("Invalid member snapshot\n"); process.exitCode = 1; });
  '
}

verify_members() {
  local h2 mongo digest
  h2=$(h2_sql '
    SELECT CAST(JSON_ARRAY(CAST(ID AS VARCHAR), NAME, EMAIL, PHONE_NUMBER NULL ON NULL) AS VARCHAR) AS MEMBER_JSON
    FROM PUBLIC.MEMBER ORDER BY ID;
    SELECT COUNT(*) AS MEMBER_COUNT FROM PUBLIC.MEMBER;
  ' 2>/dev/null | fingerprint) || fail 'Unable to reconcile H2 members.'
  mongo=$(mongo_admin '
    db.members.aggregate([
      {$sort: {_id: 1}}, {$project: {_id: 0, idType: {$type: "$_id"}, id: {$toString: "$_id"},
        name: 1, email: 1, phoneNumber: 1}}
    ], {readConcern: {level: "majority"}}).forEach(row => {
      if (row.idType !== "long" || ![row.name, row.email, row.phoneNumber].every(
          value => value === null || typeof value === "string")) throw new Error("Invalid member document");
      print("MEMBER_JSON: " + JSON.stringify([row.id, row.name, row.email, row.phoneNumber]));
    });
    print("MEMBER_COUNT: " + db.members.countDocuments({}, {hint: "_id_", readConcern: {level: "majority"}}));
  ' 2>/dev/null | fingerprint) || fail 'Unable to reconcile MongoDB members.'
  [[ "$h2" == "$mongo" ]] || fail 'H2 and MongoDB member data differ. Cutover remains blocked.'
  read -r member_count max_member_id digest <<< "$h2"
  [[ "$max_member_id" != 9223372036854775807 ]] || fail 'The MongoDB numeric ID sequence is exhausted.'
  printf 'Exact member parity confirmed for %s members. Maximum ID %s.\n' "$member_count" "$max_member_id"
}

initialize_counter() {
  if ! mongo_admin "
    const floor = NumberLong('$max_member_id');
    const result = db.counters.updateOne({_id: 'member'}, [{\$set: {value: {\$max: [
      {\$toLong: {\$ifNull: ['\$value', NumberLong('0')]}}, floor
    ]}}}], {upsert: true, writeConcern: {w: 'majority', j: true}});
    if (!result.acknowledged) throw new Error('Counter update was not acknowledged');
  " >/dev/null 2>&1; then
    fail 'Unable to initialize the MongoDB member ID counter.'
  fi
  echo 'MongoDB member counter initialized.'
}

fence() {
  "${in_ns[@]}" patch service "$1" --type=merge -p '{"spec":{"selector":{"kitchensink-cutover":"closed"}}}'
}

unfence() {
  "${in_ns[@]}" patch service "$1" --type=merge -p '{"spec":{"selector":{"kitchensink-cutover":null}}}'
}

stop_forward() {
  if [[ -n "$forward_pid" ]]; then
    kill "$forward_pid" 2>/dev/null || true
    wait "$forward_pid" 2>/dev/null || true
    forward_pid=
  fi
}

wait_for_old_pods() {
  local app
  for app in "$@"; do
    if [[ -n "$("${in_ns[@]}" get pods -l "app=$app" -o name)" ]]; then
      "${in_ns[@]}" wait --for=delete pod -l "app=$app" --timeout=90s || return 1
    fi
    [[ -z "$("${in_ns[@]}" get pods -l "app=$app" -o name)" ]] || return 1
  done
}

restore_h2() {
  "${in_ns[@]}" scale deployment/modern --replicas=0 || return 1
  wait_for_old_pods modern || return 1
  if (( mongo_manifest_applied )); then
    "${in_ns[@]}" patch deployment modern --type=json --patch-file "$state_dir/previous-modern-template.json" || return 1
    if [[ -n "$previous_store" ]]; then
      "${in_ns[@]}" annotate deployment modern "$store_annotation=$previous_store" --overwrite || return 1
    else
      "${in_ns[@]}" annotate deployment modern "$store_annotation-" || return 1
    fi
  fi
  "${in_ns[@]}" scale deployment/modern --replicas="$modern_replicas" || return 1
  "${in_ns[@]}" rollout status deployment/modern --timeout=120s || return 1
  unfence modern || return 1
  "${in_ns[@]}" scale deployment/legacy --replicas="$legacy_replicas" || return 1
  "${in_ns[@]}" rollout status deployment/legacy --timeout=120s || return 1
  unfence public-web || return 1
}

cleanup() {
  local rc=$?
  trap - EXIT
  trap '' INT TERM
  stop_forward
  if (( rc != 0 && maintenance_started )); then
    if (( ! mongo_writes_released )) && restore_h2; then
      echo 'Restored the previous H2 deployment before releasing MongoDB writes.' >&2
    else
      fence public-web >&2 || true
      fence modern >&2 || true
      echo "Services are fenced. Inspect deployment modern and ConfigMap $lock before resuming." >&2
      exit "$rc"
    fi
  fi
  "${in_ns[@]}" delete configmap "$lock" --ignore-not-found >/dev/null ||
    echo "Remove the completed cutover lock $lock after checking the deployment." >&2
  exit "$rc"
}

smoke_mongo() {
  local port
  "${in_ns[@]}" port-forward --address 127.0.0.1 deployment/modern :8080 > "$state_dir/smoke-forward.log" 2>&1 &
  forward_pid=$!
  for (( attempt=0; attempt<15; attempt++ )); do
    port=$(sed -nE 's/^Forwarding from 127\.0\.0\.1:([0-9]+) -> 8080$/\1/p' "$state_dir/smoke-forward.log")
    if [[ "$port" =~ ^[0-9]+$ ]]; then
      if ! curl --fail --silent --show-error --max-time 5 \
        "http://127.0.0.1:$port/kitchensink/rest/members" |
        node -e 'const count = Number(process.argv[1]);
          try { const body = JSON.parse(require("node:fs").readFileSync(0, "utf8"));
            if (!Array.isArray(body) || body.length !== count) process.exit(1);
          } catch { process.exit(1); }' "$member_count"; then
        fail 'The MongoDB member list did not pass its read smoke test.'
      fi
      stop_forward
      echo 'Mongo-backed member list passed the read smoke test.'
      return
    fi
    sleep 1
  done
  fail 'Unable to open the local Mongo-backed pod for its read smoke test.'
}

prepare() {
  preflight
  read_image
  kind load docker-image "$image" --name "$cluster"
  ensure_mongo_writer
  sed "s|IMAGE_MODERN|$image|g" deploy/k8s/modern-mongo.yaml |
    "${in_ns[@]}" apply --dry-run=server -f - >/dev/null
  mkdir -p "$state_dir"
  printf '%s\n' "$image" > "$state_dir/prepared-image"
  printf 'MongoDB cutover prepared in %s.\n' "$namespace"
}

cutover() {
  preflight
  read_image
  [[ -s "$state_dir/prepared-image" && "$(cat "$state_dir/prepared-image")" == "$image" ]] ||
    fail 'Prepare this image in the selected namespace before starting maintenance.'
  "${in_ns[@]}" create configmap "$lock" --from-literal="image=$image" >/dev/null
  maintenance_started=0 mongo_manifest_applied=0 mongo_writes_released=0 forward_pid=
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  modern_replicas=$("${in_ns[@]}" get deployment modern -o 'jsonpath={.spec.replicas}')
  legacy_replicas=$("${in_ns[@]}" get deployment legacy -o 'jsonpath={.spec.replicas}')
  [[ "$modern_replicas" =~ ^[1-9][0-9]*$ && "$legacy_replicas" =~ ^[1-9][0-9]*$ ]] ||
    fail 'Both H2-backed application deployments must be running before cutover.'
  "${in_ns[@]}" get deployment modern -o json | node -e '
    const previous = JSON.parse(require("node:fs").readFileSync(0, "utf8"));
    process.stdout.write(JSON.stringify([{op: "replace", path: "/spec/template", value: previous.spec.template}]));
  ' > "$state_dir/previous-modern-template.json"

  local start=$SECONDS writers
  maintenance_started=1
  fence public-web
  fence modern
  "${in_ns[@]}" scale deployment/legacy deployment/modern --replicas=0
  wait_for_old_pods legacy modern || fail 'The H2-backed application pods did not stop.'
  writers=$(h2_count "SELECT COUNT(*) AS SINGLE_COUNT FROM INFORMATION_SCHEMA.SESSIONS WHERE UPPER(USER_NAME) IN ('MEMBERS','MEMBERS_WRITER')") ||
    fail 'Unable to check active H2 writer sessions.'
  [[ "$writers" == 0 ]] || fail "H2 still has $writers application sessions capable of writing."
  wait_for_outbox
  verify_members
  initialize_counter

  mongo_manifest_applied=1
  sed "s|IMAGE_MODERN|$image|g" deploy/k8s/modern-mongo.yaml | "${in_ns[@]}" apply -f -
  "${in_ns[@]}" scale deployment/modern --replicas="$modern_replicas"
  "${in_ns[@]}" rollout status deployment/modern --timeout=120s
  smoke_mongo
  "${in_ns[@]}" scale deployment/legacy --replicas="$legacy_replicas"
  "${in_ns[@]}" rollout status deployment/legacy --timeout=120s

  mongo_writes_released=1
  unfence modern
  unfence public-web
  printf 'MongoDB reads and writes active in %s after %ss of maintenance. Reconnect existing port-forwards.\n' \
    "$namespace" "$((SECONDS - start))"
}

command -v node >/dev/null || fail 'Install Node.js to run the cutover checks.'
umask 077
"$action"
