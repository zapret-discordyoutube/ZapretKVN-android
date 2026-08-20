# Zapret KVN — политика форматов импорта

> Каноническая граница импортера. Документ определяет, какие входные форматы
> допустимы, что именно они меняют и при каких условиях можно расширять MVP.

| Поле | Значение |
|---|---|
| Статус | WireGuard/AmneziaWG `.conf` реализован; Clash YAML исследован и отложен |
| Последний аудит | 24 июля 2026 года |
| Ядро | `sing-box-extended` `ff11f007ec798136a5de258f947a4f34011a37ea` |
| Источник сетевой истины | sing-box base JSON профиля + документированные глобальные runtime-intent |

## Неподвижная граница

Любой импорт заканчивается созданием настоящего sing-box JSON до preview и
сохранения. YAML, URI и subscription response — только входные данные, а не
второй формат профиля или маршрутизации.

- импорт не переносит чужие DNS, route rules или proxy groups скрыто;
- неизвестное поле или тип нельзя молча отбросить и показать импорт успешным;
- исходный YAML не хранится и не участвует в runtime;
- перед сохранением итог проходит native `Libbox.CheckConfig()`;
- перед запуском сохранённый JSON может получить только явно документированные runtime overlays из DNS/Routing/VPN Hiding ADR; stored profile при этом не переписывается;
- URL обновляется только вручную, без фонового scheduler;
- секреты маскируются в preview, ошибках, логах и диагностике.

## Форматы MVP

- полный sing-box JSON;
- нативный WireGuard и AmneziaWG 2.0 INI `.conf`;
- JSON/raw/base64 subscription, который уже распознаёт реализованный importer;
- VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и TUIC URI;
- URL, QR, буфер обмена и системный file picker как способы доставки тех же
  данных.

Текстовый файл или буфер могут содержать несколько URI разных поддерживаемых
протоколов, подписи, маркеры списка и несколько ссылок в одной строке. Импортер
извлекает все поддерживаемые URI в исходном порядке и создаёт одну managed-группу
с preview полного количества серверов. Неподдерживаемые конфигурационные URI-схемы
пропускаются, а preview явно перечисляет только названия пропущенных схем без
адресов и credentials. Если поддерживаемых URI нет вообще, импорт завершается
ошибкой. Fragment у HTTP(S) URL подписки считается клиентской меткой, не отправляется
серверу и удаляется до загрузки и сохранения URL обновления.

## Вердикт по параметру определяет его класс

Незнакомое имя параметра ссылки не означает, что ссылку нужно отклонить. Каждый
токен входа получает ровно один вердикт:

1. **Переносится** — параметр представим в целевом sing-box JSON.
2. **Сохраняется** — вход уже является sing-box JSON, и судья ему `Libbox.CheckConfig()`,
   а не локальный allow-list (см. раздел про XHTTP ниже).
3. **Отклоняется** — параметр меняет аутентификацию, шифрование, проверку сертификата
   или сам факт TLS и не может быть перенесён точно. Сюда же попадает неизвестное имя:
   неизвестный класс — это отказ, а не молчание.
4. **Теряется с предупреждением** — параметр не влияет ни на криптографию, ни на то,
   кому клиент доверяет: он неисполним этим протоколом либо косметичен. Импорт
   продолжается, а параметр поимённо назван в preview.

Отсюда следуют инварианты:

- имя нормализуется до классификации (нижний регистр, свёрнутые `-` и `_`, канонический
  синоним), иначе строгость срабатывает на орфографию, а не на смысл: `allowInsecure`,
  `allow_insecure` и `skip-cert-verify` — один параметр;
- ослабление проверки исполняется только с видимым предупреждением, а непереводимое
  усиление (`pinSHA256`) отклоняется: sing-box закрепляет публичный ключ, а не сертификат,
  и тихая потеря вернула бы обычную проверку по CA;
- `spx`, `headerType=none`, `fp` и `security=tls` у QUIC-протоколов теряются с
  предупреждением: `spx` относится к реализации REALITY в Xray, а uTLS неприменим к QUIC —
  рукопожатие идёт внутри ядра, и sing-box уже имитирует QUIC-профиль Chrome сам;
- `headerType` с любым другим значением и чужой транспорт Shadowsocks отклоняются:
  они меняют вид трафика на проводе, и такое подключение не встанет;
- предупреждения — часть результата импорта, а не лог: они доходят до экрана
  подтверждения, проходят ту же редакцию секретов, что адреса и метки, и схлопываются
  с указанием числа серверов, чтобы не вытеснить кнопки диалога.

Список «теряется с предупреждением» расширяется только по реальному образцу ссылки
и вместе с тестом.

Кроме HTTP(S) источником подписки может быть открытая deep link клиента:
`happ://add`, `incy://add`, `incy://import` и `v2raytun://import`. Адрес внутри
принимается как обычным URL, так и в base64; после разворачивания он проходит ту же
проверку схемы и хоста. Схема ссылки задаёт профиль клиента по умолчанию, если
пользователь не выбрал другой. Закрытые `happ://crypt`…`happ://crypt5` расшифровываются
встроенной реализацией RSA PKCS#1 v1.5 и ChaCha20-Poly1305 (RFC 8439): платформенный
`javax.crypto` даёт этот AEAD только с API 28, а приложение поддерживает API 26.

Идентификация клиента хранится рядом с URL обновления, вне `profiles/index.json`:
профиль клиента, флаг передачи HWID и сам HWID. Записи старого формата — одна строка
URL — читаются как профиль Zapret KVN без HWID и переводятся в новый формат при первой
записи. HWID не сохраняется, пока передача выключена.

Для VLESS импортёр принимает стандартный sing-box flow `xtls-rprx-vision` и
Xray client alias `xtls-rprx-vision-udp443`. Alias преобразуется в
`xtls-rprx-vision`: в sing-box этот режим соответствует Xray-варианту без
блокировки UDP/443. Любой другой непустой `flow` отклоняется до native
`CheckConfig()`, а не переносится в заведомо несовместимый JSON.

## F-IMPORT-XHTTP — VLESS XHTTP URI

Граница конвертации разделяет два формата:

- вход соответствует XTLS VLESS share-link: `extra` — JSON-объект, целиком
  обработанный `encodeURIComponent`, а не Base64;
- выход соответствует `V2RayXHTTPOptions` закреплённого sing-box-extended:
  camelCase-поля XTLS явно преобразуются в snake_case-поля transport JSON.

Импортёр принимает как стандартный объект `extra`, так и полный объект
`xhttpSettings`: `host`, `path`, `mode`, вложенный `extra` и все остальные поля
переносятся целиком. Имена параметров системно преобразуются из Xray camelCase в
sing-box snake_case, включая будущие поля; имена HTTP-заголовков и типы JSON
остаются без изменений. Поле не отбрасывается по локальному allow-list:
окончательную совместимость всего результата проверяет закреплённое ядро через
`Libbox.CheckConfig()`.

Устаревшее Xray-поле `xmux.cMaxLifetimeMs` совместимо преобразуется в актуальное
`h_max_reusable_secs`: одиночное значение или обе границы диапазона делятся на
1000. Одновременное указание старого `cMaxLifetimeMs` и нового
`hMaxReusableSecs`, а также потеря миллисекунд при преобразовании отклоняются
явной ошибкой.

Link parser закреплённого core здесь не является источником синтаксиса входной
ссылки: в commit `ff11f007` его XHTTP-ветка ошибочно ожидает Base64. Источник
истины для URI — [XTLS share-link proposal](https://github.com/XTLS/Xray-core/discussions/716),
а источник истины для результата —
[точная схема `V2RayXHTTPOptions`](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/option/v2ray_transport.go).

## F-IMPORT-WG — WireGuard и AmneziaWG 2.0 `.conf`

Импорт является прямым преобразованием INI в единственный сохраняемый sing-box
JSON. Отдельный WireGuard-процесс, локальный SOCKS и второй VPN не создаются.
Поскольку закреплённый sing-box 1.13 использует новую схему, результат содержит
`wireguard` в верхнеуровневом `endpoints`, а не deprecated outbound:

- `[Interface] PrivateKey`, `Address`, `ListenPort`, `MTU` переходят в endpoint;
- `[Peer] PublicKey`, `PresharedKey`, `Endpoint`, `AllowedIPs` и
  `PersistentKeepalive` переходят в `peers`;
- AWG `Jc`, `Jmin`, `Jmax`, `S1`…`S4`, `H1`…`H4` и AWG 2.0 `I1`…`I5`
  переходят в нативный объект `amnezia`;
- `AllowedIPs` становятся одновременно peer policy и явным sing-box route
  rule; остальной трафик идёт в `direct`, как при split-tunnel WireGuard;
- числовые `DNS` становятся sing-box UDP DNS; сервер направляется через endpoint
  только если он входит в `AllowedIPs`. Для bootstrap имени `Endpoint`
  используется системный DNS underlying-сети;
- исходный INI после preview не хранится.

Private/public/pre-shared keys принимаются только как стандартный Base64 ровно
32 байт и записываются обратно в каноническом виде с `=` padding. Числовые
поля AWG ограничены UInt16, а `Jmin` не может превышать `Jmax`. `H1`…`H4`
принимают UInt32 либо диапазон `A-B`. Непустые `I1`…`I5` проверяются как цепочки
AWG 2.0 тегов (`b`, `c`, `t`, `r`, `rc`, `rd`, `d`, `ds`, `dz`). Пустые
`I2`…`I5` допустимы и не попадают в JSON.

Неизвестные секции и параметры, повторные scalar keys, hostname/search-domain в
`DNS`, `PostUp`/`Table`/`FwMark`, `IncludedApplications` и
`ExcludedApplications` завершают импорт явной ошибкой: их нельзя молча потерять
или исполнять. Per-app область настраивается только в UI Zapret KVN.

Схема сверена с
[option закреплённого core](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/option/wireguard.go),
[примером Amnezia endpoint](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/examples/amnezia/client.json)
и [parser AmneziaWG Android `2.0.0`](https://github.com/amnezia-vpn/amneziawg-android/blob/4116c836241f737badb99dcd4e990600d46e4c65/tunnel/src/main/java/org/amnezia/awg/config/Interface.java).

## F-IMPORT-01 — Clash YAML

### Что подтверждено

Pinned source уже содержит общий subscription parser и вызывает парсеры
sing-box, Clash YAML, SIP008 и raw URI именно в таком порядке. Clash parser
читает только верхнеуровневый список `proxies` и преобразует его в sing-box
outbounds. Он не является импортом Clash DNS, rules или proxy groups:

- [общий parser точного commit](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/parser/parser.go);
- [Clash parser точного commit](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/parser/clash/parser.go).

Реальный сигнал спроса есть, но частичный импорт опасен: отдельно зафиксированы
запрос VLESS XHTTP из Mihomo YAML и случай, когда потеря ECH-параметра делала
импортированный сервер нерабочим:

- [NekoBox: Clash YAML с VLESS XHTTP](https://github.com/qr243vbi/nekobox/issues/228);
- [NekoBox: потеря ECH config при разборе Clash YAML](https://github.com/MatsuriDayo/NekoBoxForAndroid/pull/1173).

При этом собранный из точного commit Android `libbox.aar` не экспортирует
`ParseSubscription` или `ParseClashSubscription`. Повторять parser и его YAML
семантику на Kotlin означало бы добавить второй конвертер, новую зависимость и
риск расхождения с ядром.

### Решение MVP

Clash YAML не поддерживать. Не добавлять Kotlin YAML parser и не угадывать
значение неподдержанных полей. Если вход похож на YAML, показывать явное
сообщение «Clash YAML пока не поддерживается», а не общую ошибку JSON.

Вернуться к реализации можно только если выполнено всё следующее:

1. Parser точного ядра экспортирован через libbox, либо отдельная ADR явно
   разрешила ровно один поддерживаемый converter boundary.
2. Собрано минимум 10 обезличенных реальных subscriptions, включая VLESS
   XHTTP, ECH, Hysteria2, TUIC, Shadowsocks plugin, YAML anchors/aliases и разные
   типы scalar values.
3. Для каждого неподдержанного proxy type или значимого поля импорт завершается
   явной ошибкой; silent drop запрещён.
4. Golden-тест сравнивает полученные outbounds, а итог проходит native
   `CheckConfig()` и реальное подключение.
5. Clash `rules`, `dns`, `proxy-groups` и `rule-providers` остаются вне импорта.

## F-IMPORT-02 — дополнительные extended URI

[Link parser точного commit](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/parser/link/parser.go)
распознаёт `tuic`, `trojan`, `vless`, `hysteria`, `hy2`, `hysteria2`, `ss` и
`vmess`. Из этого списка в приложении пока отсутствует только Hysteria v1
(`hysteria://`). Это кандидат, а не разрешение немедленно добавить протокол.

До нового протокола приоритетнее закрывать подтверждённые реальными ссылками
пробелы уже заявленных форматов: VLESS XHTTP, Shadowsocks plugin, aliases и
дополнительные параметры TUIC/Hysteria2. AnyTLS, SSH, ShadowTLS и другие
outbounds ядра не считаются URI-форматами только потому, что существуют в JSON:
без зафиксированного URI contract их синтаксис не придумывается. WireGuard/AWG
поддерживается отдельно только в нативном INI `.conf`, а не в придуманном URI.

Hysteria v1 можно включить, когда есть минимум три обезличенных реально
неподдержанных URI от пользователей, golden-тесты всех встреченных вариантов,
native `CheckConfig()` и device connection test. Сбор образцов — только после
явного действия пользователя, без аналитики; credentials удаляются до
диагностического экспорта.

SIP008 рассматривается отдельно: pinned core умеет этот JSON subscription
format, но это не extended URI. Его также нельзя объявлять поддержанным без
реальных образцов и доступного libbox binding.

## Таблица решений

| Формат | Статус | Следующий gate |
|---|---|---|
| WireGuard / AmneziaWG 2.0 `.conf` | Реализован как sing-box endpoint | Device connection test с реальным обезличенным WG и AWG 2.0 сервером |
| Clash YAML `proxies` | Отложен, не входит в MVP | libbox binding + 10 реальных образцов + отсутствие silent drop |
| Hysteria v1 URI | Первый кандидат | 3 обезличенных URI + native/device tests |
| Расширения текущих URI | Приоритет по фактическим ошибкам | Реальный образец + golden/native test на каждый вариант |
| SIP008 | Отложен отдельно от URI | Реальные subscriptions + libbox binding |
| Прочие extended outbounds | Не определены как URI | Публичный contract и реальные неподдержанные ссылки |

Checkbox исследовательского пункта означает завершённый аудит, а не наличие
формата в приложении. Реальная поддержка отмечается только отдельной задачей и
после прохождения её gate.
