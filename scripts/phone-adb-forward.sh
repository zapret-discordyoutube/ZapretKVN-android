#!/usr/bin/env bash
set -euo pipefail

# TCP forwarders 127.0.0.1:5037 and 127.0.0.1:15037 -> the panel's adb socket, for
# tools that hard-code `adb -H 127.0.0.1 -P <port>` or use the SDK adb inside a
# Claude SSH Web chat. The phone itself is reached by plain `adb` (the panel
# wrapper); see the section "Connect to the phone from a panel chat session" of
# .agents/skills/test-zapret-android/SKILL.md.
#
# Usage: scripts/phone-adb-forward.sh start|stop|status
#
# The forwarders die with the chat; the socket name is chat-specific. Processes are
# tracked by PID files and stopped by PID (never `pkill -f`: a pattern that is also
# in your own command line kills the tool shell with exit 144).

read -r -a PORTS <<<"${ZAPRET_ADB_FWD_PORTS:-5037 15037}"
STATE_DIR="${ZAPRET_ADB_FWD_DIR:-$HOME/.cache/kvn-adb}"
CCWEB_DIR="${ZAPRET_CCWEB_DIR:-$HOME/.ccweb}"
SDK_ADB="${ZAPRET_ADB:-${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb}"

die() { echo "phone-adb-forward: $*" >&2; exit 1; }

socket_path() {
    local sock="$CCWEB_DIR/adb/${CCWEB_CHAT:-_}.sock" alive="$CCWEB_DIR/svc.alive" age
    [[ -S "$sock" ]] || die "no panel adb socket $sock (not a panel chat, or the panel toggle 'Android (adb)' is off)"
    [[ -f "$alive" ]] || die "$alive is missing: the panel service is not alive"
    age=$(( $(date +%s) - $(stat -c %Y "$alive") ))
    [[ "$age" -lt 60 ]] || die "$alive is $age s old (limit 60 s): the panel link is dead, the socket has nobody behind it"
    printf '%s\n' "$sock"
}

# PID listening on a loopback port, empty when free.
listener_pid() {
    ss -ltnpH "sport = :$1" 2>/dev/null | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n 1
}

is_ours() {
    local port="$1" pid="$2" file="$STATE_DIR/$1.pid"
    [[ -n "$pid" && -f "$file" && "$(cat "$file")" == "$pid" && "$(cat "/proc/$pid/comm" 2>/dev/null)" == socat ]]
}

# Devices a real adb server on 127.0.0.1:<port> reports (SDK adb, panel wrapper bypassed).
server_devices() {
    env -u ADB_SERVER_SOCKET timeout 10 "$SDK_ADB" -H 127.0.0.1 -P "$1" devices 2>/dev/null \
        | awk 'NR > 1 && NF >= 2 { n++ } END { print n+0 }'
}

start_port() {
    local port="$1" sock="$2" pid
    pid="$(listener_pid "$port")"
    if [[ -n "$pid" ]]; then
        if is_ours "$port" "$pid"; then echo "port $port: already forwarded (pid $pid)"; return 0; fi
        if [[ "$port" == 5037 ]]; then
            [[ "$(server_devices 5037)" -eq 0 ]] \
                || { echo "port 5037: a real adb server with devices listens there (pid $pid); not touching it" >&2; return 1; }
            # An idle adb server with no devices (the SDK adb started it by itself): replace it.
            env -u ADB_SERVER_SOCKET "$SDK_ADB" -H 127.0.0.1 -P 5037 kill-server 2>/dev/null || true
            sleep 1
            [[ -z "$(listener_pid 5037)" ]] || { echo "port 5037: the idle adb server did not stop (pid $pid)" >&2; return 1; }
        else
            echo "port $port: busy (pid $pid), not ours" >&2; return 1
        fi
    fi
    mkdir -p "$STATE_DIR"
    nohup setsid socat "TCP-LISTEN:$port,bind=127.0.0.1,reuseaddr,fork" "UNIX-CONNECT:$sock" \
        >"$STATE_DIR/$port.log" 2>&1 </dev/null &
    sleep 1
    pid="$(listener_pid "$port")"
    [[ -n "$pid" ]] || { echo "port $port: socat did not start, see $STATE_DIR/$port.log" >&2; return 1; }
    echo "$pid" > "$STATE_DIR/$port.pid"
    echo "port $port: forwarding to $sock (pid $pid)"
}

stop_port() {
    local port="$1" pid
    pid="$(listener_pid "$port")"
    if [[ -z "$pid" ]]; then echo "port $port: not listening"; rm -f "$STATE_DIR/$port.pid"; return 0; fi
    if is_ours "$port" "$pid"; then
        kill "$pid"; rm -f "$STATE_DIR/$port.pid"; echo "port $port: stopped (pid $pid)"
    else
        echo "port $port: listener pid $pid is not ours, left alone" >&2
    fi
}

status_port() {
    local port="$1" pid
    pid="$(listener_pid "$port")"
    if [[ -z "$pid" ]]; then echo "port $port: free"
    elif is_ours "$port" "$pid"; then echo "port $port: ours (pid $pid), devices through it: $(server_devices "$port")"
    else echo "port $port: other listener (pid $pid), devices: $(server_devices "$port")"; fi
}

command -v ss >/dev/null || die "ss is required"
case "${1:-}" in
    start)
        command -v socat >/dev/null || die "socat is required"
        sock="$(socket_path)"
        rc=0
        for port in "${PORTS[@]}"; do start_port "$port" "$sock" || rc=1; done
        exit "$rc" ;;
    stop) for port in "${PORTS[@]}"; do stop_port "$port"; done ;;
    status) for port in "${PORTS[@]}"; do status_port "$port"; done ;;
    *) echo "Usage: $0 start|stop|status" >&2; exit 2 ;;
esac
