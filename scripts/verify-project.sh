#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$PROJECT_ROOT/app/src/main/AndroidManifest.xml"

[[ -f "$PROJECT_ROOT/settings.gradle.kts" ]]
[[ -f "$PROJECT_ROOT/app/build.gradle.kts" ]]
[[ -f "$MANIFEST" ]]
[[ -f "$PROJECT_ROOT/docs/NOTICE" ]]
[[ -f "$PROJECT_ROOT/docs/THIRD_PARTY_NOTICES.md" ]]
[[ -f "$PROJECT_ROOT/gradle/verification-metadata.xml" ]]
[[ -f "$PROJECT_ROOT/app/src/main/res/raw/sing_box_extended_license.txt" ]]
[[ -f "$PROJECT_ROOT/app/src/main/res/raw/sing_geoip_license.txt" ]]
[[ -f "$PROJECT_ROOT/audit/rule_set_performance_test.go" ]]
[[ -f "$PROJECT_ROOT/scripts/core-patchset.sh" ]]
[[ -x "$PROJECT_ROOT/scripts/publish-local-stable.sh" ]]
[[ -x "$PROJECT_ROOT/scripts/publish-github-stable.sh" ]]
[[ -x "$PROJECT_ROOT/scripts/test-publish-github-stable.sh" ]]
[[ -x "$PROJECT_ROOT/scripts/verify-release-bundle.sh" ]]
[[ -f "$PROJECT_ROOT/release.properties" ]]
source "$PROJECT_ROOT/core.properties"
source "$PROJECT_ROOT/scripts/core-patchset.sh"
verify_core_patchset "$PROJECT_ROOT"
[[ -f "$PROJECT_ROOT/scripts/gate8-performance-summary.jq" ]]

RELEASE_WORKFLOW="$PROJECT_ROOT/.github/workflows/release.yml"
grep -Fq 'workflow_dispatch:' "$RELEASE_WORKFLOW"
grep -Fq 'scripts/verify-gate8-performance.sh' "$RELEASE_WORKFLOW"
grep -Fq 'scripts/verify-release-bundle.sh' "$RELEASE_WORKFLOW"
grep -Fq 'Restore Android emulator packages' "$RELEASE_WORKFLOW"
grep -Fq 'Install or verify Android emulator packages' "$RELEASE_WORKFLOW"
grep -Fq 'android-emulator-${{ runner.os }}' "$RELEASE_WORKFLOW"
grep -Fq 'android-emulator-packages.outputs.cache-hit' "$RELEASE_WORKFLOW"
grep -Fq 'permissions:' "$RELEASE_WORKFLOW"
grep -Fq 'contents: read' "$RELEASE_WORKFLOW"
if grep -Fq 'gh release create' "$RELEASE_WORKFLOW" ||
    grep -Fq 'ANDROID_SIGNING_KEYSTORE_BASE64' "$RELEASE_WORKFLOW" ||
    grep -Fq 'secrets.ANDROID_SIGNING' "$RELEASE_WORKFLOW"; then
    echo "Background release verification must not publish or access the production key" >&2
    exit 1
fi
if grep -Eq '^[[:space:]]+push:' "$RELEASE_WORKFLOW"; then
    echo "Stable verification must be dispatched by the local publisher" >&2
    exit 1
fi
LOCAL_PUBLISHER="$PROJECT_ROOT/scripts/publish-local-stable.sh"
grep -Fq -- '--final-gate-approved' "$LOCAL_PUBLISHER"
grep -Fq 'scripts/publish-github-stable.sh' "$LOCAL_PUBLISHER"
grep -Fq 'gh workflow run release.yml' "$LOCAL_PUBLISHER"
grep -Fq 'scripts/ci-build.sh' "$LOCAL_PUBLISHER"
grep -Fq 'scripts/verify-release-bundle.sh' "$LOCAL_PUBLISHER"
GITHUB_PUBLISHER="$PROJECT_ROOT/scripts/publish-github-stable.sh"
grep -Fq 'gh release create' "$GITHUB_PUBLISHER"
grep -Fq -- '--draft' "$GITHUB_PUBLISHER"
grep -Fq 'gh release upload' "$GITHUB_PUBLISHER"
grep -Fq 'timeout --foreground' "$GITHUB_PUBLISHER"
# shellcheck disable=SC2016
grep -Fq '.digest == $digest' "$GITHUB_PUBLISHER"
"$PROJECT_ROOT/scripts/test-publish-github-stable.sh"
source "$PROJECT_ROOT/release.properties"
if [[ ! "$RELEASE_SIGNER_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid public production signing fingerprint" >&2
    exit 1
fi

cmp -s "$PROJECT_ROOT/docs/LICENSE" "$PROJECT_ROOT/app/src/main/res/raw/license_gpl_3.txt"
cmp -s "$PROJECT_ROOT/docs/NOTICE" "$PROJECT_ROOT/app/src/main/res/raw/notice.txt"
cmp -s "$PROJECT_ROOT/docs/THIRD_PARTY_NOTICES.md" "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt"

WRAPPER_PROPERTIES="$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties"
grep -Fqx 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.4.1-bin.zip' "$WRAPPER_PROPERTIES"
grep -Fqx 'distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb' "$WRAPPER_PROPERTIES"
printf '%s  %s\n' \
    '55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c' \
    "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.jar" \
    | sha256sum -c - >/dev/null

INVALID_ACTIONS="$(
    sed -n 's/^[[:space:]]*uses:[[:space:]]*//p' "$PROJECT_ROOT"/.github/workflows/*.yml \
        | grep -Ev '@[0-9a-f]{40}([[:space:]]+#.*)?$' || true
)"
if [[ -n "$INVALID_ACTIONS" ]]; then
    echo "GitHub Actions must be pinned to full commit SHAs:" >&2
    printf '%s\n' "$INVALID_ACTIONS" >&2
    exit 1
fi

mapfile -t ANDROID_MODULES < <(
    find "$PROJECT_ROOT" -mindepth 2 -maxdepth 2 -name build.gradle.kts -printf '%h\n' \
        | sed "s|$PROJECT_ROOT/||" \
        | sort
)
EXPECTED_ANDROID_MODULES=(app app-updater network-bootstrap wireguard-import)
if [[ "${ANDROID_MODULES[*]}" != "${EXPECTED_ANDROID_MODULES[*]}" ]]; then
    echo "Unexpected Android module set: ${ANDROID_MODULES[*]:-none}" >&2
    exit 1
fi
if [[ "$(grep -l 'com.android.application' "$PROJECT_ROOT"/*/build.gradle.kts | wc -l)" -ne 1 ]] ||
    ! grep -Fq 'com.android.application' "$PROJECT_ROOT/app/build.gradle.kts"; then
    echo "Exactly app must be the Android application module" >&2
    exit 1
fi

if grep -Eq 'WAKE_LOCK|REQUEST_IGNORE_BATTERY_OPTIMIZATIONS|android:process=' "$MANIFEST"; then
    echo "Manifest contains a forbidden permission/process" >&2
    exit 1
fi

if grep -R -E 'import android\.util\.Log|\bLog\.[vdiewtf]\(|printStackTrace\(|System\.(out|err)' \
    "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/app-updater/src/main/java"; then
    echo "Production source must not write credentials or runtime details to Logcat/stdout" >&2
    exit 1
fi
if grep -R -E 'addPrimaryClipChangedListener|setPrimaryClip\(' \
    "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/app-updater/src/main/java"; then
    echo "Application clipboard history/listeners are forbidden; clipboard import is read-on-action only" >&2
    exit 1
fi
if grep -R -E 'android\.os\.PowerManager|android\.app\.AlarmManager|android\.app\.job\.JobScheduler|androidx\.work|ScheduledExecutorService|scheduleAtFixedRate|scheduleWithFixedDelay' \
    "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/app-updater/src/main/java"; then
    echo "Production source contains an app-owned wake/alarm/job/periodic scheduler" >&2
    exit 1
fi
if grep -R -F 'CommandConnections' "$PROJECT_ROOT/app/src/main/java"; then
    echo "Production runtime must not subscribe to per-connection polling" >&2
    exit 1
fi

python3 - "$PROJECT_ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
delays = []
for source in (
    root / "app/src/main/java",
    root / "app-updater/src/main/java",
    root / "network-bootstrap/src/main/java",
):
    for path in source.rglob("*.kt"):
        count = path.read_text().count("delay(")
        if count:
            delays.append((str(path.relative_to(root)), count))
delays.sort()
expected = [
    ("app/src/main/java/io/github/zapretkvn/android/ui/HomeScreen.kt", 1),
    ("app/src/main/java/io/github/zapretkvn/android/vpn/HealthProbeRace.kt", 1),
    ("app/src/main/java/io/github/zapretkvn/android/vpn/ZapretVpnService.kt", 1),
    ("network-bootstrap/src/main/java/io/github/zapretkvn/networkbootstrap/UnderlyingNetworkMonitor.kt", 1),
]
if delays != expected:
    raise SystemExit(f"Unexpected production timer/retry surface: {delays!r}")
print("Production delay calls are limited to visible session time, probe stagger, network debounce and bootstrap settle.")
PY

python3 - "$MANIFEST" "$PROJECT_ROOT/app/src/main/res/xml/diagnostic_file_paths.xml" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

android = "{http://schemas.android.com/apk/res/android}"
manifest_path, paths_path = map(Path, sys.argv[1:])
root = ET.parse(manifest_path).getroot()
application = root.find("application")
if application is None:
    raise SystemExit("Manifest has no application")
if application.get(android + "usesCleartextTraffic") != "false":
    raise SystemExit("Application must explicitly reject platform cleartext traffic")
source_permissions = {node.get(android + "name") for node in root.findall("uses-permission")}
expected_source_permissions = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.CAMERA",
    "android.permission.QUERY_ALL_PACKAGES",
}
if source_permissions != expected_source_permissions:
    raise SystemExit(
        f"Source manifest permission allowlist mismatch: "
        f"{sorted(source_permissions ^ expected_source_permissions)}"
    )
queries = root.find("queries")
if queries is None:
    raise SystemExit("Manifest must declare launcher package visibility fallback")
query_intents = set()
for intent in queries.findall("intent"):
    actions = tuple(sorted(
        node.get(android + "name") for node in intent.findall("action")
    ))
    categories = tuple(sorted(
        node.get(android + "name") for node in intent.findall("category")
    ))
    query_intents.add((actions, categories))
expected_query_intents = {
    (("android.intent.action.MAIN",), ("android.intent.category.LAUNCHER",)),
    (("android.intent.action.MAIN",), ("android.intent.category.LEANBACK_LAUNCHER",)),
}
if query_intents != expected_query_intents:
    raise SystemExit(
        f"Launcher package visibility queries mismatch: "
        f"{sorted(query_intents ^ expected_query_intents)}"
    )
profileable = application.findall("profileable")
if len(profileable) != 1 or profileable[0].get(android + "shell") != "true":
    raise SystemExit("Release-gate profiling requires exactly one shell-profileable declaration")

vpn_services = [
    node for node in application.findall("service")
    if node.get(android + "permission") == "android.permission.BIND_VPN_SERVICE"
]
if len(vpn_services) != 1:
    raise SystemExit(f"Expected exactly one VPN service, found {len(vpn_services)}")
vpn_service = vpn_services[0]
if vpn_service.get(android + "exported") != "false":
    raise SystemExit("VPN service must not be exported")
always_on_metadata = [
    node for node in vpn_service.findall("meta-data")
    if node.get(android + "name") == "android.net.VpnService.SUPPORTS_ALWAYS_ON"
]
if len(always_on_metadata) != 1 or always_on_metadata[0].get(android + "value") != "false":
    raise SystemExit("VPN service must explicitly opt out of Always-on")

quick_settings_tiles = [
    node for node in application.findall("service")
    if node.get(android + "permission") == "android.permission.BIND_QUICK_SETTINGS_TILE"
]
if len(quick_settings_tiles) != 1:
    raise SystemExit(
        f"Expected exactly one Quick Settings tile service, found {len(quick_settings_tiles)}"
    )
quick_settings_tile = quick_settings_tiles[0]
if (
    quick_settings_tile.get(android + "name") != ".vpn.ZapretQuickSettingsTileService"
    or quick_settings_tile.get(android + "exported") != "true"
):
    raise SystemExit("Quick Settings tile must be the single exported system-bound tile service")
tile_actions = {
    action.get(android + "name")
    for intent_filter in quick_settings_tile.findall("intent-filter")
    for action in intent_filter.findall("action")
}
if tile_actions != {"android.service.quicksettings.action.QS_TILE"}:
    raise SystemExit(f"Quick Settings tile intent actions differ: {sorted(tile_actions)}")

providers = [
    node for node in application.findall("provider")
    if node.get(android + "name") == "androidx.core.content.FileProvider"
]
if len(providers) != 1:
    raise SystemExit(f"Expected one FileProvider, found {len(providers)}")
provider = providers[0]
expected = {
    android + "authorities": "${applicationId}.fileprovider",
    android + "exported": "false",
    android + "grantUriPermissions": "true",
}
for attribute, value in expected.items():
    if provider.get(attribute) != value:
        raise SystemExit(f"Unsafe FileProvider attribute {attribute}: {provider.get(attribute)!r}")
metadata = provider.findall("meta-data")
if len(metadata) != 1 or metadata[0].get(android + "resource") != "@xml/diagnostic_file_paths":
    raise SystemExit("FileProvider must reference only diagnostic_file_paths")

paths = ET.parse(paths_path).getroot()
children = list(paths)
entries = {(child.tag, child.get("name"), child.get("path")) for child in children}
expected_paths = {
    ("cache-path", "diagnostics", "diagnostics/"),
    ("cache-path", "updates", "updates/"),
}
if entries != expected_paths:
    raise SystemExit(f"FileProvider paths differ from bounded cache paths: {entries!r}")
PY

python3 - "$PROJECT_ROOT" <<'PY'
from pathlib import Path
import hashlib
import re
import sys

root = Path(sys.argv[1])
docs_root = root / "docs"
docs = [docs_root / name for name in (
    "ARCHITECTURE.md",
    "DNS_ARCHITECTURE.md",
    "ROUTING_ARCHITECTURE.md",
    "IMPORT_FORMATS.md",
    "IMPLEMENTATION_PLAN.md",
    "README.md",
    "LICENSING.md",
    "SIGNING.md",
    "GATE8_RESULTS.md",
    "THIRD_PARTY_NOTICES.md",
)]
all_text = "\n".join(path.read_text() for path in docs)
fixture_root = root / "testdata"
manifest = {}
for line in (fixture_root / "SHA256SUMS").read_text().splitlines():
    digest, relative = line.split(maxsplit=1)
    manifest[relative] = digest
fixtures = sorted(fixture_root.rglob("*.json"))
fixture_names = {str(path.relative_to(fixture_root)) for path in fixtures}
if fixture_names != set(manifest):
    raise SystemExit("Fixture set differs from testdata/SHA256SUMS")
for fixture in fixtures:
    digest = hashlib.sha256(fixture.read_bytes()).hexdigest()
    relative = str(fixture.relative_to(fixture_root))
    if manifest[relative] != digest:
        raise SystemExit(f"Fixture hash mismatch: {fixture}: {digest}")
    if digest not in all_text:
        raise SystemExit(f"Fixture hash is not documented: {fixture}: {digest}")
for doc in docs:
    text = doc.read_text()
    if len(re.findall(r"^```", text, re.MULTILINE)) % 2:
        raise SystemExit(f"Unbalanced Markdown fences: {doc}")
    for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", text):
        if "://" in target or target.startswith("#"):
            continue
        local = target.split("#", 1)[0]
        if local and not (doc.parent / local).exists():
            raise SystemExit(f"Broken local link in {doc}: {target}")
print("Markdown, local links and fixture hashes are valid.")
PY

python3 - "$PROJECT_ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
surfaces = {
    "README": (root / "docs/README.md").read_text(),
    "UI": (root / "app/src/main/java/io/github/zapretkvn/android/ui/SettingsScreen.kt").read_text(),
    "release notes generator": (root / "scripts/create-release-bundle.sh").read_text(),
}
required = (
    "Известные ограничения MVP",
    "arm64-v8a",
    "Always-on/Lockdown",
    "shared UID",
    "DoH, DoT",
    "FakeIP",
    "Domain-only block",
    "Clash YAML",
    "Hysteria v1",
    "silent install",
    "plaintext DNS fallback",
)
for surface, text in surfaces.items():
    missing = [value for value in required if value not in text]
    if missing:
        raise SystemExit(f"{surface} omits known limitations: {missing!r}")
print("Known limitations are synchronized across UI, README and release notes.")
PY

python3 - "$PROJECT_ROOT" <<'PY'
from pathlib import Path
import hashlib
import json
import sys

root = Path(sys.argv[1])
asset_root = root / "app/src/main/assets/rule-sets"
manifest = json.loads((asset_root / "manifest.json").read_text())
if manifest.get("generated_with_core") != "ff11f007ec798136a5de258f947a4f34011a37ea":
    raise SystemExit("Rule-set manifest core revision mismatch")
expected = {"zapret-ru-domains", "zapret-ru-ip"}
entries = manifest.get("sets", [])
if {entry["tag"] for entry in entries} != expected:
    raise SystemExit("Unexpected packaged rule-set tag set")
for entry in entries:
    path = asset_root / entry["file"]
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != entry["sha256"]:
        raise SystemExit(f"Rule-set asset hash mismatch: {path}: {digest}")
print("Packaged rule-set manifest and hashes are valid.")
PY

PERFORMANCE_POLICY="$PROJECT_ROOT/scripts/gate8-performance-summary.jq"
POLICY_RESULT="$(
    printf '%s\n' \
        '{"scenario":"stack_current_mixed","cpu_ticks":100,"elapsed_nanos":1000000000,"pss_kb":100,"rss_kb":100,"tun_bytes":100,"throughput_mbps":100}' \
        '{"scenario":"stack_system","cpu_ticks":104,"elapsed_nanos":1040000000,"pss_kb":104,"rss_kb":104,"tun_bytes":104,"throughput_mbps":104}' \
        '{"scenario":"mtu_default_9000","cpu_ticks":100,"elapsed_nanos":1000000000,"pss_kb":100,"rss_kb":100,"tun_bytes":100,"throughput_mbps":100}' \
        '{"scenario":"mtu_1500","cpu_ticks":106,"elapsed_nanos":1060000000,"pss_kb":106,"rss_kb":106,"tun_bytes":106,"throughput_mbps":106}' \
        | jq -s --argjson threshold 5 -f "$PERFORMANCE_POLICY"
)"
BELOW_THRESHOLD_DECISION="$(
    jq -r '.comparisons[] | select(.name == "mixed_vs_system") | .decision' <<<"$POLICY_RESULT"
)"
ABOVE_THRESHOLD_DECISION="$(
    jq -r '.comparisons[] | select(.name == "mtu_default_vs_1500") | .decision' <<<"$POLICY_RESULT"
)"
if [[ "$BELOW_THRESHOLD_DECISION" != "NO_CHANGE_BELOW_5_PERCENT_OR_NO_SIGNAL" ]]; then
    echo "Performance policy accepted a sub-5% change" >&2
    exit 1
fi
if [[ "$ABOVE_THRESHOLD_DECISION" != "PHYSICAL_CONFIRMATION_REQUIRED" ]]; then
    echo "Performance policy ignored a >=5% change" >&2
    exit 1
fi
echo "Performance significance policy verified."

echo "Project structure verified."
