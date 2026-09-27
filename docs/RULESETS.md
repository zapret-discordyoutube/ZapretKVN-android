# Встроенные rule-set

`zapret-ru-domains.json` — небольшой исходный список доменных зон России и отдельных
сайтов, которые должны идти через `direct`. Binary `.srs` собирается точным audit CLI
из `core.properties`.

`zapret-ru-ip.srs` берётся из закреплённого snapshot `SagerNet/sing-geoip@7fe82a879ad2666526730c195b55a6d8d9147908`; ожидаемый SHA-256 — `6e23f5580dd443e2f9c4895adafee8d199c1a3487182ad5f0a2256cf59c7e53a`.

Оба файла доставляются только вместе с APK. Runtime-загрузки или фонового updater нет.
