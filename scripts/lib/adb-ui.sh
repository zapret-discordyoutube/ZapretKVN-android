#!/usr/bin/env bash
# Общие помощники для сценариев на эмуляторе: поиск узлов по тексту через
# uiautomator и прогон «путей пользователя». Подключается через source из
# scripts/generate-baseline-profile.sh и scripts/measure-startup-frames.sh.
#
# Перед подключением вызывающий скрипт задаёт ADB (путь к adb из SDK) и SERIAL,
# а также PACKAGE. `adb` из PATH здесь не используется никогда.

UI_HELPER_DIR="$(mktemp -d -t zapret-kvn-ui-XXXXXXXX)"
cat > "$UI_HELPER_DIR/uifind.py" <<'PY'
import sys
import xml.etree.ElementTree as ET

# argv: режим (tap|has), искомый текст; XML hierarchy приходит со stdin.
mode, want = sys.argv[1], sys.argv[2]
raw = sys.stdin.read()
end = raw.find("</hierarchy>")
if end < 0:
    sys.exit(2)
root = ET.fromstring(raw[: end + len("</hierarchy>")])
exact, prefix = None, None
for node in root.iter("node"):
    labels = (node.get("text") or "", node.get("content-desc") or "")
    if want in labels and exact is None:
        exact = node
    elif any(label.startswith(want) for label in labels if label) and prefix is None:
        prefix = node
found = exact if exact is not None else prefix
if found is None:
    sys.exit(1)
if mode == "tap":
    left, top, right, bottom = map(
        int, found.get("bounds").replace("][", ",").strip("[]").split(",")
    )
    print((left + right) // 2, (top + bottom) // 2)
PY

adb_dev() { "$ADB" -s "$SERIAL" "$@"; }

ui_dump() { adb_dev exec-out uiautomator dump /dev/stdout 2>/dev/null || true; }

# Нажимает узел с точным текстом/описанием (иначе — по префиксу). 1 — узла нет.
ui_tap() {
    local coords
    coords="$(ui_dump | python3 -I "$UI_HELPER_DIR/uifind.py" tap "$1")" || return 1
    # shellcheck disable=SC2086
    adb_dev shell input tap $coords
}

ui_has() { ui_dump | python3 -I "$UI_HELPER_DIR/uifind.py" has "$1" >/dev/null; }

# Ждёт появления текста (до $2 секунд, по умолчанию 15).
ui_wait() {
    local deadline=$((SECONDS + ${2:-15}))
    until ui_has "$1"; do
        ((SECONDS < deadline)) || return 1
        sleep 0.5
    done
}

ui_back() { adb_dev shell input keyevent KEYCODE_BACK; sleep 0.8; }

SCREEN_W=1080
SCREEN_H=2400
ui_read_screen() {
    local size
    size="$(adb_dev shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)$/\1 \2/p' | tail -n 1)"
    if [[ -n "$size" ]]; then
        read -r SCREEN_W SCREEN_H <<<"$size"
    fi
}

# Свайп по вертикали на $2 долей высоты (направление: up — лента едет вверх).
ui_swipe() {
    local direction="$1" duration="${2:-300}" x y1 y2
    x=$((SCREEN_W / 2))
    if [[ "$direction" == up ]]; then
        y1=$((SCREEN_H * 70 / 100)); y2=$((SCREEN_H * 25 / 100))
    else
        y1=$((SCREEN_H * 25 / 100)); y2=$((SCREEN_H * 70 / 100))
    fi
    adb_dev shell input swipe "$x" "$y1" "$x" "$y2" "$duration"
}

# Центры вкладок нижней панели: Главная, Серверы, Маршруты, Настройки.
ui_tab_xy() {
    local index
    case "$1" in
        Главная) index=0 ;; Серверы) index=1 ;; Маршруты) index=2 ;; Настройки) index=3 ;;
        *) return 1 ;;
    esac
    local x=$(((SCREEN_W * (2 * index + 1)) / 8))
    echo "$x $((SCREEN_H * 947 / 1000))"
}

ui_tab() {
    local xy
    xy="$(ui_tab_xy "$1")"
    # shellcheck disable=SC2086
    adb_dev shell input tap $xy
    sleep 1.2
}

# Ищет текст, при необходимости листая ленту вверх, затем вниз, и нажимает его.
ui_tap_scroll() {
    local attempt
    ui_tap "$1" && return 0
    for attempt in 1 2 3 4 5 6; do
        ui_swipe up 350; sleep 0.6
        ui_tap "$1" && return 0
    done
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
        ui_swipe down 350; sleep 0.6
        ui_tap "$1" && return 0
    done
    return 1
}

# Открывает вложенный экран или шторку по тексту, выдерживает паузу ($2 с), при $3 = scroll
# листает содержимое (для шторок нельзя: свайп вниз закрывает её) и возвращается назад.
ui_visit() {
    if ui_tap_scroll "$1"; then
        sleep "${2:-1.8}"
        if [[ "${3:-}" == scroll ]]; then
            ui_swipe up 350; sleep 0.5
            ui_swipe down 350; sleep 0.5
        fi
        ui_back
    else
        echo "ВНИМАНИЕ: на экране нет «$1», пропускаю" >&2
    fi
}

# Импорт текстового файла со списком ссылок через системное «Открыть в…» без ручного ввода.
# $1 — локальный файл; в профиле появляется одна группа серверов.
journey_import_servers() {
    local local_file="$1" remote=/data/local/tmp/zapret-kvn-servers.txt
    adb_dev push "$local_file" "$remote" >/dev/null
    adb_dev shell chmod 644 "$remote"
    adb_dev shell am start -a android.intent.action.VIEW -d "file://$remote" -t text/plain \
        -n "$PACKAGE/io.github.zapretkvn.android.MainActivity" >/dev/null
    ui_wait "Предпросмотр импорта" 20
    ui_tap "Один профиль-группа"
    ui_wait "Подключить" 20
    adb_dev shell rm -f "$remote"
}

# Полный обход приложения для записи профиля: все вкладки, список серверов, редактор
# JSON, выбор приложений и страницы настроек.
journey_tour() {
    ui_tab Главная; sleep 1.5

    ui_tab Серверы; sleep 1
    for _ in 1 2 3; do ui_swipe up 250; sleep 0.3; done
    for _ in 1 2 3; do ui_swipe down 250; sleep 0.3; done
    ui_swipe up 120; sleep 0.8; ui_swipe down 120; sleep 0.8
    if ui_tap "Сортировка"; then sleep 1; ui_back; fi
    if ui_tap "Поиск серверов"; then sleep 1; ui_back; fi
    ui_tap_scroll "Сменить" && { sleep 2; ui_back; } || true

    ui_tab Маршруты; sleep 1.2
    ui_visit "Выбрать приложения" 3
    ui_tab Маршруты
    ui_tap_scroll "Изменить режим" && { sleep 1.5; ui_back; } || true
    ui_tap_scroll "Правило" && { sleep 1.5; ui_back; } || true
    ui_tap_scroll "Развернуть" && sleep 1 || true
    if ui_tap_scroll "Исходный JSON профиля"; then
        sleep 3
        ui_swipe up 300; sleep 0.5; ui_swipe down 300; sleep 0.5
        ui_back
    fi

    ui_tab Настройки; sleep 1.2
    ui_visit "Сеть и DNS" 1.8 scroll
    ui_tab Настройки; ui_visit "Автоматизация VPN" 1.8 scroll
    ui_tab Настройки; ui_visit "Оформление" 1.5
    ui_tab Настройки; ui_visit "Обновления" 2.5 scroll
    ui_tab Настройки; ui_visit "Сообщество" 1.5 scroll
    ui_tab Настройки; ui_visit "О приложении" 1.8 scroll
    ui_tab Настройки
    if ui_tap_scroll "Дополнительно"; then
        sleep 1.5
        ui_visit "Скрытие VPN" 1.8 scroll
        ui_visit "Диагностика" 2 scroll
        ui_back
    fi

    ui_tab Главная; sleep 1
}

# Короткий прогон для кадров: только координаты, без uiautomator, чтобы нагрузка была
# одинаковой при каждом запуске. Прокрутка списка серверов и переключение вкладок.
journey_frames() {
    local x=$((SCREEN_W / 2)) top=$((SCREEN_H * 70 / 100)) bottom=$((SCREEN_H * 25 / 100))
    local xy_home xy_servers xy_routing xy_settings
    xy_home="$(ui_tab_xy Главная)"; xy_servers="$(ui_tab_xy Серверы)"
    xy_routing="$(ui_tab_xy Маршруты)"; xy_settings="$(ui_tab_xy Настройки)"
    adb_dev shell "
        input tap $xy_servers; sleep 1
        for round in 1 2 3; do
            for i in 1 2 3 4 5 6; do input swipe $x $top $x $bottom 200; done
            for i in 1 2 3 4 5 6; do input swipe $x $bottom $x $top 200; done
        done
        for round in 1 2 3; do
            input tap $xy_routing; sleep 0.8
            input tap $xy_settings; sleep 0.8
            input swipe $x $top $x $bottom 250; sleep 0.3
            input swipe $x $bottom $x $top 250; sleep 0.3
            input tap $xy_servers; sleep 0.8
            input tap $xy_home; sleep 0.8
        done
    "
}
