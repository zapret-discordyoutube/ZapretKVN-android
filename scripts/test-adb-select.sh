#!/usr/bin/env bash
set -euo pipefail

# Offline tests for scripts/lib/adb-select.sh with a fake `adb`. No device, no
# network. Also checks that every phone verification script uses the helper and
# that the emulator-only scripts do not.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

cat > "$WORK_DIR/adb" <<'FAKE'
#!/usr/bin/env bash
# FAKE_DEVICES: lines "serial<TAB>state"; FAKE_HW: lines "serial=hardware id".
if [[ "$1" == "devices" ]]; then
    echo "List of devices attached"
    printf '%b' "${FAKE_DEVICES:-}"
    echo
    exit 0
fi
if [[ "$1" == "-s" ]]; then
    serial="$2"; shift 2
    if [[ "$*" == "shell getprop ro.serialno" ]]; then
        printf '%b' "${FAKE_HW:-}" | awk -F= -v s="$serial" '$1 == s { print $2 "\r" }'
    fi
    exit 0
fi
exit 0
FAKE
chmod +x "$WORK_DIR/adb"

IP='192.168.1.3:45245'
MDNS='adb-52041FDAS0012S-XpW63f._adb-tls-connect._tcp'

# run_select <devices> <hardware map> [ANDROID_SERIAL]; prints "rc|serial|stderr"
run_select() {
    local devices="$1" hw="$2" serial="${3-}" out err rc
    err="$WORK_DIR/err"
    set +e
    out="$(
        unset ANDROID_SERIAL
        [[ -z "$serial" ]] || export ANDROID_SERIAL="$serial"
        ADB="$WORK_DIR/adb" FAKE_DEVICES="$devices" FAKE_HW="$hw"
        export ADB FAKE_DEVICES FAKE_HW
        # shellcheck disable=SC1091
        source "$SCRIPT_DIR/lib/adb-select.sh"
        adb_select_device 2>"$err"
        rc=$?
        printf '%s|%s' "$rc" "${ANDROID_SERIAL-}"
    )"
    set -e
    printf '%s|%s' "$out" "$(tr '\n' ' ' < "$err")"
}

expect() {
    local name="$1" got="$2" want_rc="$3" want_serial="$4" want_err="${5-}"
    local rc serial err
    rc="${got%%|*}"; got="${got#*|}"
    serial="${got%%|*}"; err="${got#*|}"
    [[ "$rc" == "$want_rc" ]] || fail "$name: exit $rc, wanted $want_rc ($err)"
    [[ "$serial" == "$want_serial" ]] || fail "$name: serial '$serial', wanted '$want_serial'"
    [[ -z "$want_err" || "$err" == *"$want_err"* ]] || fail "$name: message '$err' lacks '$want_err'"
    echo "ok: $name"
}

HW_SAME="$IP=52041FDAS0012S\n$MDNS=52041FDAS0012S\n"

expect "one device" \
    "$(run_select "$IP\tdevice\n" "$IP=52041FDAS0012S\n")" 0 "$IP"
expect "duplicate pair (IP and mDNS): one phone, IP entry preferred" \
    "$(run_select "$MDNS\tdevice\n$IP\tdevice\n" "$HW_SAME")" 0 "$IP"
expect "duplicate pair, hardware id unavailable: mDNS name carries the serial" \
    "$(run_select "$IP\tdevice\n$MDNS\tdevice\n" "$IP=52041FDAS0012S\n")" 0 "$IP"
expect "mDNS entry only" \
    "$(run_select "$MDNS\tdevice\n" "$MDNS=52041FDAS0012S\n")" 0 "$MDNS"
expect "two different phones are refused" \
    "$(run_select "$IP\tdevice\nR58M123ABC\tdevice\n" "$IP=52041FDAS0012S\nR58M123ABC=R58M123ABC\n")" 1 "" "found 2 different devices"
expect "emulator plus phone are refused" \
    "$(run_select "$IP\tdevice\nemulator-5554\tdevice\n" "$IP=52041FDAS0012S\nemulator-5554=EMULATOR36X\n")" 1 "" "set ANDROID_SERIAL"
expect "no devices" \
    "$(run_select "" "")" 1 "" "found 0"
expect "offline entries do not count" \
    "$(run_select "$IP\toffline\n" "")" 1 "" "found 0"
expect "ANDROID_SERIAL picks one of two different phones" \
    "$(run_select "$IP\tdevice\nR58M123ABC\tdevice\n" "$IP=52041FDAS0012S\nR58M123ABC=R58M123ABC\n" "R58M123ABC")" 0 "R58M123ABC"
expect "ANDROID_SERIAL kept on the duplicate pair" \
    "$(run_select "$MDNS\tdevice\n$IP\tdevice\n" "$HW_SAME" "$MDNS")" 0 "$MDNS"
expect "ANDROID_SERIAL not listed" \
    "$(run_select "$IP\tdevice\n" "" "nope:1")" 1 "nope:1" "is not a ready adb device"
expect "ANDROID_SERIAL offline" \
    "$(run_select "$IP\toffline\n" "" "$IP")" 1 "$IP" "is not a ready adb device"

# Every phone script uses the helper and no longer counts device lines itself.
for script in verify-release-device verify-gate8-performance verify-gate8-stress \
    verify-process-recreation verify-same-key-upgrade; do
    grep -Fq 'scripts/lib/adb-select.sh' "$SCRIPT_DIR/$script.sh" || fail "$script.sh does not source adb-select.sh"
    grep -Fq 'adb_select_device' "$SCRIPT_DIR/$script.sh" || fail "$script.sh does not call adb_select_device"
    ! grep -Fq 'device_count' "$SCRIPT_DIR/$script.sh" || fail "$script.sh still counts device lines"
done
# The emulator-only scripts must stay independent of the PATH adb and of this helper.
for script in measure-startup-frames generate-baseline-profile; do
    ! grep -Fq 'adb-select.sh' "$SCRIPT_DIR/$script.sh" || fail "$script.sh must not use adb-select.sh"
done
echo "ok: scripts use the helper"
echo "test-adb-select: all passed"
