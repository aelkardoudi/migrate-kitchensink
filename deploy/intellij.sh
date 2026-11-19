#!/usr/bin/env bash
# Keep the IDE run session alive; its Stop button shuts down the local workloads.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
demo="$root/deploy/demo.sh"
options=("$@")
"$demo" up "${options[@]}"

forward_pid=
cleanup() {
  trap - EXIT INT TERM
  if [[ -n "$forward_pid" ]]; then
    kill "$forward_pid" 2>/dev/null || true
    wait "$forward_pid" 2>/dev/null || true
  fi
  "$demo" stop "${options[@]}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$demo" forward "${options[@]}" &
forward_pid=$!
wait "$forward_pid"
