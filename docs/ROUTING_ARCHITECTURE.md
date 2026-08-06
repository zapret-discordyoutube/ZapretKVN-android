# ADR-003: приложения, назначения и rule-set в Zapret KVN

| Поле | Значение |
|---|---|
| Статус | Реализовано; автоматизированный Gate 4 пройден, выпуск после физической test matrix |
| Дата решения | 22 июля 2026 года |
| Последнее изменение | 25 июля 2026 года — общая destination-policy для всех профилей |
| Платформа | Android 8.0+ (API 26+) |
| Проверенный исходник | tag `v1.13.14-extended-2.5.2`, commit `ff11f007ec798136a5de258f947a4f34011a37ea` |
| Область | per-app capture, маршруты, domain/IP rule-set, пресеты |

Этот документ является источником истины для маршрутизации MVP. DNS остаётся в [DNS_ARCHITECTURE.md](DNS_ARCHITECTURE.md); при конфликте сетевой маршрут задаёт эта ADR, а способ его DNS-разрешения — DNS ADR.

## Решение

Приложения и назначения — не две реализации одного правила, а две последовательные координаты политики:

```text
пакет/UID ── Android решает, входит ли он в VPN
                         │
                         ▼ выбранные VPN + отдельный blocklist
                 один Android TUN
                         │
                         ▼
домен/IP ─── sing-box решает direct или выбранный proxy
```

- Невыбранное приложение идёт через системную сеть и не попадает в TUN/libbox, если
  оно не добавлено в отдельный список полной блокировки.
- Выбранное приложение целиком попадает в один TUN; уже внутри него sing-box применяет LAN, domain/IP/rule-set и `final`.
- Заблокированное приложение также попадает в TUN, но первое runtime-правило
  `package_name → reject` запрещает ему DNS и любой дальнейший сетевой маршрут.
- `direct` внутри sing-box не нагружает VPN-сервер, но такой flow уже прошёл локальный TUN/core.
- Физически существует один TUN. userspace stack sing-box не является вторым адаптером.

Слить точки исполнения нельзя: `VpnService.Builder` знает UID, но не знает домен будущего соединения, а sing-box видит домен/IP только после того, как Android передал flow в TUN. Исключать GEO-префиксы из Android routes не используем: Android не умеет доменные исключения, список IP меняется, а split routes усложняют IPv6, LAN, внутренний DNS и пересоздание TUN.

Объединяем только управление: экран «Маршрутизация» показывает обе координаты рядом, а `VpnController` перед каждым запуском создаёт один неизменяемый `EffectiveRoutingSnapshot`:

```text
DataStore app allowlist/blocklist ─────┐
DataStore global routing intent ───────┼─ EffectiveRoutingSnapshot
active profile sing-box base JSON ─────┘   ├─ packages → VpnService.Builder
managed rule-set manifest ─────────────────└─ network policy → runtime JSON/libbox
```

`GlobalRoutingPolicy` хранит только выбранный preset и список редактируемых
`match/action/outboundTag`, без transport, credentials, DNS-серверов или произвольного
sing-box JSON. Он не является вторым исполняемым конфигом: перед каждым запуском
компилируется вместе с базовым профилем в один диагностируемый effective sing-box JSON
до `CheckConfig()` и `establish()`. Snapshot сам правила не исполняет.

Технически sing-box умеет совместить `package_name` и `rule_set` в одном route-правиле.
Обычная VPN-allowlist этим не дублируется. Единственное штатное исключение — явно
выбранная полная блокировка приложения: такой пакет намеренно допускается Android в
TUN и немедленно получает `reject`.

## Повторный аудит раннего отсечения

Проверены все реалистичные точки, где можно убрать flow раньше:

| Способ | Где отсекает | Решение MVP |
|---|---|---|
| `addAllowedApplication()` | до TUN по UID | использовать всегда; это основной fast path |
| `route_exclude_address`/Android routes | до TUN по IP | не использовать для GEO/LAN в managed-режимах |
| `route_exclude_address_set` | потенциально до TUN по IP-set | exact libbox не раскрывает set в Android platform TUN; не использовать |
| binary `.srs` + `direct` | внутри sing-box, до VPN-сервера | использовать для domain/IP маршрутизации |
| `action: bypass` | kernel pre-match | доступен только Linux `auto_redirect`, не Android VPN |
| proxy-only/root/TProxy | вместо обычного Android VPN | не входит в неинвазивный MVP |

На снимке upstream `SagerNet/sing-geoip@5605651c12ed5b2fcf3b5de580c041eb9d8d938e` файл `geoip-ru.srs` с SHA-256 `1f4cccc9bb9510bb29d8a4b7d326b869bff94e9911d555acc0570545dabfaa7b` содержит 10 842 уже агрегированных CIDR: 8 668 IPv4 и 2 174 IPv6. Прямое раннее исключение означало бы:

- API 33+ — 10 842 вызова `VpnService.Builder.excludeRoute()`;
- API 26–32 — exact `sing-tun@v0.8.11 BuildAutoRouteRanges(true)` для полных IPv4/IPv6 routes превращает исключения в дополнение из 35 041 `addRoute()`;
- любое изменение набора требует пересоздания TUN;
- доменные правила всё равно остаются внутри sing-box и не получают общего fast path.

Кроме стоимости и риска системной таблицы, это раздваивает владельца GEO-политики между Android routes и `route.rules`. Выигрыш не оправдывает сложность. `excludeRoute()` к тому же существует только с API 33; exact Android reference на старых API действительно передаёт построенное дополнение через `addRoute()`.

Вывод: быстрее и корректнее уже выбранная схема — сначала Android package boundary,
затем один route-pass. Узкий пользовательский blocklist является осознанным исключением:
его пакеты входят в boundary ради немедленного `reject`; все остальные невыбранные
пакеты сохраняют раннее отсечение. Глобальный sniff не включаем.

Единственная оставшаяся микрооптимизация — патч exact core, отключающий безусловный Android owner/process lookup, когда effective JSON не содержит package/process rules. Она не фильтрует трафик раньше, требует собственного fork hash и остаётся только кандидатом после benchmark API 26/current; в MVP её нет.

## Источники истины

| Решение | Единственное постоянное хранилище | Исполнитель |
|---|---|---|
| какие приложения входят в VPN | глобальная allowlist в DataStore | Android `VpnService.Builder` |
| какие приложения полностью заблокированы | глобальный пустой по умолчанию blocklist в DataStore | Android boundary + runtime DNS/route `reject` |
| общие domain/IP, LAN, `direct`, proxy, `final` | `routing_policy` DataStore | runtime compiler → sing-box route engine |
| transport, outbounds и расширенные route/DNS-поля профиля | активный sing-box base JSON | runtime compiler → sing-box |
| встроенные GEO/domain-данные | versioned `.srs` + manifest внутри APK | sing-box `rule_set` |

Это намеренно не один физический файл: область приложений и destination-policy
глобальны, а transport/outbounds и расширенные поля принадлежат профилю. Общая политика
не копируется в JSON профилей, поэтому обновление подписки или переключение профиля её
не теряет. Исполняется только итоговый sing-box JSON; параллельного route engine и
дублирующих сохранённых route-объектов нет.

Поля `include_package`/`exclude_package` исходного JSON сохраняются для round-trip,
но runtime-копия очищает их и применяет глобальную область приложений. Route-пресеты
не создают `package_name`-правила; их создаёт только отдельная полная блокировка.

## Вместо GeoIP и Geosite

В sing-box 1.13 legacy-поля `geoip`, `source_geoip` и `geosite` уже удалены и в точном commit завершают разбор ошибкой. Используем современный `route.rule_set`:

- domain rule-set — домены и суффиксы, бывший сценарий Geosite;
- IP rule-set — IPv4/IPv6 CIDR, бывший сценарий GeoIP;
- binary `.srs` — основной production-формат;
- inline/source JSON — только для маленьких пользовательских списков и тестов.

Два набора с одинаковым назначением объединяем в одно route-правило:

```json
{
  "rule_set": [
    "zapret-ru-domains",
    "zapret-ru-ip"
  ],
  "action": "route",
  "outbound": "direct"
}
```

Domain и IP остаются отдельными файлами только потому, что DNS до получения ответа может сопоставить домен, но ещё не знает его IP. В route engine оба ссылаются на одно действие и не образуют второй проход по отдельному движку.

Сам `.srs` содержит только данные совпадения и не выбирает outbound. Действие и приоритет остаются в `route.rules` JSON, поэтому rule-set — не второй источник политики. Для RU-пресета IP-набор строится из закреплённого `geoip-ru` source, а domain-набор — из хранимого в нашем репозитории source JSON (`.ru`, `.su`, `.xn--p1ai` и явно проверенные дополнения). Архитектура не зависит от наличия общего upstream `geosite-ru.srs`.

## Порядок route-правил

Для управляемых пресетов порядок фиксирован и виден в итоговом JSON:

1. полная блокировка `package_name → reject`;
2. `port: 53 → hijack-dns` и точные служебные health-check правила;
3. все точные destination `reject` правила;
4. пользовательские `direct`, затем LAN/RU direct-исключения;
5. пользовательские proxy, затем preset proxy-правила;
6. `route.final`.

Первое совпавшее финальное route-действие определяет outbound. Пресет не копирует правила второй раз и не создаёт скрытую таблицу приоритетов.

## Управляемые режимы

Режим применяется только к выбранным Android-приложениям и является общим для всех
VPN-профилей. При первом запуске версии с общей политикой UI-intent однократно
инициализируется из режима и редактируемых правил активного профиля; ни один профиль
при этом не переписывается. После этого импорт, обновление подписки и смена активного
профиля используют ту же политику поверх своей базовой конфигурации. Явный
`outboundTag` обязан существовать в выбранном профиле; пустой tag каждый раз разрешается
в выбранный proxy/selector этого профиля.

| Режим | Правило rule-set | `route.final` | DNS по умолчанию |
|---|---|---|---|
| Всё через VPN | только LAN → `direct` при включённом обходе | selected proxy | secure |
| Только выбранные сайты | выбранные domain rule-set → selected proxy | `direct` | Android; совпавшие домены → secure |
| Россия напрямую, остальное через VPN | RU domain/IP rule-set → `direct` | selected proxy | secure; RU domains → Android |
| Россия через VPN, остальное напрямую | RU domain/IP rule-set → selected proxy | `direct` | Android; RU domains → secure |
| Пользовательский | как записано пользователем | как записано пользователем | по DNS ADR/JSON |

Второй GEO-режим — точная реализация формулировки «не-RU напрямую»: не используем инвертированное GeoIP-правило, а явно направляем RU rule-set в proxy и оставляем `final = direct`. Это проще и корректно обрабатывает адреса, отсутствующие в базе.

Название режима всегда показывает обе половины, чтобы пользователь не перепутал направление. GEO — приближённая классификация по версии набора, а не юридическая гарантия страны.

## Действие «Блокировать»

«Блокировать» — действие отдельного правила, а не третий глобальный режим маршрутизации. Новый JSON использует современный final action:

```json
{
  "rule_set": "zapret-block",
  "action": "reject"
}
```

Legacy outbound `{"type":"block","tag":"block"}` не создаём: начиная с sing-box 1.11 официальный путь — `action: reject`. Импортированный legacy JSON сохраняем без скрытой миграции и показываем метку «устаревшее».

Destination-блокировка действует только на приложения из «Области VPN». Отдельная
полная блокировка приложения хранится независимо: в include-режиме пакет добавляется
к Android allowlist, в exclude-режиме он не может одновременно находиться в списке
direct-исключений. Списки взаимоисключающие, blocklist по умолчанию пуст.

Для полной блокировки одно и то же первое правило `package_name → reject` добавляется
в runtime-копию `dns.rules` и `route.rules`. Сохранённый профиль не переписывается.
Блокировка действует только во время активной VPN-сессии Zapret KVN; изменение списка
применяется при следующем подключении.

Для managed-правила оставляем core defaults `method: default`, `no_drop: false`:

- TCP получает RST;
- UDP получает ICMP port unreachable;
- ICMP echo получает host unreachable;
- после 50 срабатываний за 30 секунд ядро временно переходит на drop для защиты от flood.

`drop` не делаем настройкой основного GUI: он создаёт долгие таймауты и полезен только advanced-пользователю через JSON.

Domain block компилируется атомарно в две ссылки на один domain rule-set:

1. в `dns.rules` — `action: reject`, чтобы стандартный DNS быстро получил `REFUSED` и запрос не ушёл к resolver;
2. в `route.rules` — `action: reject`, чтобы блокировка не зависела только от DNS-кэша.

IP/CIDR block добавляется только в `route.rules`. Route reject обязателен и для domain block: он использует доступное имя/reverse mapping после собственного DNS приложения или DNS-кэша. Прямое соединение только по IP гарантированно блокируется лишь при наличии соответствующего IP-set. Глобальный TLS/HTTP/QUIC sniff ради блокировки не включаем — он замедлил бы весь трафик.

Честная граница: domain-only block гарантирован для стандартных DNS-запросов, перехваченных managed DNS. Приложение со встроенным DoH может скрыть имя; без соответствующего IP-set или уже существующего reverse mapping такой flow нельзя гарантированно распознать по домену. GUI показывает это одной подсказкой, а не обещает полноценный firewall/ad blocker.

## Согласование DNS

Один intent режима атомарно меняет ссылки и в `route.rules`, и в управляемой части `dns.rules`:

- domain set → `direct` означает тот же domain set → Android DNS;
- domain set → proxy означает тот же domain set → secure DNS;
- `dns.final` следует `route.final`;
- IP set не выбирает DNS до ответа и используется только на route-этапе;
- пользовательский DNS в режиме «Из JSON» не переписывается.

Это две необходимые ссылки на один tag в одном effective JSON, а не две независимо
редактируемые политики. GUI сохраняет intent одной транзакцией DataStore и проверяет
его компиляцию на активном профиле. При каждом подключении полный effective JSON заново
собирается для выбранного профиля и проходит `CheckConfig()` до создания TUN.

## Доставка rule-set без новой подсистемы

Для встроенных пресетов используем только локальные binary `.srs`:

1. CI берёт исходный domain/IP список по полному commit SHA, проверяет лицензию и сохраняет source URL, commit, SHA-256 и дату данных в manifest.
2. Тем же pinned CLI компилирует source в `.srs` и проверяет его.
3. `.srs` и manifest входят в APK.
4. До первого использования новой версии APK файлы под service-lock атомарно копируются во временные файлы и переименовываются в стабильные пути `filesDir/rule-sets/*.srs`; manifest записывается последним.
5. При несовпадении SHA-256 или отсутствии файла VPN не запускается. Тихого пропуска GEO-правила нет.
6. Если процесс прерван, несовпадение manifest заставляет повторить извлечение до запуска VPN; временные файлы удаляются.

Нет отдельного updater, WorkManager, Room, `experimental.cache_file` и сетевой загрузки во время подключения. Наборы обновляются вместе с APK через GitHub Releases. Это делает первый запуск офлайн-предсказуемым и не позволяет недоступному GitHub заблокировать VPN.

Для импортированного JSON поддерживаются штатные `inline`, `local` и `remote` rule-set без скрытой переделки. Remote-наборы и их core-cache являются ответственностью такого JSON и явно помечаются в GUI как пользовательские; встроенные пресеты на них не зависят.

Управляемый профиль хранит обычный `type: local`, зарезервированный tag и фактический стабильный absolute path в самом sing-box JSON. Это остаётся валидным, видимым JSON без скрытого route-overlay. После Android restore или изменения data path приложение атомарно мигрирует только известные встроенные path и снова вызывает `CheckConfig()`; произвольные пользовательские local path не трогает. При экспорте GUI предупреждает, что локальный `.srs` нужно приложить отдельно; portable bundle можно добавить позже.

## Минимальный UI

Внутри действительно существуют два типа решения, как и в v2rayNG: per-app selection и destination routing. Но пользователю не показываем два равноправных пункта с названием «Маршрутизация». В актуальном v2rayNG это отдельные экраны «Выбор приложений» и «Маршрутизация»; Zapret KVN объединяет их в один нижний раздел с понятными названиями.

```text
Маршрутизация

┌ Область VPN ─────────────────────────┐
│ Только выбранные приложения          │
│ YouTube, Instagram, Discord  ·  7  › │
└──────────────────────────────────────┘

┌ Правило трафика ─────────────────────┐
│ Россия напрямую                      │
│ Остальное через выбранный VPN      › │
└──────────────────────────────────────┘

Заблокировано: 12 правил               ›

Итог
• выбранные приложения: RU → напрямую, остальное → VPN
• остальные приложения: напрямую, вне VPN
• блокировка: только для выбранных приложений

Расширенные настройки
Правила · Наборы .srs · JSON
```

- Нажатие «Область VPN» открывает полноэкранный picker с поиском; огромный список не держим на основном экране.
- Нажатие «Правило трафика» открывает короткий список пресетов с полным названием обеих половин маршрута.
- «Итог» пересчитывается сразу и всегда показывает судьбу невыбранных приложений.
- Редактор правила предлагает три понятных действия: «Через VPN», «Напрямую», «Блокировать»; effective-порядок всегда нормализуется как reject → direct → proxy → final и показывается в diff.
- Пользовательские domain/IP/rule-set и raw JSON находятся в одном раскрываемом блоке «Расширенные настройки».
- При смене приложений во время соединения Android TUN необходимо пересоздать; смена route-режима требует контролируемого перезапуска core. Для обоих случаев GUI показывает одну кнопку «Применить и переподключить».

Отдельные вкладки «Приложения / GEO» внутри нижней навигации не нужны. Также не добавляем отдельный экран GeoIP/Geosite, планировщик обновлений и собственный редактор баз.

## Проверка

Эталон [RU rule-set routing](../testdata/routing/ru-rule-set.json), SHA-256 `cad0494627f4776eda70da316bd9caea6105007cc39717923ab6a7062aa6fc96`, является schema/graph fixture: reserved example-сети имитируют IP-набор, а `selected-proxy` заменён на `direct`. Повторный аудит подтвердил `sing-box check` на точном core commit; fixture не содержит package-правил и legacy GEO-полей.

Эталон [Block rule](../testdata/routing/block-rule.json), SHA-256 `d19bb2ede90a327c699544d76757f6a2fbee9ceb55570c8330ac2acfe12a1f3b`, проверяет связку DNS `reject` + route `reject`, отсутствие legacy block outbound и отсутствие глобального sniff. Exact core и compatibility CLI приняли fixture повторно.

Реализация от 22 июля 2026 года включает два packaged binary-набора: `zapret-ru-domains.srs` (53 байта, SHA-256 `a39faeb4a4c894a2ce665b8919322cee626f61dd12c63a63736fcf8b0a433053`) и `zapret-ru-ip.srs` (50 036 байт, SHA-256 `1f4cccc9bb9510bb29d8a4b7d326b869bff94e9911d555acc0570545dabfaa7b`). [Manifest](../app/src/main/assets/rule-sets/manifest.json) закрепляет exact core/source revision/license; [проверочный скрипт](../scripts/verify-rule-sets.sh) воспроизводит domain-набор и проверяет RU/non-RU domain/IPv4/IPv6 exact CLI.

Исходный Gate 4: 47/47 JVM tests и 46/46 instrumented tests на API 26 и 36. Все шесть
presets прошли реальный Android TUN → local SOCKS5/direct/reject путь для RU/non-RU
domain и IPv4/IPv6; UI summary, тогда сохранявшийся JSON и фактический outbound
совпадали. Общий runtime-intent дополнительно закреплён codec/unit-тестом и тем же
чистым `RoutingConfigEditor`; итог по-прежнему проходит native `CheckConfig()` перед
TUN. Физическое переключение нескольких профилей относится к пострелизному device
gate и не подменяется JVM-тестом.

Exact core benchmark production assets: load 1 114 мкс, 758 624 allocation bytes; lookup 329 нс/op, 1 104 B/op, 2 allocs/op. Размер двух `.srs` после добавления `championat.com` — 50 114 байт. Полный debug-прогон API 36 дал extraction 2 мс, cold connect 41 мс, 100 мс CPU на 40 flows и +356 КиБ PSS; API 26 — 5 мс, 63 мс, 300 мс CPU и нулевой положительный PSS growth. Это закрывает автоматизированный Gate 4, но не физический release-gate энергии/OEM ниже.

Перед выпуском нужны следующие тесты. Это детализация P1, P7 и P8 из единого раздела [«Потом проверить»](ARCHITECTURE.md#потом-проверить--открытые-вопросы), а не отдельный список нерешённых архитектурных вопросов:

- повторить proxy/direct/reject preset matrix на слабом и современном физическом устройстве/OEM;
- обновление APK с фактической сменой version manifest и восстановление после принудительного kill во время замены;
- DNS block возвращает `REFUSED`, route block отклоняет TCP/UDP/ICMP, а health-check выше пользовательских правил продолжает работать;
- один blocked-домен из выбранного приложения отклоняется, тот же домен из невыбранного приложения остаётся вне TUN и не считается заблокированным;
- повторить embedded DoH boundary с реальным сторонним приложением;
- повторить lookup CPU/RAM/cold start и выполнить battery release-gate на слабом и современном физическом устройстве.

## Основания

- [Android per-app VPN contract](https://developer.android.com/develop/connectivity/vpn)
- [Android `VpnService.Builder`: `excludeRoute()` только с API 33](https://developer.android.com/reference/android/net/VpnService.Builder#excludeRoute(android.net.IpPrefix))
- [sing-box rule-set](https://sing-box.sagernet.org/configuration/rule-set/)
- [sing-box route rule](https://sing-box.sagernet.org/configuration/route/rule/)
- [sing-box route `reject`](https://sing-box.sagernet.org/configuration/route/rule_action/#reject)
- [sing-box DNS rule](https://sing-box.sagernet.org/configuration/dns/rule/)
- [sing-box DNS `reject`](https://sing-box.sagernet.org/configuration/dns/rule_action/#reject)
- [официальная миграция legacy block outbound](https://sing-box.sagernet.org/migration/#migrate-legacy-special-outbounds-to-rule-actions)
- [официальная миграция GeoIP/Geosite в rule-set](https://sing-box.sagernet.org/migration/)
- [upstream binary `geoip-ru.srs`](https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs)
- [точный core: отказ legacy GeoIP/Geosite](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/route/rule/rule_default.go#L128-L136)
- [точный core: inline/local/remote rule-set](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/option/rule_set.go)
- [точный core: Android platform TUN не разворачивает route address set](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/protocol/tun/inbound.go#L297-L355)
- [проверенный снимок `geoip-ru.srs`](https://raw.githubusercontent.com/SagerNet/sing-geoip/5605651c12ed5b2fcf3b5de580c041eb9d8d938e/geoip-ru.srs)
- [v2rayNG: отдельный экран per-app](https://github.com/2dust/v2rayNG/blob/1b13fadce7aea2e6ddcac346caa55d123042c985/V2rayNG/app/src/main/java/com/v2ray/ang/ui/perappproxy/PerAppProxyActivity.kt)
- [v2rayNG: отдельный экран destination routing](https://github.com/2dust/v2rayNG/blob/1b13fadce7aea2e6ddcac346caa55d123042c985/V2rayNG/app/src/main/java/com/v2ray/ang/ui/routing/RoutingSettingActivity.kt)
