# Zapret KVN Android — план реализации

> Рабочий TODO-лист проекта. Отмечать выполненное заменой `[ ]` на `[x]` только после прохождения указанного gate. Архитектурные решения здесь не переопределяются: источник истины — [главная архитектура](ARCHITECTURE.md), [DNS ADR](DNS_ARCHITECTURE.md), [Routing ADR](ROUTING_ARCHITECTURE.md), [VPN Hiding ADR](VPN_HIDING_ARCHITECTURE.md) и [политика форматов импорта](IMPORT_FORMATS.md).

| Поле | Значение |
|---|---|
| Статус | Публичный MVP завершён и выпущен как stable `v0.2.4`; расширенная физическая матрица продолжается по пользовательским отчётам |
| Текущий этап | Пострелизное тестирование и исправление подтверждённых проблем |
| Минимальная ОС | Android 8.0, API 26 |
| Устройства MVP | Телефоны |
| Ядро | `sing-box-extended` `v1.13.14-extended-2.5.2` |
| Commit ядра | `ff11f007ec798136a5de258f947a4f34011a37ea` |
| Модули | Только `app` |

## Как вести план

- Выполнять этапы по порядку; внутри этапа допустимы небольшие независимые задачи.
- Не отмечать gate по факту компиляции: требуются перечисленные тесты и сохранённый результат.
- Баг, блокирующий gate, добавлять прямо под соответствующим этапом как `- [ ] BUG-...`.
- Решение, меняющее TUN, DNS, routing, хранение или источник истины, сначала вносить в соответствующий ADR.
- Не добавлять новый слой, dependency, service, process или фоновую задачу «на будущее».
- После этапа обновлять строку «Текущий этап» и раздел «Текущее состояние» внизу.

## Зафиксировано до начала кода

- [x] Принята архитектура с одним product-модулем `app` и узкими направленными libraries (`network-bootstrap`, import), без Room, Hilt, WebView и WorkManager.
- [x] Разделены Android per-app scope и sing-box destination routing.
- [x] Зафиксированы один process, один `VpnService`, один TUN и один libbox instance.
- [x] Зафиксирована DNS-архитектура без FakeIP: Auto `профиль → Android → DoH`, а `fallback/parallel` используется только внутри защищённого этапа.
- [x] Зафиксирована маршрутизация `proxy` / `direct` / `reject` через настоящий JSON.
- [x] Зафиксирован точный commit ядра.
- [x] Подготовлены и проверены 7/7 эталонных JSON, включая Android WireGuard ClientBind.
- [x] Пройден `go test ./dns/... ./route/rule ./experimental/libbox`.
- [x] До первой публичной сборки выбрать окончательные `applicationId`, namespace и имя signing key.
- [x] Для production rule-set зафиксированы источники, лицензии, commit и SHA-256 списков.

## Definition of Done MVP

- [x] Публичный MVP принят владельцем 24 июля 2026 года после установки и проверки
  production-signed stable `v0.2.4` на реальном устройстве.
- [x] Новый пользователь может установить APK, импортировать VLESS/другой поддержанный
  URI или JSON, выбрать приложения и подключиться без ручного редактирования JSON.
- [x] Автоматизированы проверки per-app bypass, `proxy/direct/reject`, DNS, fail-close,
  JSON round-trip/backup, lifecycle, redaction и updater.
- [x] Пройдены обязательные для публикации fixture, unit, instrumented, manifest,
  signing, revision и release-аудиты.
- [x] Release APK содержит ядро нужного commit, подписан постоянным ключом и опубликован с SHA-256.

Расширенная проверка редких сочетаний устройств, операторов, IPv6/NAT64, captive
portal и физической энергии не блокирует статус MVP. Она продолжается после релиза
по пользовательским отчётам и не считается выполненной без соответствующего evidence.

## Этап 0 — каркас и воспроизводимая сборка

Цель: минимальный устанавливаемый APK и CI, доказывающий происхождение ядра.

### Репозиторий и Android

- [x] `I0-01` Инициализировать Git-репозиторий и добавить `.gitignore`, `docs/LICENSE`, `docs/README.md`.
- [x] `I0-02` Создать Gradle wrapper и один модуль `app`; Kotlin, Compose и Material 3, minSdk 26.
- [x] `I0-03` Зафиксировать версии AGP/Kotlin/JDK; не добавлять convention plugins и version catalog без реальной необходимости.
- [x] `I0-04` Создать `ZapretApplication`, ручной `AppContainer`, `MainActivity` и четыре нижние вкладки-заглушки.
- [x] `I0-05` Реализовать системную светлую/тёмную тему, Dynamic Color на API 31+ и встроенную палитру на API 26–30.
- [x] `I0-06` Добавить базовый manifest с минимальными разрешениями; не запрашивать `WAKE_LOCK` и `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- [x] `I0-07` Добавить debug/release build types, R8 и resource shrinking для release.


### Ядро и CI

- [x] `I0-08` Добавить скрипт сборки libbox AAR и audit CLI из полного pinned SHA.
- [x] `I0-09` Проверять, что HEAD исходника и embedded revision CLI равны pinned SHA.
- [x] `I0-10` Первоначально выбрать для MVP `arm64-v8a`; решение заменено `I7-10` после проверки размера универсального тестового APK.
- [x] `I0-11` В CI запускать `sing-box check` для всех файлов `testdata/**/*.json`.
- [x] `I0-12` В CI запускать pinned Go tests и Android unit tests.
- [x] `I0-13` Собирать debug APK в GitHub Actions и сохранять core version/revision рядом с artifact.
- [x] `I0-14` Добавить `docs/LICENSE`/`docs/NOTICE` для приложения, sing-box-extended и включённых библиотек.

### Gate 0

- [x] Чистый checkout одной командой собирает debug APK.
- [x] APK устанавливается и открывает четыре вкладки на API 26 и современной версии Android.
- [x] CI подтверждает exact core revision и принимает 7/7 fixtures.
- [x] В release manifest нет запрещённых разрешений, второго process или второго VPN service.

## Этап 1 — профили и настоящий JSON

Цель: импортировать, безопасно хранить, редактировать и проверять JSON без VPN.

- [x] `I1-01` Создать пакеты `profiles/`, `config/`, `importer/` внутри `app`, без отдельных Gradle-модулей.
- [x] `I1-02` Реализовать `ProfileStore`: `files/profiles/index.json`, `<id>.json`, одна `<id>.json.bak` через `AtomicFile`.
- [x] `I1-03` Хранить в index только UI metadata; DNS, routes и outbounds остаются исключительно в JSON профиля.
- [x] `I1-04` Реализовать атомарные create/read/update/delete/restore операции и очистку orphan temp-файлов.
- [x] `I1-05` Реализовать импорт raw JSON из системного file picker и буфера после явного нажатия.
- [x] `I1-06` Вызывать libbox `CheckConfig()` до сохранения изменённого профиля и показывать понятную ошибку.
- [x] `I1-07` Реализовать JSON-tree editor на `kotlinx.serialization.json`, сохраняющий неизвестные поля.
- [x] `I1-08` Сделать простой raw editor: моноширинный текст, поиск, format, validate, отмена несохранённых изменений.
- [x] `I1-09` Добавить список профилей, выбор активного профиля, переименование, удаление с подтверждением и восстановление backup.
- [x] `I1-10` Хранить тему, активный профиль и UI-настройки в DataStore; не копировать туда сетевую конфигурацию.
- [x] `I1-11` Реализовать `ManagedProfileFactory`: маленькие base/protocol/selector builders вместо набора полноразмерных JSON-шаблонов.
- [x] `I1-12` Для одиночной ссылки создавать один server outbound и selector `zapret-proxy`; для subscription — несколько server outbounds в том же selector.
- [x] `I1-13` Генерировать уникальные стабильные server tags без credentials; при совпадении имён добавлять детерминированный suffix.
- [x] `I1-14` Сохранять выбранный сервер только в `selector.default` настоящего JSON; не использовать DataStore или `experimental.cache_file`.
- [x] `I1-15` Анализировать raw JSON: показывать существующие selector-группы без скрытой нормализации; managed `zapret-proxy` создавать только после явного выбора пользователя.
- [x] `I1-16` Хранить профили только в app-private storage и выключить Android Auto Backup для файлов с credentials.

### Тесты и Gate 1

- [x] Unit: JSON round-trip и GUI edit сохраняют неизвестные extended-поля.
- [x] Unit: сбой записи оставляет старый профиль читаемым; backup восстанавливается.
- [x] Unit: malformed JSON не изменяет существующий профиль.
- [x] Unit: index не содержит DNS/outbound/route objects или credentials.
- [x] Unit + native instrumented: managed single/multi-server builders проходят `CheckConfig()` и имеют валидные ссылки selector → server tags.
- [x] Unit: смена `selector.default` не изменяет остальные outbounds, DNS, routes и unknown fields.
- [x] Instrumented: file picker и clipboard import работают на API 26 и современной ОС.
- [x] Gate: пользователь может импортировать JSON, проверить, изменить, перезапустить приложение и получить тот же профиль.

## Этап 2 — минимальный рабочий VPN

Цель: один профиль и выбранное приложение реально проходят через один TUN/libbox.

### Per-app scope

- [x] `I2-01` Реализовать глобальный include allowlist в DataStore.
- [x] `I2-02` Создать полноэкранный picker приложений: поиск, установленные пользовательские приложения, скрытые системные.
- [x] `I2-03` Добавить встроенные suggestions для Instagram, X/Twitter, YouTube, Telegram Stable/Beta/Direct, Telegram X, ZaStoGram и распространённых форков, WhatsApp, Discord, Signal, ChatGPT, Claude, Gemini, Perplexity, Copilot, DeepSeek, Grok, Suno, Spotify, Notion, Chromium/Ultimatum и популярных браузеров; без TikTok. Только при первой инициализации выбирать все установленные recommendations, включая отключённые предустановленные Gemini/YouTube/YouTube Music, но не обычные системные службы. Установленные Android browser handlers и обработчики `tg://` также выбирать без бесконечного списка package вручную. После инициализации обновления никогда автоматически не меняют include/exclude-список пользователя.
- [x] `I2-04` Не запускать VPN при пустой effective allowlist или ошибке `addAllowedApplication()`; исчезнувшие package пропускать до Builder, писать в bounded diagnostic log и продолжать при наличии хотя бы одного установленного приложения. Отключённый, но установленный package разрешать выбрать и передавать Builder: после включения приложения сохранённая allowlist действует без повторной настройки.
- [x] `I2-05` Внутренне включать package Zapret KVN для health-check, не показывая его как пользовательский выбор.

### Service и libbox

- [x] `I2-06` Создать один foreground `VpnService`, `VpnController` и закрытый набор состояний подключения.
- [x] `I2-07` Реализовать один service-lock, generation token и идемпотентный `stop()`.
- [x] `I2-08` Интегрировать libbox в том же Android process; вызвать `Libbox.SetMemoryLimit(false)`.
- [x] `I2-09` Настроить release `Debug=false`, managed `log.level=warn`, `LogMaxLines=256`, без runtime-лога на диск.
- [x] `I2-10` Реализовать минимальный platform adapter: TUN PFD, `protect(fd)`, package owner lookup и platform callbacks.
- [x] `I2-11` Перед запуском очищать package include/exclude только в runtime-копии JSON и применять одну глобальную allowlist.
- [x] `I2-12` Валидировать один TUN, IPv4+IPv6 full routes, `auto_route`, package conflicts и запрещённые bind/mark/netns-поля.
- [x] `I2-13` Выполнять `CheckConfig()` до `VpnService.Builder.establish()`.
- [x] `I2-14` Реализовать foreground notification только с состоянием и действиями открыть/остановить; без live speed.
- [x] `I2-15` При revoke/error/stop закрывать core, PFD, callbacks, streams и foreground notification ровно один раз.
- [x] `I2-16` Получать selector-группы/текущий server через libbox и переключать активный server вызовом `CommandClient.SelectOutbound()` без restart TUN/core.
- [x] `I2-17` Перед switch собрать JSON с новым `selector.default`, выполнить `CheckConfig()` и атомарно сохранить; при ошибке runtime switch сделать один контролируемый restart.
- [x] `I2-18` Для managed selector включить `interrupt_exist_connections=true`; проверить, что закрываются только proxy-соединения selector, а `direct` и приложения вне TUN не затрагиваются.
- [x] `I2-18A` Поверх exact core commit применять один проверяемый Android data-plane patch: vanilla WireGuard и AmneziaWG используют раздельные pinned движки metacubex, общий protected batch-1 ClientBind, roaming lock до peers и порядок `device → IPC → TUN Up`; публиковать версии модулей и patch SHA-256, а checkout возвращать чистым. Physical A/B остаётся в `P16`.

### Тесты и Gate 2

- [x] Instrumented: выбранное приложение видно в TUN; контрольный невыбранный UID не виден и работает напрямую.
- [x] Instrumented: проверены IPv4, IPv6, TCP, UDP и реальный Hysteria2/QUIC transport.
- [x] Instrumented: `protect(fd) == false`, revoke и ошибка после `establish()` приводят к полному stop.
- [x] Instrumented: ноль/два TUN, partial routes и конфликт package list отклоняются до подключения.
- [ ] Instrumented: server switch меняет внешний IP без пересоздания TUN; после restart остаётся выбранный server.
- [x] Leak test: после 20 connect/stop нет роста PFD/threads/libbox instances; TUN, adapters и callbacks закрываются в каждом цикле.
- [x] Gate: минимальный JSON-профиль подключается на API 26 и современной ОС, а невыбранный трафик не создаёт per-packet работу приложения.

## Этап 3 — DNS, bootstrap и сетевой lifecycle

Цель: соединение либо полностью работает, либо полностью закрывается без сломанного DNS Android.

- [x] `I3-01` Реализовать `DefaultNetworkMonitor` для underlying non-VPN network без polling.
- [x] `I3-02` Реализовать bootstrap resolver: `DnsResolver` на API 29+, `Network.getAllByName()` на API 26–28.
- [x] `I3-03` Реализовать маленький LKG cache адреса proxy: fresh 24 часа, аварийный срок до 7 дней, исходное имя сохранять для TLS/Reality SNI.
- [x] `I3-04` Детектировать Private DNS off/automatic/strict через `LinkProperties`; системную настройку никогда не менять.
- [x] `I3-05` Реализовать четыре режима GUI: Автоматически, DNS Android, Защищённый через VPN, Из JSON.
- [x] `I3-05A` Добавить включённый по умолчанию «Только IPv4 через VPN»: `ipv4_only` применяется к generated DNS rules proxy-доменов в Secure/DNS Android и managed-этапах Auto, не меняя первую попытку с DNS профиля, direct/LAN, TUN IPv6 и режим «Из JSON»; пользователь может вернуть dual-stack, но WireGuard требует настоящий внутренний IPv6-адрес.
- [x] `I3-05B` Добавить одну глобальную редактируемую пару DNS override для managed-режимов, по умолчанию включённую как `ntc.party → 130.255.77.28`: `hosts` и точное правило стоят после `reject`, но до resolver rules; «Из JSON», routing, TLS/SNI и встроенный DoH не меняются, diagnostic не раскрывает пару.
- [x] `I3-05C` В Auto использовать конечную цепочку `DNS профиля → DNS Android → DoH`: переходить только после typed DNS health failure, полностью закрывать предыдущие core/TUN/callbacks, не переключаться на ошибке JSON/proxy/HTTPS и не давать fallback явным режимам.
- [x] `I3-06` Создать `RuntimeConfigBuilder`, который добавляет только `zapret-*` overlays и не меняет сохранённый JSON.
- [x] `I3-07` В явном Secure и последнем Auto-этапе использовать реальные IP, `reverse_mapping`, cache 4096 и пять DoH (Quad9, Google, OpenDNS, Cloudflare, Yandex) через `fallback/parallel`; exact `sequential` не достигает резерва при зависшем основном DoH; FakeIP не создавать.
- [x] `I3-08` Брать внутренний DNS через `TunOptions.GetDNSServerAddress()` и передавать его в `VpnService.Builder.addDnsServer()`.
- [x] `I3-09` Перехватывать стандартный DNS правилом port 53 / `hijack-dns`; не обещать перехват DoT, встроенного DoH и mDNS.
- [x] `I3-10` При strict Private DNS блокировать managed Auto/Secure до `establish()`; DNS Android разрешать только при active+validated strict, иначе fail-close без plaintext fallback; «Из JSON» не переписывать.
- [x] `I3-11` Реализовать последовательный health pipeline: proxy socket, DNS через TUN, HTTPS Cloudflare, затем Google и OpenDNS только после ошибок предыдущих endpoints.
- [x] `I3-12` Показывать «Подключено» только после всех проверок; любая ошибка закрывает TUN.
- [x] `I3-13` При смене сети или DNS/captive policy state обновлять underlying Network, сбрасывать transport и выполнять один контролируемый restart с debounce/generation token.
- [x] `I3-14` Не добавлять периодический health-check, бесконечный retry или plaintext DNS fallback.
- [x] `I3-15` Кнопку «Очистить DNS-кэш» реализовать контролируемым restart core, честно не обещая очистить Android resolver cache.

`I3-13`: baseline текущей сессии неизменяем. Transient callback `A → B → A`
отменяет pending restart, а итоговый policy key повторно проверяется после debounce.

### Тесты и Gate 3

- [x] Unit/core: четыре DNS fixtures проходят exact CLI; JVM-тест подтверждает managed `fallback/parallel` без FakeIP.
- [x] Unit: Auto выбирает DNS профиля, Android и DoH в фиксированном порядке, остаётся bounded и не скрывает non-DNS ошибки; явные режимы имеют ровно одну попытку.
- [x] Core: воспроизводимый тест внутри exact pinned package проверяет success, transport error, hang с общим context и `NXDOMAIN`/`SERVFAIL`/`REFUSED` без ошибочного fallback.
- [x] Instrumented на AVD API 28/29/36: Private DNS off/automatic/strict working/strict broken; managed Auto/Secure блокируются до TUN, а поломка strict Android DNS во время активной сессии event-driven закрывает TUN без plaintext fallback.
- [x] Instrumented на AVD: реальные Wi-Fi, mobile, Wi-Fi ↔ mobile и IPv6; один контролируемый restart на каждую смену; captive-portal fail-close покрыт детерминированной fault injection до TUN.
- [ ] Physical lab: настоящий captive portal и IPv6-only/NAT64 на целевых устройствах/сетях.
- [x] Instrumented: блокировка системного resolver с fresh/stale/no LKG; реальный managed DoH/proxy success, отказ всех managed DoH при недоступном proxy и полная очистка lifecycle.
- [ ] Physical lab: повторить blocked system DNS/LKG и DoH failure на реальной Wi-Fi/mobile сети, а не только через детерминированную fault injection.
- [x] Instrumented: мёртвый внутренний DNS после TUN закрывает core/PFD; Android немедленно получает обычную non-VPN сеть и снова разрешает DNS.
- [ ] Gate: вся матрица DNS ADR проходит, активный TUN никогда не остаётся с неработающим DNS.

Physical Test 17 на Pixel 9 Pro XL (API 37) подтвердил ответ WireGuard handshake и
чистый fail-close без утечки PFD/core/callback. Он одновременно выявил три независимые
ошибки после handshake: `ipv4_only` не применялся к proxy-доменам режима DNS Android;
`fallback/sequential` не мог перейти к резервному DoH после зависания первого; одиночный
Google 204 probe давал ложный `VPN-200`, а IPv6-попытка WireGuard-профиля без внутреннего
IPv6 завершалась `missing IPv6 local address`. Test 18 подтвердил WireGuard handshake,
но реальные логи затем показали блокировку managed DoH и необходимость предпочитать
уже выбранный DNS профиля. Цепочка Auto исправлена; physical подтверждение Test 21
остаётся частью открытого Gate 3.

## Этап 4 — маршрутизация и rule-set

Цель: один понятный UI управляет destination routing внутри настоящего JSON.

- [x] `I4-01` Реализовать экран из карточек «Область VPN», «Правило трафика» и читаемый «Итог».
- [x] `I4-02` Реализовать presets: Всё через VPN, Обход LAN, Только выбранные сайты, Россия напрямую, Россия через VPN, Пользовательский.
- [x] `I4-03` Реализовать действия правила: Через VPN, Напрямую, Блокировать.
- [x] `I4-04` Соблюдать порядок exact reject → direct exceptions → proxy rules → final.
- [x] `I4-05` Не генерировать package route rules для обычной allowlist: Android уже выполнил это отсечение.
- [x] `I4-06` Поставлять локальные binary `.srs` внутри APK с manifest, version, license и SHA-256.
- [x] `I4-07` Реализовать согласованное domain block: DNS `reject` + route `reject`; IP/CIDR block: route `reject`.
- [x] `I4-08` Не включать global sniff, legacy GeoIP/Geosite, remote managed rule-set или Android route exclusions.
- [x] `I4-09` Реализовать advanced include/exclude mode с явным предупреждением; пустой exclude-list блокирует запуск.
- [x] `I4-10` Дать GUI-редактор основных domain/IP/rule-set/outbound полей, редкое оставить raw JSON.
- [x] `I4-11` После каждой GUI-операции показывать effective summary и diff управляемых `zapret-*` объектов.
- [x] `I4-12` Добавить отдельный пустой по умолчанию blocklist приложений: в include
  объединять его с Android TUN boundary, в exclude запрещать пересечение с direct,
  а в runtime-копии ставить первые DNS/route `package_name → reject`.

### Тесты и Gate 4

- [x] Core/fixture: `ru-rule-set.json` и `block-rule.json` проходят exact CLI.
- [x] Instrumented: RU/non-RU domain и IPv4/IPv6 во всех presets.
- [x] Instrumented: selected app получает proxy/direct/reject; unselected app всегда остаётся вне TUN.
- [x] Instrumented: приложение со встроенным DoH демонстрирует задокументированное ограничение domain-only block.
- [x] Performance: измерить cold start, lookup CPU/RAM и размер production `.srs`.
- [x] Gate: UI summary, effective JSON и реальный сетевой путь совпадают для каждого preset.

Gate 4 закрыт автоматизированно 22 июля 2026 года на AVD API 26 и 36. Все шесть presets прошли реальный TUN-путь через локальный SOCKS5 proxy/direct/reject для RU/non-RU domain и IPv4/IPv6; отдельный UID-тест подтвердил, что невыбранное приложение остаётся вне TUN. Стандартный DNS block и HTTPS DoH→numeric path воспроизводят документированную границу domain-only block. UI summary, сохранённый JSON и фактический outbound совпали для каждого preset.

Exact pinned core загрузил два `.srs` за 1 114 мкс при 758 624 байтах allocations; lookup benchmark — 329 нс/op, 1 104 B/op, 2 allocs/op. Суммарный размер assets — 50 089 байт. В полном debug-прогоне API 36: extraction 2 мс, cold connect 41 мс, 40 реальных flows — 100 мс CPU и +356 КиБ PSS; API 26: 5 мс, 63 мс, 300 мс CPU и без роста PSS. Это закрывает автоматизированный performance-пункт, но не release-gate энергии на физических устройствах.

## Этап 5 — ссылки, QR и подписки

Цель: основной пользователь импортирует конфигурацию без знания JSON.

- [x] `I5-01` Реализовать единый `ImportParser` без отдельной внутренней модели маршрутизации.
- [x] `I5-02` Поддержать обычный sing-box JSON и subscription, возвращающий JSON.
- [x] `I5-03` Поддержать URI-листы минимум VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и TUIC; каждый результат преобразовывать сразу в sing-box JSON.
- [x] `I5-04` Поддержать plain/base64 subscription со списком известных URI; неизвестную строку не угадывать.
- [x] `I5-05` Добавить URL import и ручное обновление subscription groups с preview до сохранения.
- [x] `I5-06` Добавить QR scanner только по действию пользователя; камера запрашивается в момент открытия.
- [x] `I5-07` Добавить импорт из буфера только после нажатия и импорт файла через системный picker.
- [x] `I5-08` После успешного импорта не подключаться автоматически: предложить выбрать приложения, затем явную кнопку подключения.
- [x] `I5-09` Перед запуском сканировать импортированный JSON на `urltest`, NTP, remote rule-set, external Clash controller, verbose log и explicit keepalive.
- [x] `I5-10` Показывать одно предупреждение о фоновой/внешней активности; пользовательский JSON скрыто не очищать.
- [x] `I5-11` Маскировать UUID, password, token, subscription query и URL credentials во всех preview/log/error.
- [x] `I5-12` Не добавлять фоновое обновление подписок или ядра.
- [x] `I5-13` При subscription refresh сохранять выбранный server tag, если он остался; иначе выбрать первый доступный и явно сообщить пользователю.
- [x] `I5-14` Активный профиль после ручного refresh перезапускать только после подтверждения пользователя; не менять работающий server скрыто.
- [x] `I5-15` Для одиночного URI предлагать «Новый профиль» или «Добавить в существующую managed-группу»; никогда не сливать профили автоматически.

### Отложенные форматы импорта

- [x] `F-IMPORT-WG` Реализовать строгий импорт WireGuard/AmneziaWG 2.0 native `.conf` напрямую в sing-box `endpoints`, native/core tests и открытие через Android `ACTION_VIEW`; unknown-field drop запрещён.

- [x] `F-IMPORT-01` Проверить спрос и корректную семантику Clash YAML. Реальный спрос и риск silent field loss подтверждены; точный core parser не экспортирован в libbox, поэтому второй YAML-конвертер не входит в MVP. Решение и gate зафиксированы в [политике форматов](IMPORT_FORMATS.md#f-import-01--clash-yaml).
- [x] `F-IMPORT-02A` Сверить URI inventory точного ядра. Единственный отсутствующий URI из pinned parser — Hysteria v1; это кандидат, а не реализованная поддержка.
- [ ] `F-IMPORT-02` Собрать и проанализировать реальные неподдержанные ссылки: минимум 3 обезличенных Hysteria v1 URI и образцы пробелов текущих VLESS/SS/TUIC/Hysteria2 parser; затем выбрать по данным.
- [ ] `F-IMPORT-03` Добавлять Clash YAML только после полного gate из [политики форматов](IMPORT_FORMATS.md#f-import-01--clash-yaml); не импортировать Clash DNS/routes/groups.

Здесь `[x]` у исследовательского пункта означает завершённый аудит, а не
наличие формата в APK.

### Тесты и Gate 5

- [x] Golden tests для каждого URI, IPv6 host, percent/base64 encoding, transport, TLS, Reality и пустых/битых полей.
- [x] Fuzz/property tests: parser не падает и не зависает на произвольном вводе.
- [x] Integration: subscription update атомарен; битый ответ не меняет старые профили.
- [ ] Security: секреты отсутствуют в Logcat, crash message, notification и redacted export.
- [ ] Gate: путь URL/QR/clipboard/file → preview → JSON → выбор приложений → connect работает без raw editor.

Открытая часть Gate 5 требует реальной камеры/HTTPS subscription endpoint и физического
устройства. Redacted export из `I6-15`–`I6-16` теперь проверен instrumented-тестом:
профиль, package list, endpoint, UUID/password/token и внешний IP в отчёт не попадают.
Полный security-gate всё ещё требует проверки Logcat/crash/notification на физическом
устройстве. Автотестами также подтверждены parser/preview, native `CheckConfig`,
атомарный malformed refresh и общая redaction ошибок/debug core log.

## Этап 6 — продуктовый UI и диагностика

Цель: компактный Material You клиент, пригодный для ежедневного использования.

### Главная и состояние

- [x] `I6-01` Реализовать компактную connection card: состояние, профиль, сервер, IP, ping и время.
- [x] `I6-01A` По нажатию на сервер открывать bottom sheet selector-группы с именем, протоколом, endpoint, session ping и отметкой текущего выбора.
- [x] `I6-02` Использовать внутренний libbox traffic manager без external controller/listener.
- [x] `I6-03` Подписывать `CommandStatus` с интервалом 1 секунда только пока главная в lifecycle `STARTED`.
- [x] `I6-04` Хранить 60 download/upload значений в кольцевом массиве текущей сессии.
- [x] `I6-05` Никогда не включать `CommandConnections`; bounded `CommandLog` открывать на время connect health-check и затем только на экране диагностики.
- [x] `I6-06` IP запрашивать один раз после connect, ping — при connect и вручную.
- [x] `I6-07` Уведомление не обновлять каждую секунду и не рисовать там скорость.

Главная и диагностика проверены 22 июля 2026 года: 85/85 JVM и текущие 67/67
instrumented на AVD API 36; базовая матрица 66/66 также пройдена на API 26/29.
Постоянным остаётся только event-driven `CommandGroup`;
`CommandStatus` создаётся при видимой главной, а отдельный `CommandLog` — на время
connect health-check и затем только при видимой диагностике. Оба непостоянных клиента
физически закрываются вне своего узкого lifecycle. `CommandConnections` отсутствует.

### Остальные экраны

- [x] `I6-08` Завершить Профили: группы, source, last update, add actions и ошибки.
- [x] `I6-09` Завершить Маршрутизацию: две карточки, rules, app picker, advanced JSON.
- [x] `I6-10` Завершить Настройки: тема, DNS, Stable/Beta, Диагностика, Сообщество, О приложении.
- [x] `I6-10A` Добавить отдельный экран «Скрытие VPN» и rootless runtime-модуль без нового process/thread/polling: localhost endpoints закрыты по умолчанию, stored JSON не меняется.
- [ ] `I6-10B` Завершить physical gate MTU 1500 на IPv4/IPv6/NAT64/QUIC, операторах, OEM и энергии; initial device-test позволил включить default с явным откатом к profile/core.
- [x] `I6-10C` Повторно проверить на физическом Android WireGuard/AWG после исправления:
  stable 0.2.3 доказал внешний TUN 9000 при endpoint 1280; stable `v0.2.4` задаёт
  `min(1500, endpoint MTU)`, то есть 1280 для проверенного профиля. Владелец установил
  production APK на то же реальное устройство и подтвердил работу WireGuard и остальных
  использованных профилей. Полная OEM/оператор/IPv6 матрица остаётся в `I6-10B`/`R8`.
- [x] `I6-11` Разместить Telegram-ссылки только в «Настройки → Сообщество».
- [x] `I6-12` Добавить accessibility labels, нормальный back navigation, состояния loading/empty/error и крупные touch targets.
- [x] `I6-13` Ограничить анимации стандартными Compose/Material; не добавлять тяжёлый dashboard.

Остальные экраны завершены 22 июля 2026 года без нового слоя навигации, DI или
зависимостей. Профили группируются только по UI metadata и показывают источник и
`updated_at`; сетевой JSON остаётся единственным источником истины. Маршрутизация
имеет две главные карточки: Android per-app scope и destination routing, а кнопка
«Расширенный JSON» открывает активный настоящий профиль. Stable/Beta хранится как
UI-настройка и не запускает фоновые обновления; updater остаётся этапом 7.

Три Telegram URL присутствуют в production-коде только внутри экрана
«Настройки → Сообщество». Подэкраны настроек, picker приложений и JSON-редактор
обрабатывают системный Back; интерактивные строки имеют минимум 56–72 dp и
понятные semantics. Собственных бесконечных/тяжёлых анимаций нет — используются
только штатные состояния Compose/Material. Проверено в общей матрице: 85/85 JVM,
67/67 instrumented tests на API 36 и базовые 66/66 на API 26/29. Основные кнопки соединения имеют
явный минимум 48 dp; этот инвариант и подписи нижней навигации проверяются smoke-тестом.

### Диагностика

- [x] `I6-14` Показывать короткий тип ошибки и раскрываемые последние bounded log lines.
- [x] `I6-14A` Записывать bounded event timeline каждой попытки подключения/restart: общая длительность, статусы этапов и самый долгий этап; только monotonic event timestamps, без ticker/polling.
- [x] `I6-14B` Атомарно хранить только последний redacted uncaught Kotlin/Java crash в `noBackupFilesDir`; runtime/core traffic logs на диск не писать. На API 30+ читать одну системную process-exit запись для native crash/ANR без копирования тяжёлого trace.
- [x] `I6-14C` Заменить head/tail startup-окно на priority ring: handshake/endpoint/TUN/error сохраняются, повторы схлопываются, один callback принимает не более 48 записей, а received/coalesced/dropped counters экспортируются.
- [x] `I6-15` Создавать redacted diagnostic JSON только по действию пользователя.
- [x] `I6-16` Экспортировать через Android Sharesheet/FileProvider и удалять временный файл при следующем запуске.
- [x] `I6-17` Включать core/app version, Android/API, network state, Private DNS mode и effective overlay без секретов.

Диагностика не является отдельным Gradle-модулем и не добавляет постоянного фонового сборщика. `CommandLog` подписывается
отдельным клиентом на время connect health-check и при `Activity STARTED` с открытым
экраном. В памяти остаются три последние попытки, до 48 приоритетных startup core-записей
на каждую и отдельные 80 общих записей; повторы схлопываются, шум вытесняется раньше
handshake/TUN/errors, а counters честно показывают отброшенное. Клиент закрывается идемпотентно после успешной
проверки, вместе с экраном или service. Runtime-лог на диск не пишется. Effective overlay — структурная
сводка managed `zapret-*`: режим DNS, наличие dual-stack TUN, типы managed DNS,
количество правил/actions, локальные rule-set и bounded hardening-state без endpoint,
match values или секретов.

Вся suspend-цепочка connect/restart ограничена одним 30-секундным deadline. Timeout
завершается fail-close с `VPN-120` и не создаёт периодический watchdog или retry-loop.

Отчёт версии 3 создаётся только кнопкой, содержит app/core revision+patch SHA-256, Android/API/ABI,
VPN/non-VPN network state, Private DNS, последнюю классифицированную ошибку, безопасный
overlay, bounded connection timeline, runtime resource/log counters, последний redacted app crash,
одну Android process-exit запись и bounded log lines. Он не содержит raw JSON, имя профиля, packages, внешний IP
или credentials. Единственный временный файл находится в `cache/diagnostics/`, доступен
через `FileProvider` с `exported=false` и read grant Sharesheet, а `AppContainer` удаляет
его при следующем запуске.

Полевой отчёт v1 от 22 июля 2026 года подтвердил живую сессию на Android 17/API 37:
Wi-Fi validated, managed DNS `Automatic`, Private DNS off, dual-stack TUN и VLESS/Reality без
зафиксированной ошибки. В захваченном steady-state трафике DNS lookup занимал 1–3 мс,
а выход VLESS на TLS/Reality — около 177–196 мс. Отчёт был создан через 83,7 с после
`connected_at`, а первая log line была получена через 78,1 с, поэтом v1 не доказывает
длительность первичного connect и не закрывает причину задержки. Для этого нужна одна
новая репродукция на APK с отчётом v2 и его connection timeline.

### Тесты и Gate 6

- [x] Compose UI tests для всех состояний главной и четырёх вкладок.
- [x] Lifecycle test: после ухода с главной status ticker закрыт; после ухода из диагностики log stream закрыт.
- [x] Rotation/process recreation не создаёт второй service/core и не теряет фактическое состояние VPN.
- [x] Accessibility smoke test на светлой/тёмной теме и API 26/31+.
- [x] Gate: полный путь «установил → импортировал → выбрал → подключил → диагностировал» не требует JSON.

Автоматизированный Gate 6 закрыт 22 июля 2026 года на AVD API 26 и API 36. UI-тесты
покрывают отсутствие профиля/приложений и состояния `Stopped`, `Starting`, `Connected`,
`Stopping`, `Error`, а также основные экраны всех четырёх вкладок. В светлой и тёмной
темах проверены accessibility labels нижней навигации и touch targets не меньше 48 dp.

Полный сценарий использует обычную Shadowsocks-ссылку из буфера: создаёт managed JSON
через preview, выбирает установленное приложение, поднимает настоящий Android TUN и
libbox, переживает rotation с тем же `connectedAt` и единственным core, затем открывает
диагностику. Сетевой health-result в тесте детерминирован, поэтому сценарий не зависит
от внешнего VPN-сервера, но profile parser, native `CheckConfig`, service, TUN и libbox
не подменяются. Raw JSON editor нигде не открывается.

Отдельный `scripts/verify-process-recreation.sh` проверяет hard process death. Android
в этом случае закономерно уничтожает service/TUN/core; новый процесс обязан показать
фактическое `Stopped` с нулевыми ресурсами, а следующее подключение — создать ровно один
service/core/TUN. Probe receiver существует только в debug source set, защищён системным
`android.permission.DUMP`, а CI дополнительно запрещает его присутствие в release APK.

## Этап 7 — обновление APK и выпуск

Цель: безопасный GitHub Release без динамического ядра и мусора.

- [x] `I7-01` Проверять GitHub Releases один раз при запуске и вручную: Stable принимает только обычный release, Beta — любой prerelease; при обновлении показывать release notes.
- [x] `I7-02` Скачать APK во внутренний cache, проверить опубликованный SHA-256 и передать системному installer.
- [x] `I7-03` Проверять package name и совместимость подписи; не обещать silent install.
- [x] `I7-04` Удалять скачанные/незавершённые APK после install handoff, отмены, ошибки и при следующем запуске; diagnostic temp-файлы — при следующем запуске.
- [x] `I7-05` Никогда не скачивать core отдельно: libbox обновляется только вместе с APK.
- [x] `I7-06` Настроить release workflow: exact core SHA → CLI/AAR → tests → APK → checksum → metadata.
- [ ] `I7-07` Хранить signing secrets только в GitHub Secrets; сделать зашифрованную офлайн-копию ключа и инструкции восстановления.
- [x] `I7-08` Публиковать release notes с app version, core tag, full core SHA, ABI и checksum.
- [x] `I7-09` Проверить same-key upgrade с предыдущей версией и сохранение профилей/DataStore.
- [x] `I7-10` Публиковать отдельные `arm64-v8a`, `armeabi-v7a`, `x86_64` APK без чужих native-библиотек; updater выбирает первый совместимый ABI устройства.
- [x] `I7-11` Вынести updater в направленный `app-updater` library-модуль. После retryable сетевой ошибки один раз повторять текущую проверку или загрузку через временный VPN runtime overlay, ограниченный package приложения и GitHub-хостами; после операции восстанавливать предыдущее состояние VPN.

Updater проверяет выбранный канал один раз на запуск процесса и по кнопке, не имеет
scheduler/service и не выполняет периодический polling. Stable использует самый новый
не-prerelease, Beta — самый новый prerelease независимо от имени тега. Release считается
валидным при единственном поддерживаемом metadata-файле, полной матрице одно-ABI APK и отдельных
`APK.sha256`; metadata, checksum и GitHub asset digest обязаны совпасть. Переходный schema-1
`release-metadata.json` указывает на arm64 APK для уже установленных старых клиентов. Разрешены только
HTTPS-хосты GitHub с ограниченными redirect/размером/таймаутом.

`app-updater` не зависит от UI, профилей, libbox или VPN lifecycle. Интеграционный callback
в `app` включается только после сетевой ошибки/HTTP 403, 429, 451 или 5xx. Если VPN уже
работает, сервис выполняет контролируемый restart с временным `package_name + domain_suffix`
правилом; если VPN был выключен и consent уже выдан, временно запускается активный профиль.
После запроса исходное состояние восстанавливается в `finally`, включая отмену. Правило не
записывается в профиль и не влияет на GitHub-трафик других приложений. Без consent или
активного профиля updater показывает обычную ошибку с причиной недоступности VPN-повтора.

После загрузки Android читает сам APK: package должен совпасть с установленным, versionCode
должен быть строго больше, versionName/versionCode — совпасть с metadata, minSdk — подходить,
а текущий сертификат — входить в доказанную signing history нового APK. Для multi-signer
требуется точное равенство набора. Только после этих проверок non-exported FileProvider
передаёт content URI штатному installer; silent install не используется. Частичный, битый,
отменённый или оставшийся после process restart APK удаляется из `cache/updates`.

Локальный stable publisher принимает строгий tag `vMAJOR.MINOR.PATCH`, выводит
детерминированный монотонный versionCode, собирает exact pinned core, тестирует,
подписывает отдельные arm64-v8a/armeabi-v7a/x86_64 APK постоянным owner-only ключом,
проверяет `apksigner`/manifest/единственность ABI и публикует APK, SHA-256, metadata и
notes один раз без замены уже опубликованных assets. После публикации он запускает
GitHub Actions как независимую фоновую проверку и не ждёт её. Workflow не имеет
production key и ничего не публикует.

Постоянный ключ создан, проверен и хранится локально вне репозитория; публичный
fingerprint закреплён в `release.properties`. Локальный publisher, фоновый verification
workflow и [инструкция](SIGNING.md) готовы. `I7-07` остаётся открытым только до
подтверждения двух отдельных зашифрованных офлайн-копий ключа.

### Gate 7

- [x] Неверный checksum, другая подпись, прерванная загрузка и downgrade обрабатываются безопасно.
- [x] После update/cancel/restart cache не содержит старых APK.
- [x] Release APK воспроизводимо содержит указанный core revision.
- [x] GitHub Release содержит APK, SHA-256 и достаточную информацию для независимой проверки.

Автоматизированный Gate 7 пройден на AVD API 26/36. `scripts/verify-same-key-upgrade.sh`
установил build 701001, записал настоящий профиль/DataStore/allowlist, обновил тем же ключом
до 701002 и подтвердил сохранность. Затем Android отклонил downgrade и переподписанный
другим ключом APK как `VERSION_DOWNGRADE`/`UPDATE_INCOMPATIBLE`, не повредив данные.
Unit/instrumented тесты отдельно проверяют rotation lineage, multi-signer, package/version,
неверный checksum, прерванный `.part`, installer cancel и startup cleanup. Публичные
`v0.2.1-beta.30` и stable `v0.2.1` содержат три ABI, отдельные SHA-256 и обе
metadata-схемы.

## Этап 8 — пострелизная физическая матрица

Цель: постепенно расширять доказанную совместимость на реальных Android-устройствах
и сетях. По решению владельца эта матрица выполняется пользователями после публикации
MVP и не блокирует уже выпущенный stable; открытая галочка означает непроверенное
сочетание, а не известную поломку.

### Устройства и сеть

- [ ] `R8-01` Реальное слабое устройство API 26/28.
- [x] `R8-02` Android 10/API 29 AVD: `DnsResolver`, Private DNS boundary и 66/66 tests.
- [x] `R8-03` Современный Android API 36 AVD: 66/66 tests; физическое устройство остаётся в energy/OEM gate.
- [ ] `R8-04` Wi-Fi, cellular, IPv4, IPv6/NAT64, captive portal и смена сети.
- [x] `R8-05` Always-on/Lockdown читается через public API 29+, объясняется до TUN и не включается приложением.

### Корректность

- [ ] `R8-06` Повторить полную DNS matrix из DNS ADR.
- [ ] `R8-07` Повторить routing matrix из Routing ADR.
- [ ] `R8-08` Проверить каждый заявленный outbound отдельно на connect, UDP/QUIC и `protect(fd)` loop.
- [ ] `R8-09` Проверить shared UID, удалённый package, пустые include/exclude и revoke.
- [x] `R8-10` 100 connect/stop и 50 Wi-Fi/mobile transitions пройдены на API 29/36 AVD без deadlock, duplicate restart и утечек.

Протокол и честная граница automated/physical прогона зафиксированы в
[`GATE8_RESULTS.md`](GATE8_RESULTS.md). `R8-01`, полный `R8-04`, `R8-06…R8-09`
остаются открытыми до реальных устройств/сетей/серверов.

### CPU, память и батарея

- [ ] `R8-11` Пять повторов: VPN off baseline и VPN idle с погашенным экраном. API 36 AVD 5/5 пройден; физическая energy-часть открыта.
- [ ] `R8-12` Невыбранное приложение передаёт фиксированный объём без пропорционального CPU/network роста Zapret KVN. На AVD 8 MiB дали median TUN=0; физический OEM-повтор открыт.
- [ ] `R8-13` Сравнить выбранный `direct` и `proxy`: CPU, throughput, RSS/PSS, GC и энергия. AVD CPU/throughput/RSS/PSS готовы; реальные proxy, GC trace и энергия открыты.
- [ ] `R8-14` Сравнить главную видимую/закрытую и diagnostics открыта/закрыта. Настоящие Compose-экраны AVD измерены; физическая энергия открыта.
- [ ] `R8-15` Измерить принятый managed `parallel` против контрольного `sequential` на уникальных именах. Test 17 уже доказал функциональную регрессию `sequential` при hang; AVD 5×12 готов, физическая энергия/cache burst открыты.
- [ ] `R8-16` Сравнить текущий `mixed` stack с `system`; AVD-разница ниже 5%, default не изменён; физическая матрица открыта.
- [ ] `R8-17` Сравнить default MTU 1500 и profile/core; initial physical test дал заметный выигрыш, но IPv6/NAT64/QUIC, оператор/OEM и энергия открыты.
- [ ] `R8-18` Сравнить `SetMemoryLimit(false)` с экспериментальным GC=10: AVD CPU/PSS/RSS/throughput ниже порога; Go GC count/pause, OOM и физическая энергия открыты.
- [x] `R8-19` Harness сохраняет System Trace, batterystats и 130 raw-файлов с SHA-256; manual CI загружает их artifact. Физические PowerMetric/ODPM входят в открытые R8-11…R8-18.
- [x] `R8-20` Порог 5% применяется агрегатором и проверяется negative/positive self-test в `verify-project.sh`.

Точный AVD-протокол и граница оставшейся физической проверки находятся в
[`GATE8_RESULTS.md`](GATE8_RESULTS.md). Harness дополнительно прошёл сокращённую матрицу
5 × 16 на API 26; это только проверка minSdk-совместимости, не energy evidence. Ни один
production default по AVD-данным не изменён.

### Security и release candidate

- [x] `R8-21` Secrets проверяются до export во внутреннем bounded-log, в redacted export,
  foreground notification и app-private temp/history после явного чтения clipboard; stale diagnostics/APK
  очищаются отдельными тестами. Уведомление принимает только закрытый enum состояний.
- [x] `R8-22` Managed runtime удаляет все listener/UI/secret поля `experimental.clash_api`,
  не меняя сохранённый JSON. Явный raw user JSON остаётся под контролем пользователя.
- [x] `R8-23` Exact merged-manifest audit проверяет permission allowlist, все exported-компоненты,
  signature-permission AndroidX, FileProvider, backup/cleartext/process/VPN contract и `Debug=false`.
- [x] `R8-24` Arm64 release проверяет APK/R8 mapping и exact native symbols; minified x86_64
  release на API 36 прошёл 5 cold starts (median 407 ms) без process crash.
- [x] `R8-25` Локальный minified RC прошёл clean install, same-key update,
  downgrade/signature rejection; production-signed stable `v0.2.4` установлен и
  запущен владельцем на реальном устройстве.

### Финальный gate

- [x] Нет failed fixture, unit или instrumented test: exact/compat fixtures 7/7,
  JVM 85/85, API 36 instrumented 67/67; API 26/29 baseline также пройден.
- [x] Нет core revision/ABI/signature mismatch. Exact core и ABI проверены release-аудитом,
  same-key/foreign-key/downgrade tests пройдены, production-signed stable установлен
  на реальном arm64-устройстве.
- [x] Нет app-owned WakeLock, alarm/job, скрытого polling или бесконечного reconnect.
  CI проверяет manifest и production sources; разрешён только lifecycle ticker видимой
  главной и одноразовый debounce смены сети.
- [x] Managed idle не создаёт периодический сетевой трафик: 5/5 окон дали 0 UID RX+TX
  и 0 status/log clients; физическая energy-матрица остаётся отдельным R8 gate.
- [x] Невыбранный трафик не проходит через TUN/libbox: 5 × 8 MiB дали 0 UID RX+TX
  Zapret KVN и median 0 TUN bytes (единственный интерфейсный шум — 96 bytes).
- [x] Все известные ограничения синхронно перечислены в UI, README и генерируемых
  release notes; это проверяет `scripts/verify-project.sh`.
- [x] Первый production GitHub Release опубликован; актуальный stable `v0.2.4`
  доступен с тремя ABI, SHA-256 и updater metadata.

Публичный MVP завершён. Открытые `R8-*` и physical/energy gates из
[`GATE8_RESULTS.md`](GATE8_RESULTS.md) являются пострелизной программой совместимости:
их закрываем только реальными пользовательскими evidence, но они не возвращают
статус уже принятого MVP в незавершённый.

## После MVP — только по измерениям и запросам пользователей

Эти пункты не входят в текущий final gate и не реализуются «на всякий случай». Галочка
означает не наличие кода, а прохождение указанного ниже отдельного activation gate.

- [x] `F-01` Whole-app block активирован 24 июля 2026 года по явному запросу пользователя:
  отдельный пустой по умолчанию список создаёт узкое `package_name → reject` внутри
  TUN и не превращает Android include/exclude boundary в третий глобальный режим.
- [ ] `F-02` Clash YAML import. Требуется полный gate из `IMPORT_FORMATS.md`: libbox binding
  либо отдельное обоснованное решение о parser, минимум 10 реальных образцов и доказанное
  отсутствие silent field loss. DNS/routes/groups из Clash автоматически не сливать.
- [ ] `F-03` Планшетный/navigation rail layout. Активировать только после возврата планшетов
  в product scope и UI/accessibility matrix на compact/medium/expanded width.
- [ ] `F-04` Выбор `mixed/system` stack в GUI. AVD-разница ниже 5%; оставить upstream
  `mixed`, пока физические API 26/современный Android не покажут устойчивый выигрыш или
  несовместимость хотя бы одного режима.
- [x] `F-05` MTU 1500 продвинут в default после initial device-test; экран сохраняет явный
  откат к profile/core. Полная физическая матрица IPv4, IPv6/NAT64, QUIC, PMTU, CPU
  и энергии остаётся в `I6-10B`. Это только внешний Android TUN: внутренний
  WireGuard/AWG endpoint без явного `mtu` получает Android-совместимое значение 1280;
  его physical gate отслеживается отдельно в `I6-10C`.
- [ ] `F-06` Patch owner/process lookup. Сначала P4 benchmark текущего core и отдельной
  экспериментальной сборки на реальных устройствах; требуются существенный повторяемый
  выигрыш, собственный patch hash и полный regression/release matrix.
- [ ] `F-07` FakeIP. Только явно экспериментальный режим с отдельной ADR: per-profile
  ranges/cache identity, очистка при stop/delete/network change и тесты, исключающие
  сохранение FakeIP вне TUN. Managed default остаётся без FakeIP.
- [ ] `F-08` Remote rule-set updater. Только после подтверждённого сценария, политики
  лицензий/version/SHA/rollback и явного ручного UX; не добавлять скрытый worker/polling и
  не объединять с APK updater.

## Текущее состояние

- [x] Архитектурный аудит завершён 22 июля 2026 года.
- [x] Exact CLI: 7/7 fixtures.
- [x] Compatibility CLI: 7/7 fixtures.
- [x] Go packages compile/tests pass; дополнительный audit test временно встраивается в exact pinned fallback package и проверяет success/error/hang/RCODE.
- [x] Markdown fences, локальные ссылки и SHA-256 fixtures проверены.
- [x] Android/Gradle-проект создан.
- [x] Debug и R8 release APK собраны.
- [x] Runtime smoke пройден на эмуляторах API 26 и API 36.
- [x] Повторный аудит I0-08–I0-14: exact origin/tag/SHA, рекурсивные fixtures, arm64 release и pinned CI actions.
- [x] JVM unit tests: 165/165 в `:app:testDebugUnitTest`; добавлены Auto DNS fallback,
  app-scoped health route, GitHub updater/signing, security, Always-on/Lockdown policy
  и полная блокировка приложений.
- [x] Android instrumented tests: текущие 67/67 пройдены на API 36; базовые 66/66 — на API 26/29, security delta 3/3 — на API 26.
- [x] Hard process recreation: `scripts/verify-process-recreation.sh` пройден на API 26/36; после смерти процесса ноль session/core/TUN/callback/client, после нового connect ровно один экземпляр.
- [x] Packaged `.srs`: exact CLI RU/non-RU domain/IPv4/IPv6, manifest/license/SHA, atomic repair; 50 089 байт, cold install 14 мс на AVD API 36.
- [x] Routing lookup/cold-start CPU/RAM measurements выполнены на AVD API 26/36 и exact core benchmark.
- [x] Test 18 собран локально из commit `e637391` для arm64-v8a, armeabi-v7a и x86_64 и опубликован отдельным GitHub prerelease с SHA-256/metadata.
- [x] Test 20 опубликован из commit `352fb31` с редактируемым DNS override; Auto DNS fallback в него ещё не входил.
- [x] Test 21 собран локально из commit `d78ad72` для arm64-v8a, armeabi-v7a и x86_64 и опубликован с новой цепочкой Auto DNS, SHA-256 и metadata; физическая проверка остаётся открытой.
- [x] Test 22 собран локально из commit `479a480` для arm64-v8a, armeabi-v7a и x86_64 и опубликован с Markdown-оформлением release notes, SHA-256 и metadata.
- [x] По отчётам Test 21 подтверждён Android WireGuard GRO/standard-bind сбой: handshake и первый DNS проходят, затем return data зависает. Runtime-only direct detour переводит endpoint без явного `detour` на `ClientBind`; health-route теперь обязателен во всех DNS-режимах, а профиль без DNS получает минимальный Android fallback. Exact pinned CLI и JVM regression tests проходят; физическая проверка остаётся открытой.
- [x] Beta 23 опубликована из commit `6be23f4`, но проверка живого GitHub API показала, что старый updater сортирует `test.*` выше более нового `beta.*`; release и tag сохранены для аудита.
- [x] Test 23 опубликован из commit `f602c19` для arm64-v8a, armeabi-v7a и x86_64 с versionCode `200123`, тем же debug signer, SHA-256 и updater metadata. Он дополнительно сортирует prerelease по `published_at` и находится первым для Test 21/22.
- [x] Диагностика Test 23 доказала более широкий дефект pinned Android WireGuard: handshake и первый UDP DNS успешны, но TCP SYN/data-plane зависает; «Из JSON» давал ложный успех через direct IPv6. Android data-plane заменён без второго TUN на pinned vanilla/AWG движки metacubex.
- [x] Test 24 опубликован из commit `ba395b4` для arm64-v8a, armeabi-v7a и x86_64 с versionCode `200124`, тем же debug signer, SHA-256, updater metadata и точными версиями vanilla WireGuard/AmneziaWG data-plane модулей; физическая проверка новой архитектуры остаётся открытой.
- [x] ADB evidence Test 24 подтвердил исправный Android TUN/VPN lifecycle и самозакрытие ровно через 12 секунд в добавленном `wireguard_data_plane`. Этот gate проверял доступность трёх публичных HTTPS-сайтов, а не handshake, дублировал общий health pipeline и скрывал core-log; он удалён. Общая HTTPS-проба теперь получает app-scoped TLS sniff и обязательный route в selected outbound, bounded core-log открывается до первой сетевой проверки.
- [x] Test 25 опубликован из commit `a1ee9cb` для arm64-v8a, armeabi-v7a и x86_64 с versionCode `200125`, прежним debug signer, новым patch SHA-256, per-APK checksums и updater metadata; Git tag указывает точно на проверенный app commit.
- [x] Evidence Test 25 исключил MTU как самостоятельную причину видеозависания: одинаковое поведение воспроизводится при внешнем TUN `1500` и profile/core `9000`. Найден общий для raw/WireGuard/VLESS runtime-дефект: exact core без `log.level` выбирает `trace`; теперь runtime всех профилей ограничен `warn` (или более строгим сохранённым уровнем), `log.output` удаляется, а профиль на диске не меняется.
- [x] Test 26 опубликован из commit `9103ed1` для arm64-v8a, armeabi-v7a и x86_64 с versionCode `200126`, прежним debug signer, per-APK checksums и updater metadata. Он изолированно проверяет новую runtime logging policy при неизменных core и WireGuard data-plane.
- [x] IPv4-only userspace WireGuard распознаётся по внутренним endpoint addresses.
  Connect-time HTTPS health-check выбирает IPv4 через resolver VPN `Network`,
  подключается к числовому адресу с исходными TLS SNI/certificate hostname/HTTP
  Host и возвращает `VPN-201`, если требуемого семейства нет. Сохранённый JSON не
  переписывается; неиспользуемые DNS servers только явно предупреждаются.
- [x] Beta `v0.2.4-beta.1` опубликована из commit `4dff6b3` с production-подписью
  для arm64-v8a, armeabi-v7a и x86_64. Runtime userspace WireGuard теперь задаёт
  внешний Android TUN `min(1500, endpoint MTU)` во всех DNS-режимах; exact fixture,
  JVM, lint и release-candidate проверки проходят. Физическое подтверждение остаётся
  открытым пунктом `I6-10C`.
- [x] Stable `v0.2.4` опубликован из commit `ec6aad4` с versionCode `200499`,
  production-подписью и отдельными arm64-v8a, armeabi-v7a и x86_64 APK.
  GitHub Release содержит SHA-256 и schema 1/2 metadata; tag указывает на exact
  локально проверенный source commit.
- [ ] Idle CPU/battery проверены на физических устройствах; пункт пострелизный и
  закрывается по измерениям, а не субъективному времени работы.

**Следующее действие:** принимать пользовательские отчёты по stable `v0.2.4`,
воспроизводить только подтверждённые проблемы по ADB evidence и выпускать patch-релизы.
Постепенно закрывать физическую матрицу: captive portal, IPv6-only/NAT64,
camera/HTTPS subscription, blocked-DNS/LKG/DoH, OEM per-app/routing и энергия.
Также создать две зашифрованные офлайн-копии production signing key по `SIGNING.md`.
