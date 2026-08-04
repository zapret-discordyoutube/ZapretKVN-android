#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$PROJECT_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk}"
MAPPING="${2:-$PROJECT_ROOT/app/build/outputs/mapping/release/mapping.txt}"
EXPECTED_ABI="${3:-}"
REPORT_DIR="${ZAPRET_RC_REPORT_DIR:-$PROJECT_ROOT/build/release-candidate}"
SYMBOL_ZIP="$PROJECT_ROOT/core-build/output/native-debug-symbols.zip"
SYMBOL_METADATA="$PROJECT_ROOT/core-build/output/native-symbols-metadata.json"
TEMP_DIR="$(mktemp -d -t zapret-kvn-release-audit-XXXXXXXX)"

# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"
if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
    export ANDROID_HOME
fi
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
APKSIGNER="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"

cleanup() {
    rm -rf -- "$TEMP_DIR"
}
trap cleanup EXIT

for command in apkanalyzer jq python3 readelf sha256sum unzip; do
    command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done
[[ -x "$APKSIGNER" && -f "$APK" && -s "$MAPPING" && -s "$SYMBOL_ZIP" && -s "$SYMBOL_METADATA" ]]

apkanalyzer manifest print "$APK" > "$TEMP_DIR/manifest.xml"
python3 - "$TEMP_DIR/manifest.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot()
if root.get("package") != "io.github.zapretkvn.android":
    raise SystemExit("Release package name mismatch")
sdk = root.find("uses-sdk")
if sdk is None or sdk.get(android + "minSdkVersion") != "26" or sdk.get(android + "targetSdkVersion") != "36":
    raise SystemExit("Release SDK contract mismatch")

expected_permissions = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.CAMERA",
    "android.permission.QUERY_ALL_PACKAGES",
    "io.github.zapretkvn.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
permissions = {node.get(android + "name") for node in root.findall("uses-permission")}
if permissions != expected_permissions:
    raise SystemExit(f"Release permission allowlist mismatch: {sorted(permissions ^ expected_permissions)}")
declared = root.findall("permission")
if len(declared) != 1 or declared[0].get(android + "name") != "io.github.zapretkvn.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" or declared[0].get(android + "protectionLevel") != "0x2":
    raise SystemExit("AndroidX dynamic receiver permission is not signature-protected")

application = root.find("application")
if application is None:
    raise SystemExit("Release manifest has no application")
if application.get(android + "debuggable") not in (None, "false"):
    raise SystemExit("Release APK is debuggable")
if application.get(android + "allowBackup") != "false" or application.get(android + "fullBackupContent") != "false":
    raise SystemExit("Credentials must be excluded from Android backup")
if application.get(android + "usesCleartextTraffic") != "false":
    raise SystemExit("Release must explicitly reject platform cleartext traffic")
if application.get(android + "process") is not None:
    raise SystemExit("Release application declares a second process")

profileable = application.findall("profileable")
if len(profileable) != 1 or profileable[0].get(android + "shell") != "true":
    raise SystemExit("Release must be shell-profileable for local performance diagnostics")

components = []
for kind in ("activity", "activity-alias", "service", "provider", "receiver"):
    for node in application.findall(kind):
        name = node.get(android + "name")
        exported = node.get(android + "exported")
        if exported not in ("true", "false"):
            raise SystemExit(f"Component has implicit exported state: {kind} {name}")
        components.append((kind, name, exported, node.get(android + "permission")))

exported = {(kind, name, permission) for kind, name, value, permission in components if value == "true"}
expected_exported = {
    ("activity", "io.github.zapretkvn.android.MainActivity", None),
    (
        "service",
        "io.github.zapretkvn.android.vpn.ZapretQuickSettingsTileService",
        "android.permission.BIND_QUICK_SETTINGS_TILE",
    ),
    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver", "android.permission.DUMP"),
}
if exported != expected_exported:
    raise SystemExit(f"Unexpected exported components: {sorted(exported ^ expected_exported, key=str)}")

vpn = [item for item in components if item[0] == "service" and item[3] == "android.permission.BIND_VPN_SERVICE"]
if len(vpn) != 1 or vpn[0][1] != "io.github.zapretkvn.android.vpn.ZapretVpnService" or vpn[0][2] != "false":
    raise SystemExit("Release VPN service contract mismatch")
quick_settings_tiles = [
    item
    for item in components
    if item[0] == "service" and item[3] == "android.permission.BIND_QUICK_SETTINGS_TILE"
]
if (
    len(quick_settings_tiles) != 1
    or quick_settings_tiles[0][1]
    != "io.github.zapretkvn.android.vpn.ZapretQuickSettingsTileService"
    or quick_settings_tiles[0][2] != "true"
):
    raise SystemExit("Release Quick Settings tile service contract mismatch")
providers = [item for item in components if item[0] == "provider" and item[1] == "androidx.core.content.FileProvider"]
if len(providers) != 1 or providers[0][2] != "false":
    raise SystemExit("FileProvider must be unique and non-exported")
print(f"Merged release manifest verified: {len(components)} components, {len(exported)} exported.")
PY

if unzip -Z1 "$APK" | grep -Eiq '(^|/)(profiles|diagnostics|updates)(/|$)|\.(log|tmp|part)$'; then
    echo "Release APK contains runtime credential/temp material" >&2
    exit 1
fi
mapfile -t DEX_ENTRIES < <(unzip -Z1 "$APK" | grep -E '^classes([0-9]+)?\.dex$')
(( ${#DEX_ENTRIES[@]} > 0 ))
for dex_entry in "${DEX_ENTRIES[@]}"; do
    dex_file="$TEMP_DIR/${dex_entry//\//_}"
    unzip -p "$APK" "$dex_entry" > "$dex_file"
    if grep -Eaq 'super-secret|controller-secret|123e4567-e89b-12d3-a456-426614174000' "$dex_file"; then
        echo "Release DEX contains a security-test credential canary: $dex_entry" >&2
        exit 1
    fi
done

APK_SIZE="$(stat -c '%s' "$APK")"
MAPPING_SIZE="$(stat -c '%s' "$MAPPING")"
SYMBOL_SIZE="$(stat -c '%s' "$SYMBOL_ZIP")"
(( APK_SIZE > 0 && APK_SIZE <= 100663296 )) || { echo "Release APK exceeds 96 MiB: $APK_SIZE" >&2; exit 1; }
(( MAPPING_SIZE >= 1024 )) || { echo "R8 mapping is unexpectedly small" >&2; exit 1; }
grep -Fq 'io.github.zapretkvn.android' "$MAPPING"

mapfile -t NATIVE_ABIS < <(
    unzip -Z1 "$APK" \
        | sed -n 's#^lib/\([^/]*\)/.*\.so$#\1#p' \
        | sort -u
)
mapfile -t NATIVE_LIBS < <(unzip -Z1 "$APK" | grep -E '^lib/[^/]+/[^/]+\.so$' | sort)
if [[ "${#NATIVE_ABIS[@]}" -ne 1 ]]; then
    echo "Release APK must contain exactly one native ABI: ${NATIVE_ABIS[*]:-none}" >&2
    exit 1
fi
ABI="${NATIVE_ABIS[0]}"
if [[ -n "$EXPECTED_ABI" && "$ABI" != "$EXPECTED_ABI" ]]; then
    echo "Release APK ABI mismatch: expected $EXPECTED_ABI, got $ABI" >&2
    exit 1
fi
case "$ABI" in
    arm64-v8a|armeabi-v7a|x86_64) ;;
    *) echo "Unsupported release APK ABI: $ABI" >&2; exit 1 ;;
esac

unzip -p "$APK" "lib/$ABI/libbox.so" > "$TEMP_DIR/libbox.so"
[[ -s "$TEMP_DIR/libbox.so" ]]
readelf -S "$TEMP_DIR/libbox.so" | grep -Fq '.gopclntab'
for native_lib in "${NATIVE_LIBS[@]}"; do
    extracted="$TEMP_DIR/${native_lib//\//_}"
    unzip -p "$APK" "$native_lib" > "$extracted"
    if readelf -S "$extracted" | grep -Fq '.symtab'; then
        echo "Packaged production native library must be stripped: $native_lib" >&2
        exit 1
    fi
done
if [[ "$ABI" == arm64-v8a ]]; then
    unzip -tq "$SYMBOL_ZIP"
    unzip -p "$SYMBOL_ZIP" arm64-v8a/libbox.so > "$TEMP_DIR/libbox-symbols.so"
    readelf -S "$TEMP_DIR/libbox-symbols.so" | grep -Fq '.symtab'
    readelf -S "$TEMP_DIR/libbox-symbols.so" | grep -Fq '.debug_info'
    jq -e --arg commit "$CORE_COMMIT" --arg patch_sha256 "$CORE_PATCH_SHA256" '
      .core_commit == $commit and .core_patch_sha256 == $patch_sha256 and
      .abi == "arm64-v8a" and .loadable_sections_exact == true
    ' "$SYMBOL_METADATA" >/dev/null
fi

SIGNED=false
SIGNER_SHA256=""
if "$APKSIGNER" verify --verbose --print-certs "$APK" > "$TEMP_DIR/signature.txt" 2>&1; then
    SIGNED=true
    mapfile -t signer_digests < <(
        sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' "$TEMP_DIR/signature.txt"
    )
    if [[ "${#signer_digests[@]}" -ne 1 ]]; then
        echo "Release APK must have exactly one current signer" >&2
        exit 1
    fi
    SIGNER_SHA256="$(tr '[:upper:]' '[:lower:]' <<<"${signer_digests[0]}" | tr -d ':[:space:]')"
    [[ "$SIGNER_SHA256" =~ ^[0-9a-f]{64}$ ]] || {
        echo "Invalid APK signer SHA-256: $SIGNER_SHA256" >&2
        exit 1
    }
elif [[ "${ZAPRET_REQUIRE_SIGNED_RELEASE:-0}" == 1 ]]; then
    cat "$TEMP_DIR/signature.txt" >&2
    echo "A signed release candidate is required" >&2
    exit 1
fi

if [[ -n "${ZAPRET_EXPECTED_SIGNER_SHA256:-}" ]]; then
    expected_signer="$(tr '[:upper:]' '[:lower:]' <<<"$ZAPRET_EXPECTED_SIGNER_SHA256" | tr -d ':[:space:]')"
    [[ "$expected_signer" =~ ^[0-9a-f]{64}$ ]] || {
        echo "ZAPRET_EXPECTED_SIGNER_SHA256 must contain exactly 64 hex characters" >&2
        exit 1
    }
    if [[ "$SIGNED" != true || "$SIGNER_SHA256" != "$expected_signer" ]]; then
        echo "Release signer SHA-256 mismatch" >&2
        exit 1
    fi
fi

mkdir -p "$REPORT_DIR"
APK_SHA256="$(sha256sum "$APK" | awk '{print $1}')"
MAPPING_SHA256="$(sha256sum "$MAPPING" | awk '{print $1}')"
SYMBOL_SHA256="$(sha256sum "$SYMBOL_ZIP" | awk '{print $1}')"
jq -n \
    --arg apk "$APK" \
    --arg apk_sha256 "$APK_SHA256" \
    --argjson apk_size "$APK_SIZE" \
    --arg mapping_sha256 "$MAPPING_SHA256" \
    --argjson mapping_size "$MAPPING_SIZE" \
    --arg symbols_sha256 "$SYMBOL_SHA256" \
    --argjson symbols_size "$SYMBOL_SIZE" \
    --argjson signed "$SIGNED" \
    --arg signer_sha256 "$SIGNER_SHA256" \
    --arg core_commit "$CORE_COMMIT" \
    --arg core_patch_sha256 "$CORE_PATCH_SHA256" \
    --arg abi "$ABI" \
    '{apk:$apk,abi:$abi,apk_sha256:$apk_sha256,apk_size:$apk_size,r8_mapping_sha256:$mapping_sha256,r8_mapping_size:$mapping_size,native_symbols_sha256:$symbols_sha256,native_symbols_size:$symbols_size,signed:$signed,signer_sha256:$signer_sha256,core_commit:$core_commit,core_patch_sha256:$core_patch_sha256,debuggable:false,cleartext:false,manifest_allowlist:true,secret_canaries_absent:true}' \
    > "$REPORT_DIR/security-report-$ABI.json"

echo "Release candidate security audit passed: $REPORT_DIR/security-report-$ABI.json"
