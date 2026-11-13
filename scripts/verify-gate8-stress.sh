#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
CONNECT_CYCLES="${GATE8_CONNECT_CYCLES:-100}"
NETWORK_TRANSITIONS="${GATE8_NETWORK_TRANSITIONS:-50}"
PACKAGE="io.github.zapretkvn.android.debug"
COMPONENT="$PACKAGE/io.github.zapretkvn.android.debug.Gate8StressProbeReceiver"
APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [[ ! "$CONNECT_CYCLES" =~ ^[1-9][0-9]*$ || ! "$NETWORK_TRANSITIONS" =~ ^[1-9][0-9]*$ ]]; then
    echo "Gate 8 cycle counts must be positive integers" >&2
    exit 1
fi

# shellcheck source=lib/adb-select.sh
source "$PROJECT_ROOT/scripts/lib/adb-select.sh"
adb_select_device || exit 1
if [[ "$($ADB shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
    echo "Automated Wi-Fi/cellular switching is restricted to a disposable AVD" >&2
    exit 1
fi

cd "$PROJECT_ROOT"
./gradlew :app:assembleDebug >/dev/null
$ADB install -r "$APK" >/dev/null
$ADB shell appops set "$PACKAGE" ACTIVATE_VPN allow
if [[ "$($ADB shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ]]; then
    $ADB shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
fi
$ADB shell svc data enable
$ADB shell su 0 svc wifi enable
$ADB shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null

probe() {
    local action="$1"
    local output result
    output="$($ADB shell am broadcast --receiver-foreground -a "$action" -n "$COMPONENT")"
    result="$(sed -n 's/.*data="\([^"]*\)".*/\1/p' <<<"$output" | tail -n 1)"
    if [[ -z "$result" || "$result" == error=* ]]; then
        echo "Gate 8 probe failed for $action: ${result:-$output}" >&2
        exit 1
    fi
    printf '%s\n' "$result"
}

field() {
    local status="$1"
    local name="$2"
    tr ';' '\n' <<<"$status" | sed -n "s/^$name=//p" | tail -n 1
}

assert_idle() {
    local status="$1"
    for expected in state=stopped sessions=0 core=0 tun=0 callbacks=0; do
        if [[ ";$status;" != *";$expected;"* ]]; then
            echo "Expected idle '$expected': $status" >&2
            exit 1
        fi
    done
}

cleanup() {
    $ADB shell svc data enable >/dev/null 2>&1 || true
    $ADB shell su 0 svc wifi enable >/dev/null 2>&1 || true
    probe "io.github.zapretkvn.android.debug.GATE8_CLEANUP" >/dev/null 2>&1 || true
    $ADB shell appops set "$PACKAGE" ACTIVATE_VPN default >/dev/null 2>&1 || true
}
trap cleanup EXIT

prepared="$(probe "io.github.zapretkvn.android.debug.GATE8_PREPARE")"
assert_idle "$prepared"
initial_created="$(field "$prepared" created)"
baseline_fds=""
baseline_threads=""

for ((cycle = 1; cycle <= CONNECT_CYCLES; cycle++)); do
    connected="$(probe "io.github.zapretkvn.android.debug.GATE8_CONNECT")"
    if [[ ";$connected;" != *";state=connected;"* || ";$connected;" != *";tun=1;"* ]]; then
        echo "Connect cycle $cycle failed: $connected" >&2
        exit 1
    fi
    stopped="$(probe "io.github.zapretkvn.android.debug.GATE8_STOP")"
    assert_idle "$stopped"
    if [[ "$cycle" -eq 10 || ( "$CONNECT_CYCLES" -lt 10 && "$cycle" -eq "$CONNECT_CYCLES" ) ]]; then
        baseline_fds="$(field "$stopped" fds)"
        baseline_threads="$(field "$stopped" threads)"
    fi
    if (( cycle % 10 == 0 || cycle == CONNECT_CYCLES )); then
        echo "Gate 8 connect/stop: $cycle/$CONNECT_CYCLES"
    fi
done

after_cycles="$(probe "io.github.zapretkvn.android.debug.GATE8_STATUS")"
created_after_cycles="$(field "$after_cycles" created)"
if (( created_after_cycles - initial_created != CONNECT_CYCLES )); then
    echo "Expected $CONNECT_CYCLES core creations, got $((created_after_cycles - initial_created)): $after_cycles" >&2
    exit 1
fi

connected="$(probe "io.github.zapretkvn.android.debug.GATE8_CONNECT")"
previous_created="$(field "$connected" created)"
for ((transition = 1; transition <= NETWORK_TRANSITIONS; transition++)); do
    probe "io.github.zapretkvn.android.debug.GATE8_ARM_RESTART" >/dev/null
    if (( transition % 2 == 1 )); then
        target_transport="cellular"
        $ADB shell su 0 svc wifi disable
    else
        target_transport="wifi"
        $ADB shell su 0 svc wifi enable
    fi

    deadline=$((SECONDS + 40))
    transitioned=""
    while (( SECONDS < deadline )); do
        candidate="$(probe "io.github.zapretkvn.android.debug.GATE8_STATUS")"
        candidate_created="$(field "$candidate" created)"
        if [[ ";$candidate;" == *";state=connected;"* &&
            "$(field "$candidate" transport)" == "$target_transport" &&
            "$candidate_created" -eq $((previous_created + 1))
        ]]; then
            transitioned="$candidate"
            break
        fi
        if (( candidate_created > previous_created + 1 )); then
            echo "Transition $transition caused duplicate core restarts: $candidate" >&2
            exit 1
        fi
        sleep 0.25
    done
    if [[ -z "$transitioned" ]]; then
        echo "Transition $transition to $target_transport timed out" >&2
        exit 1
    fi
    previous_created="$(field "$transitioned" created)"
    if (( transition % 10 == 0 || transition == NETWORK_TRANSITIONS )); then
        echo "Gate 8 Wi-Fi/cellular transitions: $transition/$NETWORK_TRANSITIONS"
    fi
done

final="$(probe "io.github.zapretkvn.android.debug.GATE8_STOP")"
assert_idle "$final"
final_fds="$(field "$final" fds)"
final_threads="$(field "$final" threads)"
if (( final_fds > baseline_fds + 2 )); then
    echo "File descriptors grew from $baseline_fds to $final_fds: $final" >&2
    exit 1
fi
if (( final_threads > baseline_threads + 2 )); then
    echo "Threads grew from $baseline_threads to $final_threads: $final" >&2
    exit 1
fi

echo "Gate 8 stress passed: connect/stop=$CONNECT_CYCLES, transitions=$NETWORK_TRANSITIONS, $final"
