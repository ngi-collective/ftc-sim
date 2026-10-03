#!/usr/bin/env bash
#
# The Driver Hub as one command. By default that is one process: the JVM simulates the robot and
# serves the built browser UI on the same port as its socket. With --dev it is two, the JVM and
# Vite's dev server, for hot reload while editing the UI.
#
# Two processes used to be the default, and starting them separately was a trap. The UI could
# only ever reach the socket on one port, so a JVM started on any other one left the page connected
# to whatever else was listening -- green indicator, wrong field, no error. Now the page is served
# from the socket's own port, and in --dev the port is decided here and handed to both halves.
#
# Usage:
#   tools/dashboard.sh                                  # official empty field
#   tools/dashboard.sh --scenario practice-balls        # an arrangement from TeamCode/scenarios
#   tools/dashboard.sh --dev                            # Vite dev server, for working on the UI
#   DASHBOARD_PORT=9000 tools/dashboard.sh              # both halves move together
#
# The UI is a production build, because the development build is half the cost of running it.
# React's development build emits a `performance.measure` per component per commit and this page
# commits at 20 Hz: profiled at 6x CPU throttle, the dev build spent 28% of the main thread in
# `clearMeasures` and 20% in `jsxDEV`, ran at 27-30 fps and stalled for 100 ms at a time, while the
# production build of the same commit held 60 fps with a 16.7 ms p95. `--dev` brings back hot
# reload for anyone actually editing the UI.
#
# Everything else passed here goes on to DashboardMain; see its javadoc for the rest of the flags.
set -euo pipefail

port="${DASHBOARD_PORT:-8765}"
ui_port=5183

# Refused rather than honoured. This script has to know the port to point the UI at it, so a
# --port that only the JVM sees would put us straight back into the bug this exists to remove.
# The message names the port they asked for, because being told the right way to say the thing you
# just said is more use than being told the default.
wanted=""
forwarded=""
dev=""
for argument in "$@"; do
  case $argument in
    --dev)
      dev=yes
      continue
      ;;
    --port) wanted="next" ;;
    --port=*) wanted="${argument#--port=}" ;;
    *) if [[ $wanted == next ]]; then wanted=$argument; fi ;;
  esac
  if [[ -n $wanted && $wanted != next ]]; then
    echo "dashboard: use DASHBOARD_PORT=$wanted so the UI moves with the simulation, not --port" >&2
    exit 2
  fi
  forwarded="$forwarded $argument"
done
if [[ $wanted == next ]]; then
  echo "dashboard: --port needs a number, and it belongs in DASHBOARD_PORT so the UI moves"\
       "with the simulation" >&2
  exit 2
fi

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

if [[ -z $dev ]]; then
  # One process: the JVM serves the built UI and the socket on the same port.
  echo "[dashboard] open http://127.0.0.1:$port"
  exec ./gradlew :TeamCode:dashboard --args="--port $port$forwarded"
fi

# --dev: Vite on its own port with hot reload, proxying /ws to the JVM.
cd "$root/driver-hub-dashboard"
bun install --silent
DASHBOARD_PORT="$port" bun run dev --clearScreen false &
ui=$!
trap 'kill "$ui" 2>/dev/null || true' EXIT
echo "[dashboard] open http://127.0.0.1:$ui_port  (dev server; simulation on ws://127.0.0.1:$port)"
cd "$root"
./gradlew :TeamCode:dashboard --args="--port $port$forwarded"
