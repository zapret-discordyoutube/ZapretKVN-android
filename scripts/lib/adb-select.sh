#!/usr/bin/env bash
# Choose the one physical phone for the device verification scripts and pin every
# later `$ADB ...` call to it through ANDROID_SERIAL (adb honours it itself, so the
# call sites stay unchanged). Sourced by verify-release-device.sh,
# verify-gate8-performance.sh, verify-gate8-stress.sh, verify-process-recreation.sh
# and verify-same-key-upgrade.sh. Not used by the emulator-only scripts
# (measure-startup-frames.sh, generate-baseline-profile.sh): they bypass the PATH
# adb on purpose, so a personal phone is never touched.
#
# Why: through the panel's adb link (Claude SSH Web) the same phone is listed twice,
# as `192.168.1.3:45245` and as `adb-<serial>-<id>._adb-tls-connect._tcp`. Counting
# `device` lines then gave "found 2" and the scripts stopped.
#
# The caller sets ADB (default `adb`). Rules, in order:
#   1. ANDROID_SERIAL set: it must be listed in state `device`, otherwise fail.
#   2. One `device` line: use it.
#   3. Several lines that are one phone (same ro.serialno, or the same serial inside
#      the mDNS name): use one, preferring the IP:port entry over the mDNS name.
#   4. Several different phones, or none: fail with the old message wording.

# Serial of every entry in state `device`, one per line.
adb_ready_serials() {
    "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

# Hardware serial of one entry; falls back to the serial embedded in an mDNS name
# (`adb-<hardware>-<id>._adb-tls-connect._tcp`), and finally to the entry itself.
adb_hardware_id() {
    local entry="$1" hardware
    hardware="$("$ADB" -s "$entry" shell getprop ro.serialno </dev/null 2>/dev/null | tr -d '\r' || true)"
    if [[ -z "$hardware" && "$entry" == adb-*._adb-tls-connect._tcp* ]]; then
        hardware="${entry#adb-}"
        hardware="${hardware%%-*}"
    fi
    printf '%s\n' "${hardware:-$entry}"
}

adb_select_device() {
    local ready entry count hardware first_hardware="" different=0 chosen="" mdns=""
    ready="$(adb_ready_serials)"
    count="$(printf '%s' "$ready" | awk 'NF { n++ } END { print n+0 }')"

    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        if ! grep -Fxq -- "$ANDROID_SERIAL" <<<"$ready"; then
            echo "ANDROID_SERIAL=$ANDROID_SERIAL is not a ready adb device (found $count other)" >&2
            return 1
        fi
        export ANDROID_SERIAL
        return 0
    fi

    if [[ "$count" -eq 0 ]]; then
        echo "Expected exactly one ready adb device, found 0" >&2
        return 1
    fi

    while IFS= read -r entry; do
        [[ -n "$entry" ]] || continue
        hardware="$(adb_hardware_id "$entry")"
        if [[ -z "$first_hardware" ]]; then
            first_hardware="$hardware"
        elif [[ "$hardware" != "$first_hardware" ]]; then
            different=1
        fi
        if [[ "$entry" == adb-*._adb-tls-connect._tcp* ]]; then
            [[ -n "$mdns" ]] || mdns="$entry"
        elif [[ -z "$chosen" ]]; then
            chosen="$entry"
        fi
    done <<<"$ready"

    if [[ "$different" -ne 0 ]]; then
        echo "Expected exactly one ready adb device, found $count different devices; set ANDROID_SERIAL" >&2
        return 1
    fi
    ANDROID_SERIAL="${chosen:-$mdns}"
    export ANDROID_SERIAL
}
