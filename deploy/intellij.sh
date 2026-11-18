#!/usr/bin/env bash
# Keep the IDE run session alive; its Stop button shuts down the local workloads.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
demo="$root/deploy/demo.sh"

"$demo" up

forward_pid=
cleanup() {
  trap - EXIT INT TERM
  if [[ -n "$forward_pid" ]]; then
    kill "$forward_pid" 2>/dev/null || true
    wait "$forward_pid" 2>/dev/null || true
  fi
  "$demo" stop
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf '\nKitchensink: http://127.0.0.1:18201/kitchensink/\nStop this run session to stop WildFly and H2 (data is kept).\n\n'
"$demo" forward &
forward_pid=$!
wait "$forward_pid"
