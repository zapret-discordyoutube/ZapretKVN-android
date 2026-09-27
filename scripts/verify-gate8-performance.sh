#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
REPETITIONS="${GATE8_PERF_REPETITIONS:-5}"
IDLE_SECONDS="${GATE8_PERF_IDLE_SECONDS:-5}"
TRAFFIC_BYTES="${GATE8_PERF_TRAFFIC_BYTES:-8388608}"
DNS_QUERIES="${GATE8_PERF_DNS_QUERIES:-12}"
SIGNIFICANCE_PERCENT=5
MANAGED_IDLE_UID_LIMIT_BYTES=4096
MAX_IDLE_TUN_GROWTH_BYTES=512
PACKAGE="io.github.zapretkvn.android.debug"
COMPONENT="$PACKAGE/io.github.zapretkvn.android.debug.Gate8PerformanceProbeReceiver"
CONTROL_URI="content://io.github.zapretkvn.android.debug.test.traffic"
APP_APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$PROJECT_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
CORE="$PROJECT_ROOT/core-build/output/sing-box"
HOST_ALIAS="10.0.2.2"
CHUNK_BYTES=65536

adb_call() {
    local output status
    for _ in 1 2 3 4 5; do
        if output="$("$ADB" "$@" 2>&1)"; then
            printf '%s\n' "$output"
            return 0
        else
            status=$?
        fi
        if grep -Eqi 'device offline|device not found|no devices/emulators found' <<<"$output"; then
            "$ADB" wait-for-device >/dev/null 2>&1 || true
            sleep 1
            continue
        fi
        printf '%s\n' "$output" >&2
        return "$status"
    done
    printf '%s\n' "$output" >&2
    return "$status"
}

if [[ ! "$REPETITIONS" =~ ^[1-9][0-9]*$ || "$REPETITIONS" -lt 5 ]]; then
    echo "Gate 8 performance requires at least five repetitions" >&2
    exit 1
fi
for value in "$IDLE_SECONDS" "$TRAFFIC_BYTES" "$DNS_QUERIES"; do
    if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
        echo "Gate 8 performance values must be positive integers" >&2
        exit 1
    fi
done
if (( TRAFFIC_BYTES > 67108864 || DNS_QUERIES > 100 )); then
    echo "Traffic/query count exceeds the bounded debug probe" >&2
    exit 1
fi

device_count="$(adb_call devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count+0 }')"
if [[ "$device_count" -ne 1 ]]; then
    echo "Expected exactly one ready adb device, found $device_count" >&2
    exit 1
fi
is_emulator="$(adb_call shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$is_emulator" != "1" && "${GATE8_ALLOW_PHYSICAL:-0}" != "1" ]]; then
    echo "Physical execution resets batterystats; set GATE8_ALLOW_PHYSICAL=1 explicitly" >&2
    exit 1
fi

sdk="$(adb_call shell getprop ro.build.version.sdk | tr -d '\r')"
device="$(adb_call shell getprop ro.product.model | tr -d '\r' | tr ' /' '__')"
clock_ticks_source="android-getconf"
if ! clock_ticks="$(adb_call shell getconf CLK_TCK 2>/dev/null | tr -d '\r')" ||
    [[ ! "$clock_ticks" =~ ^[1-9][0-9]*$ ]]; then
    # Android 8 toybox has no getconf applet. Linux exposes /proc/PID/stat CPU time in
    # USER_HZ units, whose userspace ABI value is 100 even when the kernel timer HZ differs.
    clock_ticks=100
    clock_ticks_source="linux-user-hz"
fi
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_ROOT="${GATE8_PERF_OUTPUT:-$PROJECT_ROOT/build/gate8-performance/api${sdk}-${device}-${timestamp}}"
RAW_ROOT="$RESULT_ROOT/raw"
RESULTS_JSONL="$RESULT_ROOT/results.jsonl"
TEMP_ROOT="$(mktemp -d)"
mkdir -p "$RAW_ROOT"
: > "$RESULTS_JSONL"

echo_pid=""
proxy_pid=""
reserve_port() {
    local candidate
    while true; do
        candidate="$(shuf -i 20000-45000 -n 1)"
        if ! ss -H -ltn "sport = :$candidate" | grep -q .; then
            printf '%s\n' "$candidate"
            return
        fi
    done
}
ECHO_PORT="$(reserve_port)"
PROXY_PORT="$(reserve_port)"
while [[ "$PROXY_PORT" == "$ECHO_PORT" ]]; do PROXY_PORT="$(reserve_port)"; done

probe() {
    local action="$1"
    shift
    local output result
    output="$(adb_call shell am broadcast --receiver-foreground -a "$action" -n "$COMPONENT" "$@")"
    result="$(sed -n 's/.*data="\([^"]*\)".*/\1/p' <<<"$output" | tail -n 1)"
    if [[ -z "$result" || "$result" == error=* ]]; then
        echo "Gate 8 performance probe failed for $action: ${result:-$output}" >&2
        return 1
    fi
    printf '%s\n' "$result"
}

field() {
    local status="$1"
    local name="$2"
    tr ';' '\n' <<<"$status" | sed -n "s/^$name=//p" | tail -n 1
}

cleanup() {
    adb_call shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb_call shell wm dismiss-keyguard >/dev/null 2>&1 || true
    probe "io.github.zapretkvn.android.debug.GATE8_PERF_CLEANUP" >/dev/null 2>&1 || true
    adb_call shell appops set "$PACKAGE" ACTIVATE_VPN default >/dev/null 2>&1 || true
    if [[ -n "$proxy_pid" ]] && kill -0 "$proxy_pid" 2>/dev/null; then kill "$proxy_pid" 2>/dev/null || true; fi
    if [[ -n "$echo_pid" ]] && kill -0 "$echo_pid" 2>/dev/null; then kill "$echo_pid" 2>/dev/null || true; fi
    rm -rf "$TEMP_ROOT"
}
trap cleanup EXIT

if [[ ! -x "$CORE" ]]; then
    "$PROJECT_ROOT/scripts/build-core.sh" >/dev/null
fi
cd "$PROJECT_ROOT"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest >/dev/null
adb_call install -r "$APP_APK" >/dev/null
adb_call install -r "$TEST_APK" >/dev/null
adb_call shell appops set "$PACKAGE" ACTIVATE_VPN allow
if (( sdk >= 33 )); then adb_call shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS; fi

socat TCP4-LISTEN:"$ECHO_PORT",bind=0.0.0.0,reuseaddr,fork EXEC:/bin/cat \
    >"$TEMP_ROOT/echo.log" 2>&1 &
echo_pid=$!
jq -n --argjson proxy "$PROXY_PORT" --argjson echo "$ECHO_PORT" '
  {
    log: {level:"warn"},
    inbounds: [{type:"socks",tag:"socks-in",listen:"0.0.0.0",listen_port:$proxy}],
    outbounds: [{type:"direct",tag:"direct"}],
    route: {
      rules: [{network:"tcp",port:$echo,action:"route-options",override_address:"127.0.0.1"}],
      final:"direct"
    }
  }
' > "$TEMP_ROOT/proxy.json"
"$CORE" check -c "$TEMP_ROOT/proxy.json"
"$CORE" run -c "$TEMP_ROOT/proxy.json" >"$TEMP_ROOT/proxy.log" 2>&1 &
proxy_pid=$!
sleep 1
kill -0 "$echo_pid"
kill -0 "$proxy_pid"

adb_call shell input keyevent KEYCODE_WAKEUP >/dev/null
adb_call shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb_call shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
sleep 1

pid_of_app() {
    local pid
    pid="$(adb_call shell pidof "$PACKAGE" | tr -d '\r' | awk '{print $1}')"
    [[ "$pid" =~ ^[0-9]+$ ]] || { echo "Zapret KVN process is not running" >&2; exit 1; }
    printf '%s\n' "$pid"
}

cpu_ticks() {
    local pid="$1"
    adb_call shell cat "/proc/$pid/stat" | awk '{print $14+$15}'
}

tun_bytes() {
    adb_call shell cat /proc/net/dev 2>/dev/null | tr ':' ' ' | awk '$1 == "tun0" { print $2+$10; found=1 } END { if (!found) print 0 }'
}

process_value() {
    local pid="$1"
    local name="$2"
    adb_call shell cat "/proc/$pid/status" | sed -n "s/^$name:[[:space:]]*\([0-9]*\).*/\1/p" | head -n 1
}

pss_kb() {
    local pid="$1"
    local dump="$2"
    local value
    value="$(sed -n 's/.*TOTAL PSS:[[:space:]]*\([0-9]*\).*/\1/p' <<<"$dump" | head -n 1)"
    if [[ -z "$value" ]]; then
        value="$(awk '$1 == "TOTAL" { print $2; exit }' <<<"$dump")"
    fi
    printf '%s\n' "${value:-0}"
}

start_battery_window() {
    local scenario="$1"
    mkdir -p "$RAW_ROOT/$scenario"
    adb_call shell dumpsys batterystats --reset >"$RAW_ROOT/$scenario/batterystats-reset.txt" 2>&1 || true
}

finish_raw_window() {
    local scenario="$1"
    local pid
    pid="$(pid_of_app)"
    adb_call shell dumpsys batterystats --charged >"$RAW_ROOT/$scenario/batterystats.txt" 2>&1 || true
    adb_call shell dumpsys meminfo "$pid" >"$RAW_ROOT/$scenario/meminfo.txt" 2>&1 || true
    adb_call shell dumpsys procstats "$PACKAGE" >"$RAW_ROOT/$scenario/procstats.txt" 2>&1 || true
    adb_call shell dumpsys power >"$RAW_ROOT/$scenario/power.txt" 2>&1 || true
    adb_call shell dumpsys alarm >"$RAW_ROOT/$scenario/alarms.txt" 2>&1 || true
    adb_call shell dumpsys jobscheduler >"$RAW_ROOT/$scenario/jobs.txt" 2>&1 || true
    adb_call shell dumpsys netstats detail >"$RAW_ROOT/$scenario/netstats.txt" 2>&1 || true
}

prepare() {
    local route="$1"
    local stack="$2"
    local mtu="$3"
    local dns="$4"
    adb_call shell am force-stop "$PACKAGE" >/dev/null
    adb_call shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
    sleep 1
    probe "io.github.zapretkvn.android.debug.GATE8_PERF_PREPARE" \
        --es route_mode "$route" \
        --es stack "${stack:-default}" \
        --ei mtu "$mtu" \
        --es dns_strategy "$dns" \
        --ei proxy_port "$PROXY_PORT" >/dev/null
    connected="$(probe "io.github.zapretkvn.android.debug.GATE8_PERF_CONNECT")"
    [[ ";$connected;" == *";state=connected;"* ]] || { echo "VPN did not connect: $connected" >&2; exit 1; }
    adb_call shell input keyevent KEYCODE_HOME >/dev/null
    sleep 2
}

stop_vpn() {
    local stopped
    stopped="$(probe "io.github.zapretkvn.android.debug.GATE8_PERF_STOP")"
    [[ ";$stopped;" == *";state=stopped;"* && ";$stopped;" == *";core=0;"* ]] || {
        echo "VPN resources did not close: $stopped" >&2
        exit 1
    }
}

selected_workload() {
    local remaining="$TRAFFIC_BYTES"
    local request_bytes output elapsed total_elapsed=0
    while (( remaining > 0 )); do
        request_bytes="$remaining"
        if (( request_bytes > 1048576 )); then request_bytes=1048576; fi
        output="$(probe "io.github.zapretkvn.android.debug.GATE8_PERF_TRAFFIC" \
            --es address "$HOST_ALIAS" --ei port "$ECHO_PORT" \
            --ei bytes "$request_bytes" --ei chunk_bytes "$CHUNK_BYTES")"
        elapsed="$(field "$output" elapsedNanos)"
        [[ "$elapsed" =~ ^[0-9]+$ ]] || { echo "Invalid selected workload result: $output" >&2; return 1; }
        total_elapsed=$((total_elapsed + elapsed))
        remaining=$((remaining - request_bytes))
    done
    printf 'bytes=%s;elapsedNanos=%s\n' "$TRAFFIC_BYTES" "$total_elapsed"
}

unselected_workload() {
    local started ended output
    started="$(date +%s%N)"
    output="$(adb_call shell content call --uri "$CONTROL_URI" --method tcp-bulk \
        --arg "$HOST_ALIAS,$ECHO_PORT,$TRAFFIC_BYTES,$CHUNK_BYTES")"
    ended="$(date +%s%N)"
    [[ "$output" == *"success=true"* ]] || { echo "Unselected traffic failed: $output" >&2; exit 1; }
    printf 'bytes=%s;elapsedNanos=%s\n' "$TRAFFIC_BYTES" "$((ended - started))"
}

dns_workload() {
    local prefix="$1"
    probe "io.github.zapretkvn.android.debug.GATE8_PERF_DNS" \
        --ei query_count "$DNS_QUERIES" --es query_prefix "$prefix"
}

idle_workload() {
    sleep "$IDLE_SECONDS"
    printf 'elapsedNanos=%s\n' "$((IDLE_SECONDS * 1000000000))"
}

measure_once() {
    local scenario="$1"
    local repetition="$2"
    local workload="$3"
    local pid before_cpu before_tun before_time output after_time after_cpu after_tun meminfo
    local before_status before_uid_rx before_uid_tx pss rss threads fds runtime_status
    local after_uid_rx after_uid_tx uid_rx_delta uid_tx_delta uid_network_delta
    local status_clients log_clients elapsed_nanos bytes cpu_delta tun_delta throughput
    pid="$(pid_of_app)"
    before_status="$(probe "io.github.zapretkvn.android.debug.GATE8_PERF_STATUS")"
    before_uid_rx="$(field "$before_status" uidRxBytes)"
    before_uid_tx="$(field "$before_status" uidTxBytes)"
    before_cpu="$(cpu_ticks "$pid")"
    before_tun="$(tun_bytes)"
    before_time="$(date +%s%N)"
    case "$workload" in
        idle) output="$(idle_workload)" ;;
        selected) output="$(selected_workload)" ;;
        unselected) output="$(unselected_workload)" ;;
        dns) output="$(dns_workload "${scenario//_/-}-$repetition")" ;;
        *) echo "Unknown workload: $workload" >&2; exit 1 ;;
    esac
    after_time="$(date +%s%N)"
    after_cpu="$(cpu_ticks "$pid")"
    after_tun="$(tun_bytes)"
    meminfo="$(adb_call shell dumpsys meminfo "$pid")"
    pss="$(pss_kb "$pid" "$meminfo")"
    rss="$(process_value "$pid" VmRSS)"
    runtime_status="$(probe "io.github.zapretkvn.android.debug.GATE8_PERF_STATUS")"
    threads="$(field "$runtime_status" threads)"
    fds="$(field "$runtime_status" fds)"
    status_clients="$(field "$runtime_status" statusClients)"
    log_clients="$(field "$runtime_status" logClients)"
    after_uid_rx="$(field "$runtime_status" uidRxBytes)"
    after_uid_tx="$(field "$runtime_status" uidTxBytes)"
    for value in "$before_uid_rx" "$before_uid_tx" "$after_uid_rx" "$after_uid_tx"; do
        [[ "$value" =~ ^-?[0-9]+$ ]] || { echo "Invalid TrafficStats value: $runtime_status" >&2; exit 1; }
    done
    if (( before_uid_rx < 0 || before_uid_tx < 0 || after_uid_rx < 0 || after_uid_tx < 0 )); then
        echo "Per-UID TrafficStats is unavailable on this gate device" >&2
        exit 1
    fi
    uid_rx_delta="$((after_uid_rx - before_uid_rx))"
    uid_tx_delta="$((after_uid_tx - before_uid_tx))"
    if (( uid_rx_delta < 0 || uid_tx_delta < 0 )); then
        echo "Per-UID TrafficStats counters moved backwards" >&2
        exit 1
    fi
    uid_network_delta="$((uid_rx_delta + uid_tx_delta))"
    elapsed_nanos="$(field "$output" elapsedNanos)"
    bytes="$(field "$output" bytes)"
    elapsed_nanos="${elapsed_nanos:-$((after_time - before_time))}"
    bytes="${bytes:-0}"
    cpu_delta="$((after_cpu - before_cpu))"
    tun_delta="$((after_tun - before_tun))"
    throughput="$(awk -v bytes="$bytes" -v nanos="$elapsed_nanos" 'BEGIN { if (bytes == 0 || nanos == 0) print 0; else printf "%.6f", bytes*8*1000/nanos }')"
    jq -cn \
        --arg scenario "$scenario" \
        --argjson repetition "$repetition" \
        --arg workload "$workload" \
        --argjson elapsed_nanos "$elapsed_nanos" \
        --argjson cpu_ticks "$cpu_delta" \
        --argjson pss_kb "${pss:-0}" \
        --argjson rss_kb "${rss:-0}" \
        --argjson threads "${threads:-0}" \
        --argjson fds "${fds:-0}" \
        --argjson status_clients "${status_clients:-0}" \
        --argjson log_clients "${log_clients:-0}" \
        --argjson tun_bytes "$tun_delta" \
        --argjson uid_network_bytes "$uid_network_delta" \
        --argjson workload_bytes "$bytes" \
        --argjson throughput_mbps "$throughput" \
        --arg probe "$output" \
        '{scenario:$scenario,repetition:$repetition,workload:$workload,elapsed_nanos:$elapsed_nanos,cpu_ticks:$cpu_ticks,pss_kb:$pss_kb,rss_kb:$rss_kb,threads:$threads,fds:$fds,status_clients:$status_clients,log_clients:$log_clients,tun_bytes:$tun_bytes,uid_network_bytes:$uid_network_bytes,workload_bytes:$workload_bytes,throughput_mbps:$throughput_mbps,probe:$probe}' \
        >> "$RESULTS_JSONL"
    printf 'Gate 8 performance: %s %d/%d cpu_ticks=%s pss=%sKB tun=%s uid_net=%s throughput=%sMbps\n' \
        "$scenario" "$repetition" "$REPETITIONS" "$cpu_delta" "$pss" "$tun_delta" "$uid_network_delta" "$throughput"
}

run_repeats() {
    local scenario="$1"
    local workload="$2"
    start_battery_window "$scenario"
    for ((repetition = 1; repetition <= REPETITIONS; repetition++)); do
        measure_once "$scenario" "$repetition" "$workload"
    done
    finish_raw_window "$scenario"
}

tap_text() {
    local target="$1"
    local scroll_if_missing="${2:-false}"
    local xml node bounds x1 y1 x2 y2 scroll_node scroll_bounds sx1 sy1 sx2 sy2 margin
    for _ in 1 2 3 4 5; do
        xml="$(adb_call exec-out uiautomator dump /dev/tty | tr -d '\r')"
        node="$(grep -o "<node[^>]*text=\"$target\"[^>]*>" <<<"$xml" | tail -n 1 || true)"
        bounds="$(sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' <<<"$node")"
        if [[ -n "$bounds" ]]; then
            read -r x1 y1 x2 y2 <<<"$bounds"
            adb_call shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))" >/dev/null
            sleep 1
            return 0
        fi
        if [[ "$scroll_if_missing" == true ]]; then
            scroll_node="$(grep -o '<node[^>]*scrollable="true"[^>]*>' <<<"$xml" | head -n 1 || true)"
            scroll_bounds="$(sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' <<<"$scroll_node")"
            if [[ -n "$scroll_bounds" ]]; then
                read -r sx1 sy1 sx2 sy2 <<<"$scroll_bounds"
                margin=$(((sy2 - sy1) / 6))
                adb_call shell input swipe "$(((sx1 + sx2) / 2))" "$((sy2 - margin))" \
                    "$(((sx1 + sx2) / 2))" "$((sy1 + margin))" 350 >/dev/null
            fi
        fi
        sleep 1
    done
    echo "UI text not found: $target" >&2
    return 1
}

probe "io.github.zapretkvn.android.debug.GATE8_PERF_CLEANUP" >/dev/null
adb_call shell input keyevent KEYCODE_HOME >/dev/null
adb_call shell input keyevent KEYCODE_SLEEP >/dev/null
run_repeats "vpn_off_idle" idle

prepare direct "" 0 none
adb_call shell input keyevent KEYCODE_SLEEP >/dev/null
run_repeats "vpn_idle" idle
run_repeats "unselected_direct" unselected
run_repeats "selected_direct" selected
stop_vpn

prepare proxy "" 0 none
run_repeats "selected_proxy" selected
stop_vpn

prepare direct "" 0 none
adb_call shell input keyevent KEYCODE_WAKEUP >/dev/null
adb_call shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb_call shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
sleep 1
run_repeats "ui_home_visible" idle
adb_call shell input keyevent KEYCODE_HOME >/dev/null
probe "io.github.zapretkvn.android.debug.GATE8_PERF_VISIBILITY" --ez home_visible false --ez diagnostics_visible false >/dev/null
run_repeats "ui_closed" idle
adb_call shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
tap_text "Настройки"
tap_text "Диагностика" true
diagnostics_status="$(probe "io.github.zapretkvn.android.debug.GATE8_PERF_STATUS")"
[[ ";$diagnostics_status;" == *";logClients=1;"* ]] || {
    echo "Diagnostics UI did not open its bounded log stream: $diagnostics_status" >&2
    exit 1
}
run_repeats "ui_diagnostics_visible" idle
adb_call shell input keyevent KEYCODE_HOME >/dev/null
sleep 1
stop_vpn

prepare direct "" 0 sequential
run_repeats "dns_sequential" dns
stop_vpn
prepare direct "" 0 parallel
run_repeats "dns_parallel" dns
stop_vpn

prepare direct "" 0 none
run_repeats "stack_current_mixed" selected
stop_vpn
prepare direct system 0 none
run_repeats "stack_system" selected
stop_vpn

prepare direct "" 0 none
run_repeats "mtu_default_9000" selected
stop_vpn
prepare direct "" 1500 none
run_repeats "mtu_1500" selected
stop_vpn

# sing-box 1.14 no longer exposes the GC-percent switch (Libbox.setMemoryLimit),
# so the gc10 experiment is gone; the default-GC run still owns the idle trace.
prepare direct "" 0 none
run_repeats "gc100_default" selected

mkdir -p "$RAW_ROOT/system-trace"
adb_call shell atrace -z -b 16384 -t 10 sched freq idle am wm gfx view binder_driver \
    > "$RAW_ROOT/system-trace/vpn-idle.atrace" 2> "$RAW_ROOT/system-trace/atrace.stderr" || true
finish_raw_window "gc100_default"
stop_vpn

jq -s --argjson threshold "$SIGNIFICANCE_PERCENT" \
    -f "$PROJECT_ROOT/scripts/gate8-performance-summary.jq" \
    "$RESULTS_JSONL" > "$RESULT_ROOT/summary.json"

jq -n \
    --arg generated_at "$timestamp" \
    --arg sdk "$sdk" \
    --arg device "$device" \
    --arg emulator "$is_emulator" \
    --arg core_revision "ff11f007ec798136a5de258f947a4f34011a37ea" \
    --argjson repetitions "$REPETITIONS" \
    --argjson idle_seconds "$IDLE_SECONDS" \
    --argjson traffic_bytes "$TRAFFIC_BYTES" \
    --argjson dns_queries "$DNS_QUERIES" \
    --argjson significance_percent "$SIGNIFICANCE_PERCENT" \
    --argjson clock_ticks_per_second "$clock_ticks" \
    --arg clock_ticks_source "$clock_ticks_source" \
    '{generated_at:$generated_at,android_api:($sdk|tonumber),device:$device,emulator:($emulator=="1"),measurement_apk:"debug",core_revision:$core_revision,repetitions:$repetitions,idle_seconds:$idle_seconds,traffic_bytes:$traffic_bytes,dns_queries:$dns_queries,clock_ticks_per_second:$clock_ticks_per_second,clock_ticks_source:$clock_ticks_source,significance_percent:$significance_percent,energy_valid:false}' \
    > "$RESULT_ROOT/metadata.json"

unselected_tun="$(jq -sr '[.[]|select(.scenario=="unselected_direct")|.tun_bytes]|sort|.[length/2|floor]' "$RESULTS_JSONL")"
selected_tun="$(jq -sr '[.[]|select(.scenario=="selected_direct")|.tun_bytes]|sort|.[length/2|floor]' "$RESULTS_JSONL")"
unselected_tun_max="$(jq -sr '[.[]|select(.scenario=="unselected_direct")|.tun_bytes]|max' "$RESULTS_JSONL")"
unselected_uid_max="$(jq -sr '[.[]|select(.scenario=="unselected_direct")|.uid_network_bytes]|max' "$RESULTS_JSONL")"
allowed_unselected=$((selected_tun * SIGNIFICANCE_PERCENT / 100))
if (( allowed_unselected < 65536 )); then allowed_unselected=65536; fi
if (( selected_tun <= 0 || unselected_tun > allowed_unselected )); then
    echo "Per-app performance boundary failed: unselected=$unselected_tun selected=$selected_tun limit=$allowed_unselected" >&2
    exit 1
fi
if (( unselected_tun_max > MAX_IDLE_TUN_GROWTH_BYTES || unselected_uid_max > MANAGED_IDLE_UID_LIMIT_BYTES )); then
    echo "Unselected traffic crossed the app/TUN boundary: tun_max=$unselected_tun_max uid_max=$unselected_uid_max" >&2
    exit 1
fi

idle_uid_total="$(jq -sr '[.[]|select(.scenario=="vpn_idle")|.uid_network_bytes]|add' "$RESULTS_JSONL")"
idle_uid_nonzero="$(jq -sr '[.[]|select(.scenario=="vpn_idle" and .uid_network_bytes>0)]|length' "$RESULTS_JSONL")"
idle_active_clients="$(jq -sr '[.[]|select(.scenario=="vpn_idle" and (.status_clients!=0 or .log_clients!=0))]|length' "$RESULTS_JSONL")"
if (( idle_uid_total > MANAGED_IDLE_UID_LIMIT_BYTES || idle_uid_nonzero > 1 || idle_active_clients != 0 )); then
    echo "Managed idle generated periodic work: uid_total=$idle_uid_total nonzero_windows=$idle_uid_nonzero active_clients=$idle_active_clients" >&2
    exit 1
fi

checksum_temp="$TEMP_ROOT/results-SHA256SUMS"
(
    cd "$RESULT_ROOT"
    find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum
) > "$checksum_temp"
mv "$checksum_temp" "$RESULT_ROOT/SHA256SUMS"

echo "Gate 8 performance AVD matrix passed; raw artifact: $RESULT_ROOT"
