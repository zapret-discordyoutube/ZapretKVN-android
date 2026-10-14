#!/usr/bin/env bash
# Сравнивает два release-APK (например, без правил приложения в Baseline Profile и с ними)
# на эмуляторе: время холодного старта (`am start -W -S`, TotalTime) и кадры
# (`dumpsys gfxinfo`) на прокрутке списка серверов и переключении вкладок.
#
# Использование:
#   scripts/measure-startup-frames.sh --serial emulator-5560 \
#       --before before.apk --after after.apk [--runs 10] [--rounds 2] [--journeys 3] [--out DIR]
#
# Оба APK должны быть подписаны одним ключом (скрипт всё равно переустанавливает приложение
# с нуля перед каждым состоянием и заново импортирует тестовые серверы). Профиль применяется
# так же, как это делает Macrobenchmark: ProfileInstallReceiver кладёт встроенный профиль
# в профиль приложения, затем `cmd package compile -m speed-profile -f`.
# `--verify-baseline` добавляет третье состояние: `compile -m verify` (вообще без AOT).
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE=io.github.zapretkvn.android
SERIAL="${ZAPRET_SERIAL:-}"
BEFORE_APK="" AFTER_APK=""
RUNS=10 ROUNDS=2 JOURNEYS=3
OUT_DIR="$PROJECT_ROOT/build/perf"
VERIFY_BASELINE=false

while (($#)); do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --before) BEFORE_APK="$2"; shift 2 ;;
        --after) AFTER_APK="$2"; shift 2 ;;
        --runs) RUNS="$2"; shift 2 ;;
        --rounds) ROUNDS="$2"; shift 2 ;;
        --journeys) JOURNEYS="$2"; shift 2 ;;
        --out) OUT_DIR="$2"; shift 2 ;;
        --verify-baseline) VERIFY_BASELINE=true; shift ;;
        *) echo "Неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "${ANDROID_HOME:-}" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
fi
: "${ANDROID_HOME:?ANDROID_HOME должен указывать на Android SDK}"
ADB="${ZAPRET_ADB:-$ANDROID_HOME/platform-tools/adb}"
[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo "Нужен --serial эмулятора." >&2; exit 2; }
[[ -f "$BEFORE_APK" && -f "$AFTER_APK" ]] || { echo "Нужны --before и --after (файлы APK)." >&2; exit 2; }
command -v python3 >/dev/null

mkdir -p "$OUT_DIR"
cleanup() { rm -rf "${UI_HELPER_DIR:-}" "${WORK_DIR:-}"; }
trap cleanup EXIT
WORK_DIR="$(mktemp -d -t zapret-kvn-measure-XXXXXXXX)"
# shellcheck disable=SC1091
source "$PROJECT_ROOT/scripts/lib/adb-ui.sh"

adb_dev wait-for-device
adb_dev root >/dev/null 2>&1 || true
adb_dev wait-for-device
[[ "$(adb_dev shell id -u | tr -d '\r')" == 0 ]] || { echo "Нужен root на эмуляторе (google_apis)." >&2; exit 1; }
ui_read_screen

python3 -I - > "$WORK_DIR/servers.txt" <<'PY'
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

RESULTS="$OUT_DIR/results.csv"
echo "state,round,kind,value" > "$RESULTS"
record() { echo "$1,$2,$3,$4" >> "$RESULTS"; }

# Состояние: установка с нуля, тестовые серверы, затем нужный режим компиляции.
prepare_state() {
    local apk="$1" mode="$2"
    adb_dev uninstall "$PACKAGE" >/dev/null 2>&1 || true
    adb_dev install --no-incremental "$apk" >/dev/null
    adb_dev shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    adb_dev shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
    ui_wait "Главная" 30
    journey_import_servers "$WORK_DIR/servers.txt"
    sleep 2
    case "$mode" in
        profile)
            local result
            result="$(adb_dev shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
                -n "$PACKAGE/androidx.profileinstaller.ProfileInstallReceiver" | tr -d '\r')"
            echo "   ProfileInstaller: $(grep -o 'result=[0-9]*' <<<"$result" | head -n 1)" >&2
            sleep 3
            adb_dev shell am force-stop "$PACKAGE"
            profile_entries "$PACKAGE" > "$WORK_DIR/entries"
            adb_dev shell cmd package compile -m speed-profile -f "$PACKAGE" >/dev/null
            ;;
        verify)
            adb_dev shell am force-stop "$PACKAGE"
            adb_dev shell cmd package compile --reset "$PACKAGE" >/dev/null
            adb_dev shell cmd package compile -m verify -f "$PACKAGE" >/dev/null
            ;;
    esac
    adb_dev shell am force-stop "$PACKAGE"
}

# Число записей (классы + методы) в профиле приложения на устройстве.
profile_entries() {
    local apk
    apk="$(adb_dev shell pm path "$1" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
    adb_dev shell "profman --dump-classes-and-methods --profile-file=/data/misc/profiles/cur/0/$1/primary.prof \
        --apk=$apk --dex-location=$apk 2>/dev/null | wc -l" | tr -d '\r'
}

odex_size() {
    adb_dev shell "stat -c %s \$(ls /data/app/*/$PACKAGE-*/oat/*/base.odex 2>/dev/null | head -n 1) 2>/dev/null" \
        | tr -d '\r' | head -n 1
}

cold_starts() {
    local state="$1" round="$2" i total
    adb_dev shell am start -W -S -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null  # прогрев, не считается
    for ((i = 1; i <= RUNS; i++)); do
        adb_dev shell am force-stop "$PACKAGE"
        adb_dev shell "sync; echo 3 > /proc/sys/vm/drop_caches"
        sleep 1.5
        total="$(adb_dev shell am start -W -S -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" \
            | tr -d '\r' | sed -n 's/^TotalTime: *//p')"
        record "$state" "$round" start_ms "$total"
        sleep 1
    done
}

frame_stats() {
    local state="$1" round="$2" j dump
    for ((j = 1; j <= JOURNEYS; j++)); do
        adb_dev shell am force-stop "$PACKAGE"
        adb_dev shell am start -W -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
        sleep 4
        adb_dev shell dumpsys gfxinfo "$PACKAGE" reset >/dev/null
        journey_frames
        sleep 1
        dump="$(adb_dev shell dumpsys gfxinfo "$PACKAGE" | tr -d '\r')"
        python3 -I -c '
import re, sys
state, rnd, journey, out = sys.argv[1:5]
text = sys.stdin.read()
def grab(pattern, cast=float):
    m = re.search(pattern, text)
    return cast(m.group(1)) if m else None
rows = {
    "frames_total": grab(r"Total frames rendered: (\d+)", int),
    "frames_janky": grab(r"Janky frames: (\d+)", int),
    "p50_ms": grab(r"50th percentile: (\d+)ms"),
    "p90_ms": grab(r"90th percentile: (\d+)ms"),
    "p95_ms": grab(r"95th percentile: (\d+)ms"),
    "p99_ms": grab(r"99th percentile: (\d+)ms"),
}
with open(out, "a") as f:
    for key, value in rows.items():
        if value is not None:
            f.write(f"{state},{rnd},{key},{value}\n")
' "$state" "$round" "$j" "$RESULTS" <<<"$dump"
    done
}

measure_state() {
    local state="$1" apk="$2" mode="$3" round="$4"
    echo "== Раунд $round, состояние $state ($mode)"
    prepare_state "$apk" "$mode"
    record "$state" "$round" odex_bytes "$(odex_size || echo 0)"
    [[ -s "$WORK_DIR/entries" ]] && record "$state" "$round" profile_entries "$(cat "$WORK_DIR/entries")"
    : > "$WORK_DIR/entries"
    cold_starts "$state" "$round"
    frame_stats "$state" "$round"
}

for ((round = 1; round <= ROUNDS; round++)); do
    # Порядок чередуется, чтобы дрейф нагрузки хоста не доставался одному состоянию.
    if ((round % 2 == 1)); then
        measure_state before "$BEFORE_APK" profile "$round"
        measure_state after "$AFTER_APK" profile "$round"
    else
        measure_state after "$AFTER_APK" profile "$round"
        measure_state before "$BEFORE_APK" profile "$round"
    fi
    if [[ "$VERIFY_BASELINE" == true ]]; then
        measure_state noprofile "$AFTER_APK" verify "$round"
    fi
done
adb_dev uninstall "$PACKAGE" >/dev/null 2>&1 || true

python3 -I - "$RESULTS" <<'PY'
import csv, statistics, sys
from collections import defaultdict

data = defaultdict(lambda: defaultdict(list))
with open(sys.argv[1]) as f:
    for row in csv.DictReader(f):
        data[row["state"]][row["kind"]].append(float(row["value"]))
states = list(data)
print("| метрика | " + " | ".join(states) + " |")
print("|---|" + "---|" * len(states))
def line(label, fn):
    print(f"| {label} | " + " | ".join(fn(data[s]) for s in states) + " |")
def med(kind):
    return lambda d: f"{statistics.median(d[kind]):.0f}" if d[kind] else "-"
line("холодный старт, медиана, мс", med("start_ms"))
line("холодный старт, min–max, мс", lambda d: f"{min(d['start_ms']):.0f}–{max(d['start_ms']):.0f}")
line("выборка стартов", lambda d: str(len(d["start_ms"])))
line("кадров всего", lambda d: f"{sum(d['frames_total']):.0f}")
line("janky-кадры, %", lambda d: f"{100 * sum(d['frames_janky']) / max(1, sum(d['frames_total'])):.1f}")
for p in ("p50", "p90", "p95", "p99"):
    line(f"{p}, мс (медиана по прогонам)", med(p + "_ms"))
line("записей в профиле на устройстве", med("profile_entries"))
line("размер base.odex, байт", med("odex_bytes"))
PY
echo "Сырые значения: $RESULTS"
