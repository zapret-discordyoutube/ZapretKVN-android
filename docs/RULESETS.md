# Встроенные rule-set

`zapret-ru-domains.json` — небольшой исходный список доменных зон России и отдельных
сайтов, которые должны идти через `direct`. Binary `.srs` собирается точным audit CLI
из `core.properties`.

`zapret-ru-ip.srs` берётся из закреплённого snapshot `SagerNet/sing-geoip@eb32cd6ed4a16fcb3bf9a36ccebf28e45716ae84`; ожидаемый SHA-256 — `bdca5cdb44cdcf0940001e5952386822a6f4ffa8b2c8eea00340aa6f585c7f9d`.

Оба файла доставляются только вместе с APK. Runtime-загрузки или фонового updater нет.
