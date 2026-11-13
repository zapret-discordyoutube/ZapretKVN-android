#!/usr/bin/env bash
set -euo pipefail

# Offline tests for scripts/phone-adb-forward.sh: a fake panel directory with a fake
# unix socket, high test ports, a fake SDK adb. The real ports 5037/15037 and the
# real panel link are never touched.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"
FWD_PIDS=()
cleanup() {
    "$SCRIPT_DIR/phone-adb-forward.sh" stop >/dev/null 2>&1 || true
    for pid in "${FWD_PIDS[@]:-}"; do [[ -z "$pid" ]] || kill "$pid" 2>/dev/null || true; done
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

fail() { echo "FAIL: $1" >&2; exit 1; }

command -v socat >/dev/null || { echo "test-phone-adb-forward: socat missing, skipped"; exit 0; }
command -v ss >/dev/null || { echo "test-phone-adb-forward: ss missing, skipped"; exit 0; }

PORT_A=$((20000 + RANDOM % 20000))
PORT_B=$((PORT_A + 1))
export ZAPRET_ADB_FWD_PORTS="$PORT_A $PORT_B"
export ZAPRET_ADB_FWD_DIR="$WORK_DIR/state"
export ZAPRET_CCWEB_DIR="$WORK_DIR/ccweb"
export CCWEB_CHAT=c_test
mkdir -p "$ZAPRET_CCWEB_DIR/adb"
printf '#!/bin/sh\necho "List of devices attached"\n' > "$WORK_DIR/adb"
chmod +x "$WORK_DIR/adb"
export ZAPRET_ADB="$WORK_DIR/adb"
FWD="$SCRIPT_DIR/phone-adb-forward.sh"

run() { set +e; OUT="$("$FWD" "$@" 2>&1)"; RC=$?; set -e; }

run start
[[ "$RC" -ne 0 && "$OUT" == *"no panel adb socket"* ]] || fail "no socket: rc=$RC out=$OUT"
echo "ok: refuses without a panel socket"

# A fake panel link: a unix socket that echoes whatever it gets.
SOCK="$ZAPRET_CCWEB_DIR/adb/c_test.sock"
socat "UNIX-LISTEN:$SOCK,fork" EXEC:cat >/dev/null 2>&1 &
FWD_PIDS+=("$!")
for _ in 1 2 3 4 5 6 7 8 9 10; do [[ -S "$SOCK" ]] && break; sleep 0.2; done
[[ -S "$SOCK" ]] || fail "fake socket did not appear"

touch -d '3 minutes ago' "$ZAPRET_CCWEB_DIR/svc.alive"
run start
[[ "$RC" -ne 0 && "$OUT" == *"limit 60 s"* ]] || fail "stale svc.alive: rc=$RC out=$OUT"
echo "ok: refuses when the panel service is not alive (svc.alive older than 60 s)"

touch "$ZAPRET_CCWEB_DIR/svc.alive"
run start
[[ "$RC" -eq 0 ]] || fail "start failed: $OUT"
[[ "$(grep -c 'forwarding to' <<<"$OUT")" -eq 2 ]] || fail "expected two forwarders: $OUT"
echo "ok: start opens both ports"

# Bytes go through to the socket and back.
echo_back="$(printf 'ping\n' | timeout 5 socat - "TCP:127.0.0.1:$PORT_A" 2>/dev/null || true)"
[[ "$echo_back" == "ping" ]] || fail "forwarded data did not come back: '$echo_back'"
echo "ok: data passes through the forwarder"

run start
[[ "$RC" -eq 0 && "$(grep -c 'already forwarded' <<<"$OUT")" -eq 2 ]] || fail "second start: rc=$RC out=$OUT"
echo "ok: start is idempotent"

run status
[[ "$(grep -c ': ours' <<<"$OUT")" -eq 2 ]] || fail "status: $OUT"
echo "ok: status sees both forwarders"

run stop
[[ "$RC" -eq 0 && "$(grep -c 'stopped' <<<"$OUT")" -eq 2 ]] || fail "stop: rc=$RC out=$OUT"
sleep 0.5
[[ -z "$(ss -ltnpH "sport = :$PORT_A")" ]] || fail "port $PORT_A still listening after stop"
echo "ok: stop closes both ports"

# A port held by somebody else is refused and left alone.
socat "TCP-LISTEN:$PORT_A,bind=127.0.0.1,reuseaddr,fork" EXEC:cat >/dev/null 2>&1 &
OTHER=$!
FWD_PIDS+=("$OTHER")
sleep 0.5
run start
[[ "$RC" -ne 0 && "$OUT" == *"not ours"* ]] || fail "busy port: rc=$RC out=$OUT"
kill -0 "$OTHER" 2>/dev/null || fail "someone else's listener was killed"
run stop
kill -0 "$OTHER" 2>/dev/null || fail "stop killed someone else's listener"
echo "ok: a foreign listener is refused and never killed"

echo "test-phone-adb-forward: all passed"
