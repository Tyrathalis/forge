#!/usr/bin/env bash
# Build the web GUI module (forge-gui-web) and the modules under it.
#
# The -Pweb profile's frontend-maven-plugin downloads its own Node and runs npm ci, tsc, esbuild and vitest, so this
# is the one build step that fetches and runs npm packages. It exists as a script so that a permission rule can allow
# exactly this command and nothing else.
#
# Usage (from anywhere):
#   scripts/build-web.sh          # install with tests (Java + vitest)
#   scripts/build-web.sh --fast   # install without tests
#
# It runs at nice 19 under a 12 GB memory cap, because the research box may be training while it builds.

set -euo pipefail
cd "$(dirname "$0")/.."

tests=()
case "${1:-}" in
    "") ;;
    --fast) tests=(-DskipTests) ;;
    *) echo "usage: $0 [--fast]" >&2; exit 2 ;;
esac

exec systemd-run --user --scope -q -p MemoryMax=12G -p ManagedOOMPreference=avoid \
    nice -n 19 "$HOME/.local/opt/maven/bin/mvn" -q -Pweb -pl forge-gui-web -am install "${tests[@]}"
