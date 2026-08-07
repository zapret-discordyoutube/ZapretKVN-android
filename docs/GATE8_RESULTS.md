# Gate 8 — протокол выпускной матрицы

Дата локального прогона: 22 июля 2026 года. Этот документ отделяет доказанное на AVD
от проверок, для которых обязательно реальное устройство, оператор или VPN-сервер.

Stable `v0.2.1` опубликован 23 июля 2026 года по явному решению владельца. Публикация
не заменяет перечисленные ниже физические проверки и не переводит незакрытые пункты
Gate 8 в пройденные.

## Автоматизированные результаты

| Среда | Полный набор | Дополнительная проверка | Результат |
|---|---:|---|---|
| Android 8, API 26 AVD | 66/66 + security delta 3/3 | legacy resolver, clipboard/log/export/notification security | пройдено |
| Android 10, API 29 AVD | 66/66 | `DnsResolver`, Private DNS, Always-on/Lockdown API | пройдено |
| Android 16, API 36 AVD | 67/67 | современный lifecycle, UI, security и signing history | пройдено |

JVM: 85/85. Exact core fixtures: 6/6. Pinned core:
`ff11f007ec798136a5de258f947a4f34011a37ea`.

`scripts/verify-gate8-stress.sh` отдельно выполнил на API 29 и API 36:

- 100 последовательных connect/stop;
- 50 реальных переключений эмуляторной Wi-Fi/cellular underlying network;
- ровно 151 создание core: 100 подключений, одно подключение перед переходами и 50 restart;
- после завершения: session/core/TUN/callback = 0;
- FD и thread count не превысили прогретый baseline более чем на два.

Команда использует только debug receiver, защищённый `android.permission.DUMP`. Receiver
отсутствует в release APK и дополнительно запрещён manifest-аудитом CI.

## Найденные проблемы

На API 29 `getPackageArchiveInfo()` вернул `signingInfo=null` для корректного APK.
Verifier исправлен: запрашивает `GET_SIGNING_CERTIFICATES | GET_SIGNATURES`; если Android
не предоставляет signing history, применяется более строгая проверка точного текущего
сертификата. Чужая подпись от этого не принимается.

Внешний strict Private DNS дважды показал единичный timeout/EOF. Production корректно
закрыл TUN. Тест повторяет успешную проверку не более одного раза и только после полного
cleanup, моделируя явный повтор подключения пользователем; health-check приложения не
получил скрытого retry или fallback.

## Статус R8-01–R8-10

- `R8-02`, `R8-03`, `R8-05`, `R8-10` — автоматизированная часть пройдена.
- `R8-04` — AVD Wi-Fi/cellular, IPv4/IPv6 dual-stack и captive-portal fail-close пройдены;
  физические cellular, IPv6-only/NAT64 и настоящий captive portal остаются открыты.
- `R8-06` — автоматизированная DNS matrix пройдена; физическая часть DNS ADR открыта.
- `R8-07` — exact/AVD routing matrix пройдена; повтор на физических OEM открыт.
- `R8-09` — пустые списки, удалённый package и revoke пройдены; shared UID открыт.
- `R8-01` и `R8-08` нельзя закрыть без реального слабого телефона и доступных тестовых
  серверов каждого заявленного протокола.

## Что нужно для оставшихся галочек

1. Реальный слабый телефон API 26/28 и современный телефон Android 14+.
2. SIM/mobile data, отдельная IPv6-only/NAT64 сеть и настоящий captive portal.
3. Два подписанных тестовых APK с общим UID либо подготовленный device fixture.
4. Контролируемые VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и TUIC endpoints с
   TCP, UDP и QUIC сценариями.

До этих прогонов проект не объявляется готовым к публичному release.

## CPU, память и батарея — автоматизированный прогон

22 июля 2026 года `scripts/verify-gate8-performance.sh` выполнил на API 36 AVD
16 сценариев по пять повторов. Каждый сравниваемый вариант запускался в новом app
process, использовал один и тот же pinned core и одинаковый workload: 5 секунд idle,
8 MiB TCP echo либо 12 уникальных DNS-имён. Один process CPU tick равен 10 мс.

| Сценарий, median 5 прогонов | CPU ticks | PSS | TUN bytes | UID RX+TX | Throughput |
|---|---:|---:|---:|---:|---:|
| VPN off, экран выключен | 0 | 93 150 KiB | 0 | 0 | — |
| VPN idle, экран выключен | 2 | 149 303 KiB | 0 | 0 | — |
| Невыбранный UID, 8 MiB | 1 | 146 405 KiB | 0 | 0 | 9,34 Mbit/s |
| Выбранный `direct`, 8 MiB | 43 | 147 097 KiB | 34 581 272 | 53 090 760 | 5,22 Mbit/s |
| Выбранный SOCKS proxy, 8 MiB | 45 | 146 339 KiB | 34 638 464 | 53 173 520 | 5,13 Mbit/s |
| Главная видима, 5 секунд | 8 | 146 580 KiB | 0 | 0 | — |
| UI закрыт, 5 секунд | 2 | 146 699 KiB | 0 | 0 | — |
| Диагностика видима, 5 секунд | 0 | 151 149 KiB | 0 | 0 | — |

В пяти idle-окнах UID Zapret KVN передал ровно 0 байт и держал 0 status/log clients.
Счётчик TUN дважды вырос на 96 байт; это интерфейсный шум, не app-owned network traffic.
В пяти передачах по 8 MiB невыбранным UID сетевой счётчик Zapret KVN оставался равен
нулю, а TUN изменился только один раз на 96 байт. Для выбранного `direct` тот же workload
дал median 53 090 760 UID bytes и 34 581 272 TUN bytes. Тем самым harness проверяет
Android per-app boundary независимо двумя счётчиками и не выдаёт отсутствие пакетов TUN
за отсутствие фонового трафика самого приложения.

`sequential` обработал 12 уникальных DNS-имён за median 137,6 мс, `parallel` —
за 155,0 мс. На этом AVD parallel не дал выигрыша и создавал второй DoH запрос;
managed default остаётся `sequential`.

Сравнения `mixed`/`system`, MTU 9000/1500 и GCPercent 100/10 дали менее 5% разницы
по throughput, времени, CPU и PSS. По принятой политике это шум, поэтому текущие
defaults не изменены и новые GUI-переключатели не добавлены. Direct/proxy различались
менее чем на 5% по CPU, throughput и PSS; это разные пользовательские пути, а не
взаимозаменяемые defaults. Реальный VPN-сервер всё равно нужен для физической матрицы.

Сырые данные сохранены локально в
`build/gate8-performance/api36-sdk_gphone64_x86_64-20260722T110520Z/`: 80 JSONL
измерений, raw-файлы `batterystats`/meminfo/procstats/power/alarm/job/netstats,
System Trace и общий `SHA256SUMS` — 134 файла суммарно. SHA-256 `summary.json`:
`6a3afd854c0f98b36325179c84d86f58d7edd841fbcd9481874875767a9ae77e`; SHA-256
итогового manifest: `53ef9ba18ca1bba79fb9d50206f028bcfca808b1e4150efdca16ae2185aa36f5`.
Ручной `workflow_dispatch` CI запускает ту же API 36 матрицу и загружает каталог как
artifact.

Совместимость harness отдельно проверена на API 26 AVD сокращённым workload
(5 × 16 сценариев, 1 секунда idle, 1 MiB, 2 DNS-имени). Прогон прошёл полностью;
для отсутствующего в Android 8 `getconf` metadata честно фиксирует стандартный Linux
`USER_HZ=100` как `clock_ticks_source=linux-user-hz`. Эти smoke-цифры не участвуют в
выборе defaults и не заменяют слабое физическое устройство.

Это исследовательские AVD-данные: `energy_valid=false`. Они не закрывают обязательный
release/profileable прогон на слабом API 26/28 и современном физическом устройстве,
PowerMetric/ODPM или batterystats при стабильной температуре, Go GC count/pause и OOM.

## Security и release candidate

`scripts/verify-release-candidate.sh` проверяет уже собранный arm64 APK, а не только
исходный manifest. На локальном RC подтверждены:

- точный permission allowlist, `Debug=false`, backup/cleartext/process restrictions;
- единственный non-exported VPN service и bounded non-exported FileProvider;
- отсутствие runtime profile/diagnostic/update/temp material и security canaries в APK;
- APK 75 505 859 bytes (`f677408c8c1e576e916235dda5ad7bdbbb6ef518f61cc199240c89d15c04fed0`),
  R8 mapping 39 743 272 bytes;
- отдельный `native-debug-symbols.zip` 44 208 695 bytes с `.symtab`/`.debug_info`;
- совпадение всех 26 allocated ELF-секций symbols build с production `libbox` pinned commit
  `ff11f007ec798136a5de258f947a4f34011a37ea`.

После нормализации ZIP metadata два независимых symbols build дали одинаковый SHA-256
`f8ffac5ec47f83baeb012f23d0eb86dcae876f25f44f2214621d148b6d01663a`.

Managed runtime удаляет внешний Clash listener и связанные UI/secret поля. Instrumented
security tests проверяют redaction уже в памяти, export/share/temp cleanup, отсутствие
app-private clipboard history и foreground notification из закрытого enum состояний.

`scripts/verify-release-device.sh` собрал два minified x86_64 RC одной временной подписью и
на API 36 выполнил clean install, пять force-stopped cold starts, same-key update, запуск
после update, отказ downgrade и чужой подписи. Cold start: 399/419/423/371/407 ms,
median 407 ms; release `run-as` отклонён, process crash отсутствует.

`R8-21…R8-24` закрыты. `R8-25` остаётся открытым только для последнего arm64 APK,
подписанного постоянным Forgejo release key и установленного/обновлённого на реальном
устройстве непосредственно перед публикацией. Временный тестовый ключ не выдаётся за
production evidence.

Host-side audit stable `v0.2.1` пройден для всех трёх ABI. Arm64 APK имеет SHA-256
`170faf123593ca1c2e24cdabd9ab9032860bf5eae001c1d23642c2ae39ef08fa`,
signer SHA-256
`f718835f014c8c4bfdcb281483eb9aea0d33cf705f7f1399545821b164f58898`
и exact core `ff11f007ec798136a5de258f947a4f34011a37ea`. Это не заменяет открытый
real-device прогон `R8-25`.

Stable publisher запускается локально после явного `--final-gate-approved`, сверяет
production JKS с закреплённым публичным SHA-256, повторяет host-side build/test/security
gates и создаёт Forgejo Release без ожидания Forgejo Actions. После публикации фоновый
workflow с одноразовым тестовым ключом повторяет сборку исходников, затем скачивает
Release и проверяет metadata, checksum, ABI, version и production fingerprint. Полные
instrumented/process/stress/device-gates выполняются локально до публикации. Production key
в Forgejo Actions больше не передаётся.
