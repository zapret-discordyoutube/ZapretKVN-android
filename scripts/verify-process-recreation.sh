#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
PACKAGE="io.github.zapretkvn.android.debug"
COMPONENT="$PACKAGE/io.github.zapretkvn.android.debug.Gate6ProcessProbeReceiver"
APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"

# shellcheck source=lib/adb-select.sh
source "$PROJECT_ROOT/scripts/lib/adb-select.sh"
adb_select_device || exit 1

cd "$PROJECT_ROOT"
./gradlew :app:assembleDebug >/dev/null
$ADB install -r "$APK" >/dev/null
$ADB shell appops set "$PACKAGE" ACTIVATE_VPN allow
if [[ "$($ADB shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ]]; then
    $ADB shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
fi

probe() {
    local action="$1"
    local output
    output="$($ADB shell am broadcast --receiver-foreground -a "$action" -n "$COMPONENT")"
    sed -n 's/.*data="\([^"]*\)".*/\1/p' <<<"$output" | tail -n 1
}

assert_field() {
    local status="$1"
    local expected="$2"
    if [[ ";$status;" != *";$expected;"* ]]; then
        echo "Missing '$expected' in probe status: $status" >&2
        exit 1
    fi
}

cleanup() {
    $ADB shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null 2>&1 || true
    probe "io.github.zapretkvn.android.debug.GATE6_CLEANUP" >/dev/null 2>&1 || true
    $ADB shell appops set "$PACKAGE" ACTIVATE_VPN default >/dev/null 2>&1 || true
}
trap cleanup EXIT

$ADB shell am force-stop "$PACKAGE"
$ADB shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
first="$(probe "io.github.zapretkvn.android.debug.GATE6_SETUP")"
assert_field "$first" "state=connected"
assert_field "$first" "sessions=1"
assert_field "$first" "core=1"
assert_field "$first" "tun=1"
assert_field "$first" "created=1"
first_pid="$($ADB shell pidof "$PACKAGE" | tr -d '\r')"
[[ -n "$first_pid" ]]

# A hard process stop tears down the real TUN/core. The recreated UI must report stopped,
# never a stale connected state, and all in-process resource counters must restart idle.
$ADB shell am force-stop "$PACKAGE"
$ADB shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
second_pid="$($ADB shell pidof "$PACKAGE" | tr -d '\r')"
[[ -n "$second_pid" && "$second_pid" != "$first_pid" ]]
after_death="$(probe "io.github.zapretkvn.android.debug.GATE6_STATUS")"
assert_field "$after_death" "state=stopped"
assert_field "$after_death" "sessions=0"
assert_field "$after_death" "core=0"
assert_field "$after_death" "tun=0"
assert_field "$after_death" "created=0"

second="$(probe "io.github.zapretkvn.android.debug.GATE6_SETUP")"
assert_field "$second" "state=connected"
assert_field "$second" "sessions=1"
assert_field "$second" "core=1"
assert_field "$second" "tun=1"
assert_field "$second" "created=1"

echo "Gate 6 hard process recreation passed: $after_death"
