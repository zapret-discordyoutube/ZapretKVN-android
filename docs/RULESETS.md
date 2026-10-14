# Встроенные rule-set

`zapret-ru-domains.json` — небольшой исходный список доменных зон России и отдельных
сайтов, которые должны идти через `direct`. Binary `.srs` собирается точным audit CLI
из `core.properties`.

`zapret-ru-ip.srs` берётся из закреплённого snapshot `SagerNet/sing-geoip@eb32cd6ed4a16fcb3bf9a36ccebf28e45716ae84`; ожидаемый SHA-256 — `bdca5cdb44cdcf0940001e5952386822a6f4ffa8b2c8eea00340aa6f585c7f9d`.

Оба файла доставляются только вместе с APK. Runtime-загрузки наборов нет: они обновляются только вместе с APK (фоновая проверка и скачивание самого APK описаны в ARCHITECTURE.md).

## Таблица «IP → страна» (флаги серверов)

`app/src/main/assets/geoip/country.bin` — офлайн-таблица DB-IP «IP to Country Lite» (CC BY 4.0, «IP Geolocation by DB-IP», <https://db-ip.com>). Приложение определяет по ней страну по адресу самого сервера, чтобы показать флаг, когда его нет в имени. Источник, месяц, SHA-256 и размеры — в `app/src/main/assets/geoip/manifest.json`; формат файла описан в начале `scripts/build-geoip-country.py`.

Обновление — вручную перед выпуском, не в приложении:

```bash
python3 -I scripts/build-geoip-country.py          # текущий месяц, при отсутствии — предыдущий
python3 -I scripts/build-geoip-country.py --check  # файл соответствует manifest.json
bash scripts/verify-project.sh                     # проверка хэша, размера (до 3 МиБ) и атрибуции
```

После обновления поправьте месяц в `docs/THIRD_PARTY_NOTICES.md` и скопируйте его в `app/src/main/res/raw/third_party_notices.txt` (файлы должны быть побайтово равны). Загрузки таблицы во время работы нет.
