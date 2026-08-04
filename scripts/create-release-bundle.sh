#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:?release tag is required}"
SOURCE_DIR="${2:?directory containing the signed release APK matrix is required}"
OUTPUT_DIR="${3:-$PROJECT_ROOT/release-out}"
RELEASE_ABIS=(arm64-v8a armeabi-v7a x86_64)

# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"
while IFS='=' read -r name value; do
    case "$name" in
        ZAPRET_VERSION_NAME|ZAPRET_VERSION_CODE|ZAPRET_PRERELEASE|ZAPRET_RELEASE_CHANNEL)
            printf -v "$name" '%s' "$value"
            ;;
        *)
            echo "Unexpected release variable: $name" >&2
            exit 1
            ;;
    esac
done < <("$PROJECT_ROOT/scripts/derive-release-version.sh" "$TAG")

if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
    export ANDROID_HOME
fi
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
AAPT2="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/aapt2"
APKSIGNER="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
for command in jq sha256sum unzip; do
    command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done
[[ -x "$AAPT2" && -x "$APKSIGNER" && -d "$SOURCE_DIR" ]]

if [[ -d "$OUTPUT_DIR" && -n "$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "Release output directory must be empty: $OUTPUT_DIR" >&2
    exit 1
fi
mkdir -p "$OUTPUT_DIR"

ARTIFACTS=()
SIGNER_SHA256=""
ARM64_APK_NAME=""
ARM64_APK_SHA256=""
ARM64_APK_SIZE=""

for abi in "${RELEASE_ABIS[@]}"; do
    source_apk="$SOURCE_DIR/app-$abi-release.apk"
    [[ -f "$source_apk" ]]

    mapfile -t native_abis < <(
        unzip -Z1 "$source_apk" \
            | sed -n 's#^lib/\([^/]*\)/.*\.so$#\1#p' \
            | sort -u
    )
    if [[ "${#native_abis[@]}" -ne 1 || "${native_abis[0]}" != "$abi" ]]; then
        echo "Unexpected native ABI set in $source_apk: ${native_abis[*]:-none}" >&2
        exit 1
    fi
    unzip -Z1 "$source_apk" | grep -Fx "lib/$abi/libbox.so" >/dev/null

    signature_info="$($APKSIGNER verify --verbose --print-certs "$source_apk")"
    mapfile -t signer_digests < <(
        sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' <<<"$signature_info"
    )
    [[ "${#signer_digests[@]}" -eq 1 ]]
    signer="$(tr '[:upper:]' '[:lower:]' <<<"${signer_digests[0]}" | tr -d ':[:space:]')"
    [[ "$signer" =~ ^[0-9a-f]{64}$ ]]
    if [[ -z "$SIGNER_SHA256" ]]; then
        SIGNER_SHA256="$signer"
    elif [[ "$signer" != "$SIGNER_SHA256" ]]; then
        echo "Release APKs are not signed by the same certificate" >&2
        exit 1
    fi

    badging="$($AAPT2 dump badging "$source_apk" | sed -n '1p')"
    [[ "$badging" == *"name='io.github.zapretkvn.android'"* ]]
    [[ "$badging" == *"versionCode='$ZAPRET_VERSION_CODE'"* ]]
    [[ "$badging" == *"versionName='$ZAPRET_VERSION_NAME'"* ]]

    apk_name="Zapret-KVN-$TAG-$abi.apk"
    cp "$source_apk" "$OUTPUT_DIR/$apk_name"
    apk_sha256="$(sha256sum "$OUTPUT_DIR/$apk_name" | awk '{print $1}')"
    apk_size="$(stat -c '%s' "$OUTPUT_DIR/$apk_name")"
    printf '%s  %s\n' "$apk_sha256" "$apk_name" > "$OUTPUT_DIR/$apk_name.sha256"
    ARTIFACTS+=("$(
        jq -cn \
            --arg abi "$abi" \
            --arg apk_file "$apk_name" \
            --arg apk_sha256 "$apk_sha256" \
            --argjson apk_size "$apk_size" \
            '{abi:$abi,apk_file:$apk_file,apk_sha256:$apk_sha256,apk_size:$apk_size}'
    )")

    if [[ "$abi" == arm64-v8a ]]; then
        ARM64_APK_NAME="$apk_name"
        ARM64_APK_SHA256="$apk_sha256"
        ARM64_APK_SIZE="$apk_size"
    fi
done

if [[ -n "${ZAPRET_EXPECTED_SIGNER_SHA256:-}" ]]; then
    EXPECTED_SIGNER="$(tr '[:upper:]' '[:lower:]' <<<"$ZAPRET_EXPECTED_SIGNER_SHA256" | tr -d ':[:space:]')"
    [[ "$EXPECTED_SIGNER" =~ ^[0-9a-f]{64}$ && "$SIGNER_SHA256" == "$EXPECTED_SIGNER" ]]
fi

# Schema 2 is used by architecture-aware clients.
printf '%s\n' "${ARTIFACTS[@]}" | jq -s \
    --arg version_name "$ZAPRET_VERSION_NAME" \
    --argjson version_code "$ZAPRET_VERSION_CODE" \
    --arg application_id "io.github.zapretkvn.android" \
    --arg core_tag "$CORE_TAG" \
    --arg core_commit "$CORE_COMMIT" \
    --arg core_patch_sha256 "$CORE_PATCH_SHA256" \
    --arg signer_sha256 "$SIGNER_SHA256" \
    '{schema:2,version_name:$version_name,version_code:$version_code,application_id:$application_id,core_tag:$core_tag,core_commit:$core_commit,core_patch_sha256:$core_patch_sha256,signer_sha256:$signer_sha256,artifacts:.}' \
    > "$OUTPUT_DIR/release-metadata-v2.json"

# Keep the schema-1 arm64 manifest so already-installed arm64 versions can cross
# the metadata transition through their existing updater.
jq -n \
    --arg version_name "$ZAPRET_VERSION_NAME" \
    --argjson version_code "$ZAPRET_VERSION_CODE" \
    --arg application_id "io.github.zapretkvn.android" \
    --arg core_tag "$CORE_TAG" \
    --arg core_commit "$CORE_COMMIT" \
    --arg core_patch_sha256 "$CORE_PATCH_SHA256" \
    --arg signer_sha256 "$SIGNER_SHA256" \
    --arg apk_file "$ARM64_APK_NAME" \
    --arg apk_sha256 "$ARM64_APK_SHA256" \
    --argjson apk_size "$ARM64_APK_SIZE" \
    '{schema:1,version_name:$version_name,version_code:$version_code,application_id:$application_id,core_tag:$core_tag,core_commit:$core_commit,core_patch_sha256:$core_patch_sha256,signer_sha256:$signer_sha256,abi:["arm64-v8a"],apk_file:$apk_file,apk_sha256:$apk_sha256,apk_size:$apk_size}' \
    > "$OUTPUT_DIR/release-metadata.json"

{
    printf '%s\n' \
        "## Zapret KVN $ZAPRET_VERSION_NAME" \
        '' \
        "- Канал: $ZAPRET_RELEASE_CHANNEL" \
        '- Android package: io.github.zapretkvn.android' \
        '- APK: отдельные arm64-v8a, armeabi-v7a и x86_64' \
        "- Core tag: \`$CORE_TAG\`" \
        "- Core commit: \`$CORE_COMMIT\`" \
        "- Core patch SHA-256: \`$CORE_PATCH_SHA256\`" \
        "- Signing certificate SHA-256: \`$SIGNER_SHA256\`"
    for artifact in "${ARTIFACTS[@]}"; do
        printf -- '- %s APK SHA-256: `%s`\n' \
            "$(jq -r .abi <<<"$artifact")" \
            "$(jq -r .apk_sha256 <<<"$artifact")"
    done
    printf '%s\n' \
        '' \
        '### Что изменилось' \
        '' \
        '- Добавлена «Автоматизация VPN»: для Wi-Fi, мобильной сети, Ethernet и других подключений можно независимо выбрать, должен ли VPN работать. По умолчанию автоматизация выключена, поэтому поведение существующих установок не меняется.' \
        '- Для Wi-Fi можно задать доверенные сети по SSID вручную или добавить текущую сеть. Названия сетей остаются только на устройстве и не попадают в диагностику; доступ к геопозиции запрашивается лишь при добавлении текущего SSID.' \
        '- Когда правила запрещают VPN в текущей сети, приложение переходит в состояние «Приостановлено», закрывает TUN и ядро, а при смене сети автоматически возобновляет подключение. «Остановить» по-прежнему полностью выключает VPN, а «Подключить сейчас» временно переопределяет правило до следующей смены сети.' \
        '' \
        'Каждый APK содержит только одну архитектуру и встроенный libbox; отдельная динамическая загрузка ядра не используется.' \
        '' \
        '### Известные ограничения MVP' \
        '' \
        '- Android 8+; доступны отдельные arm64-v8a, armeabi-v7a и x86_64 APK, интерфейс рассчитан на телефоны.' \
        '- Always-on/Lockdown и shared UID не поддерживаются как гарантированный per-app режим.' \
        '- Managed DNS перехватывает TCP/UDP 53, но не встроенный DoH, DoT или mDNS; FakeIP выключен.' \
        '- Domain-only block не является firewall: для гарантии нужен IP/CIDR rule-set.' \
        '- Явный outbound tag общего правила должен существовать в каждом используемом профиле; пустой tag автоматически выбирает proxy текущего профиля.' \
        '- Clash YAML и Hysteria v1 URI пока не импортируются; raw JSON остаётся ответственностью пользователя.' \
        '- APK проверяется один раз при запуске; загрузка и системная установка требуют подтверждения, silent install недоступен. Подписки не обновляются в фоне, core обновляется только вместе с APK.' \
        '- При неработающем proxy/DNS VPN закрывается без бесконечного retry и plaintext DNS fallback.'
} > "$OUTPUT_DIR/RELEASE_NOTES.md"

echo "Release bundle created in $OUTPUT_DIR"
