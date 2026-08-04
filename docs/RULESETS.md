# Встроенные rule-set

`zapret-ru-domains.json` — небольшой исходный список доменных зон России и отдельных
сайтов, которые должны идти через `direct`. Binary `.srs` собирается точным audit CLI
из `core.properties`.

`zapret-ru-ip.srs` берётся из закреплённого snapshot `SagerNet/sing-geoip@5605651c12ed5b2fcf3b5de580c041eb9d8d938e`; ожидаемый SHA-256 — `1f4cccc9bb9510bb29d8a4b7d326b869bff94e9911d555acc0570545dabfaa7b`.

Оба файла доставляются только вместе с APK. Runtime-загрузки или фонового updater нет.
