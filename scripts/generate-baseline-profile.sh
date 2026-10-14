#!/usr/bin/env bash
# Генерирует app/src/main/baseline-prof.txt: собирает не обфусцированный профилируемый
# release (build type profileGen, подпись одноразовым ключом), прогоняет на эмуляторе
# пути пользователя и превращает записанный ART-профиль в правила Baseline Profile.
# AGP сам компилирует baseline-prof.txt в assets/dexopt/baseline.prof и при R8 переносит
# исходные имена на обфусцированные.
#
# Использование:
#   scripts/generate-baseline-profile.sh --serial emulator-5560 [--rounds 3] [--skip-build]
#
# Нужен эмулятор google_apis (userdebug, работает `adb root`). Физические устройства и
# adb из PATH не используются; боевой ключ подписи не трогается.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE=io.github.zapretkvn.android
OUTPUT="$PROJECT_ROOT/app/src/main/baseline-prof.txt"
SERIAL="${ZAPRET_SERIAL:-}"
ROUNDS=3
SKIP_BUILD=false

while (($#)); do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --rounds) ROUNDS="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --output) OUTPUT="$2"; shift 2 ;;
        *) echo "Неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

# shellcheck disable=SC1091
source "$PROJECT_ROOT/core.properties"
if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
fi
: "${ANDROID_HOME:?ANDROID_HOME должен указывать на Android SDK}"
export ANDROID_HOME
# Только adb из SDK: `adb` из PATH может быть обёрткой над личным телефоном.
ADB="${ZAPRET_ADB:-$ANDROID_HOME/platform-tools/adb}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS"

if [[ ! "$SERIAL" =~ ^emulator-[0-9]+$ ]]; then
    echo "Нужен серийный номер эмулятора (--serial emulator-5560 или ZAPRET_SERIAL)." >&2
    exit 2
fi
for tool in "$ADB" "$BUILD_TOOLS/apksigner" "$BUILD_TOOLS/zipalign"; do
    [[ -x "$tool" ]] || { echo "Нет $tool" >&2; exit 1; }
done
for command in keytool python3 zip unzip sort; do
    command -v "$command" >/dev/null || { echo "Нужна команда $command" >&2; exit 1; }
done

TEMP_DIR="$(mktemp -d -t zapret-kvn-baseline-XXXXXXXX)"
cleanup() { rm -rf "$TEMP_DIR" "${UI_HELPER_DIR:-}"; }
trap cleanup EXIT
# shellcheck disable=SC1091
source "$PROJECT_ROOT/scripts/lib/adb-ui.sh"

adb_dev wait-for-device
[[ "$(adb_dev shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || {
    echo "Эмулятор $SERIAL ещё не загрузился" >&2; exit 1; }
adb_dev root >/dev/null 2>&1 || true
adb_dev wait-for-device
if [[ "$(adb_dev shell id -u | tr -d '\r')" != 0 ]]; then
    echo "На $SERIAL нет root: нужен образ google_apis (userdebug), не google_apis_playstore." >&2
    exit 1
fi
ui_read_screen

case "$(adb_dev shell getprop ro.product.cpu.abi | tr -d '\r')" in
    x86_64) ABI=x86_64 ;;
    arm64-v8a) ABI=arm64-v8a ;;
    armeabi-v7a) ABI=armeabi-v7a ;;
    *) echo "Неподдерживаемый ABI эмулятора" >&2; exit 1 ;;
esac

# Одноразовый ключ: он живёт в TEMP_DIR и исчезает вместе со сборкой.
KEYSTORE="$TEMP_DIR/ephemeral.jks"
keytool -genkeypair -noprompt -keystore "$KEYSTORE" -storepass changeit -keypass changeit \
    -alias baseline -keyalg RSA -keysize 2048 -validity 2 \
    -dname "CN=Zapret KVN baseline profile (ephemeral)" >/dev/null 2>&1

PROFILE_GEN_APK="$PROJECT_ROOT/app/build/outputs/apk/profileGen/app-profileGen.apk"
if [[ "$SKIP_BUILD" != true ]]; then
    echo "== Сборка profileGen ($ABI)"
    (
        cd "$PROJECT_ROOT"
        ZAPRET_SIGNING_STORE_FILE="$KEYSTORE" ZAPRET_SIGNING_STORE_PASSWORD=changeit \
            ZAPRET_SIGNING_KEY_ALIAS=baseline ZAPRET_SIGNING_KEY_PASSWORD=changeit \
            ./gradlew --no-configuration-cache :app:assembleProfileGen -PzapretAbi="$ABI" \
            >"$TEMP_DIR/gradle.log" 2>&1
    ) || { tail -n 40 "$TEMP_DIR/gradle.log" >&2; exit 1; }
fi
[[ -f "$PROFILE_GEN_APK" ]] || { echo "Нет $PROFILE_GEN_APK" >&2; exit 1; }

# Из APK убираем уже встроенный профиль: иначе устройство подхватит прошлые правила
# и они «самоподтвердятся» в новом файле. Подпись ставим заново тем же одноразовым ключом.
GEN_APK="$TEMP_DIR/profilegen.apk"
cp "$PROFILE_GEN_APK" "$TEMP_DIR/unsigned.apk"
zip -q -d "$TEMP_DIR/unsigned.apk" 'assets/dexopt/baseline.prof' 'assets/dexopt/baseline.profm' || true
"$BUILD_TOOLS/zipalign" -p -f 4 "$TEMP_DIR/unsigned.apk" "$GEN_APK"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:changeit --key-pass pass:changeit \
    --ks-key-alias baseline "$GEN_APK"
if unzip -Z1 "$GEN_APK" | grep -q '^assets/dexopt/'; then
    echo "В профилирующем APK остался встроенный профиль" >&2
    exit 1
fi

echo "== Установка на $SERIAL"
adb_dev uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb_dev install --no-incremental "$GEN_APK" >/dev/null
adb_dev shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adb_dev shell cmd package compile --reset "$PACKAGE" >/dev/null
adb_dev shell rm -f "/data/misc/profiles/cur/0/$PACKAGE/primary.prof" || true

# Тестовые серверы: 60 выдуманных ссылок VLESS (адреса нигде не открываются, VPN не
# запускается), чтобы список серверов был длинным.
python3 -I - > "$TEMP_DIR/servers.txt" <<'PY'
import uuid

countries = [("DE", "Германия", "185.12.3"), ("NL", "Нидерланды", "94.20.5"),
             ("FI", "Финляндия", "95.216.7"), ("US", "США", "104.18.9"),
             ("SE", "Швеция", "77.88.1"), ("PL", "Польша", "51.68.2")]
for i in range(60):
    code, name, prefix = countries[i % len(countries)]
    user = uuid.uuid5(uuid.NAMESPACE_DNS, f"baseline-profile-{i}")
    print(f"vless://{user}@{prefix}.{i + 10}:443?security=tls&sni=example.org&type=ws"
          f"&host=example.org&path=%2Fws&fp=chrome&encryption=none#{code} {name} {i + 1}")
PY

profile_path="/data/misc/profiles/cur/0/$PACKAGE/primary.prof"
flush_profile() {
    adb_dev shell "killall -s SIGUSR1 $PACKAGE" >/dev/null 2>&1 || true
    sleep 4
}

echo "== Прогон путей пользователя: $ROUNDS раза(ов), холодный старт каждый раз"
for ((round = 1; round <= ROUNDS; round++)); do
    adb_dev shell am force-stop "$PACKAGE"
    adb_dev shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
    ui_wait "Главная" 20 || { echo "Приложение не показало Главную" >&2; exit 1; }
    sleep 3
    if ((round == 1)); then
        journey_import_servers "$TEMP_DIR/servers.txt"
    fi
    journey_tour
    flush_profile
    echo "   круг $round: профиль $(adb_dev shell "stat -c %s $profile_path" | tr -d '\r') байт"
done

# Пока процесс жив, снимаем итог: --dump-classes-and-methods даёт текстовый HRF
# (флаги H/S/P + дескриптор), то есть уже формат baseline-prof.txt.
flush_profile
APK_ON_DEVICE="$(adb_dev shell pm path "$PACKAGE" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
adb_dev shell "profman --dump-classes-and-methods --profile-file=$profile_path \
    --apk=$APK_ON_DEVICE --dex-location=$APK_ON_DEVICE" | tr -d '\r' > "$TEMP_DIR/dump.txt"
[[ -s "$TEMP_DIR/dump.txt" ]] || { echo "profman вернул пустой профиль" >&2; exit 1; }

# Оставляем наш код, заметную часть AndroidX/Kotlin, которой пользуется приложение, и
# libbox (Java-обвязка ядра). Платформенные классы и чужие пакеты в профиль не нужны.
LC_ALL=C grep -E '^[HSP]*L(io/github/zapretkvn|io/nekohasekai|androidx|kotlinx|kotlin|com/journeyapps)/' \
    "$TEMP_DIR/dump.txt" | LC_ALL=C sort -u > "$TEMP_DIR/baseline-prof.txt"
RULES="$(wc -l < "$TEMP_DIR/baseline-prof.txt")"
OWN="$(grep -c -E '^[HSP]*Lio/github/zapretkvn/' "$TEMP_DIR/baseline-prof.txt" || true)"
if ((OWN < 500)); then
    echo "Подозрительно мало правил приложения ($OWN): прогон не дошёл до экранов" >&2
    exit 1
fi
cp "$TEMP_DIR/baseline-prof.txt" "$OUTPUT"
echo "== Готово: $OUTPUT — $RULES правил, из них приложения: $OWN"

adb_dev uninstall "$PACKAGE" >/dev/null 2>&1 || true
