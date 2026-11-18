#!/usr/bin/env bash
# Keep the IDE run session alive; its Stop button shuts down the local workloads.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
demo="$root/deploy/demo.sh"
modern="$root/deploy/modern.sh"

"$demo" up

forward_pid=
modern_started=
cleanup() {
  trap - EXIT INT TERM
  if [[ -n "$forward_pid" ]]; then
    kill "$forward_pid" 2>/dev/null || true
    wait "$forward_pid" 2>/dev/null || true
  fi
  if [[ -n "$modern_started" ]]; then
    "$modern" stop || true
  fi
  "$demo" stop
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$modern" up
modern_started=1

printf '\nKitchensink: http://127.0.0.1:18201/kitchensink/\nStop this run session to stop Spring Boot, WildFly and H2 (data is kept).\n\n'
"$demo" forward &
forward_pid=$!
wait "$forward_pid"
