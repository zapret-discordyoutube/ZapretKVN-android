# ADR-002: DNS и изоляция приложений в Zapret KVN

| Поле | Значение |
|---|---|
| Статус | Принято для реализации; выпуск только после test matrix |
| Дата решения | 21 июля 2026 года |
| Последний повторный аудит | 22 июля 2026 года |
| Платформа | Android 8.0+ (API 26+) |
| Проверенный исходник | tag `v1.13.14-extended-2.5.2`, commit `ff11f007ec798136a5de258f947a4f34011a37ea` |
| Android reference | gitlink `SagerNet/sing-box-for-android@eb87216961321de1802e1355c470242f2ed5faa8` (только поведенческий reference, не ABI-контракт) |
| Область | DNS, Android `VpnService`, per-app VPN, отказоустойчивость |

Этот документ является обязательной архитектурной спецификацией MVP. Слова «должен», «нельзя» и «только» обозначают требования, а не рекомендации. При обновлении базовой версии sing-box документ и эталонный JSON должны пройти повторную проверку.

Граница между глобальным списком приложений и domain/IP/rule-set политикой зафиксирована отдельно в [ROUTING_ARCHITECTURE.md](ROUTING_ARCHITECTURE.md). DNS следует выбранному сетевому outbound, но не решает, какие приложения входят в TUN.

## Границы достоверности

Документ разделяет три вида утверждений:

- **контракт ядра** — подтверждён зафиксированным исходным commit sing-box-extended;
- **контракт Android** — подтверждён публичным Android API и AOSP;
- **политика Zapret KVN** — обязательное решение нашего приложения поверх этих контрактов.

Исходный код подтверждает структуру и ожидаемое поведение, но не заменяет instrumented-тесты на устройствах. Per-app изоляция, Private DNS, captive portal, NAT64 и смена сети считаются готовыми к выпуску только после прохождения матрицы в конце документа.

### Результат аудита исходника

| Проверено | Подтверждённое поведение | Следствие для Zapret KVN |
|---|---|---|
| `experimental/libbox/tun.go` | DNS-адрес — следующий IPv4 после первого TUN-адреса; `/32` отклоняется | брать только `GetDNSServerAddress()`, не хардкодить `172.19.0.2` |
| `experimental/libbox/dns.go` | platform transport принимает raw DNS, а при `Raw=false` — только A/AAAA | API 29+ использовать `DnsResolver.rawQuery`, API 26–28 — `Network.getAllByName()` |
| `common/dialer/default.go`, `route/network.go` | при `route.auto_detect_interface=true` platform control вызывается для исходящих сокетов и возвращает ошибку в dialer | требовать этот флаг; не игнорировать `protect(fd) == false`, а выбрасывать ошибку в libbox |
| `dns/transport/https.go` | literal `server` задаёт адрес соединения, `tls.server_name` — SNI и HTTP Host; `detour` использует указанный outbound | DoH не имеет DNS bootstrap-цикла и действительно идёт через выбранный proxy |
| `dns/transport/fallback/strategy.go` | `sequential` переходит дальше только при transport/Go-ошибке и использует тот же context; зависший первый сервер может исчерпать весь deadline. `parallel` запускает все перечисленные транспорты в общем bounded context | разные источники DNS нельзя надёжно объединить этим transport: Auto выполняет конечные чистые app-level попытки, а `parallel` остаётся только внутри DoH-этапа; системный DNS разрешён только последним кандидатом Auto с предупреждением |
| `dns/client.go` | общий DNS timeout — 10 секунд; кэш при `independent_cache=false` не включает tag транспорта | DNS-выбор одного домена должен быть детерминированным, а при смене режима/сети кэш сбрасывается |
| `dns/router.go` | `ResetNetwork()` очищает DNS response cache и сбрасывает транспорты, но не очищает `dnsReverseMapping` | для полного сброса DNS-состояния в MVP нужен перезапуск core |
| `dns/transport/hosts/hosts.go` и dialer | `hosts.predefined` работает как resolver для `outbound.domain_resolver`, не меняя hostname назначения | last-known-good overlay сохраняет TLS/Reality SNI и HTTP Host |
| `route/route.go`, migration docs | `protocol: dns` требует предварительного sniff; `port: 53` совпадает без sniff | в быстром MVP использовать `port: 53 → hijack-dns` |
| `daemon/instance.go` | `OverrideOptions` дописывает package-list из JSON и изменяет только первый TUN | до запуска очищать оба package-list в runtime-копии и разрешать ровно один TUN |
| `route/router.go`, `route/route.go` | в Android-сборке с platform interface поиск owner/process включается безусловно и вызывается до сопоставления route-правил | удаление `package_name`-правил убирает дублирующую политику, но не отключает сам lookup в этом commit |
| `common/dialer/default.go` | `protect(fd)` не добавляется, если задан `bind_interface` либо `inet4/inet6_bind_address`; `route.default_interface` также выбирает другую ветку | platform bind/mark/netns-поля в Android MVP отклонять до `establish()` |
| `protocol/tun/inbound.go`, `experimental/libbox/service.go` | Android `OpenTun()` вызывается на стадии старта inbound, а route ranges строятся из TUN options | до запуска проверять полный IPv4/IPv6 capture и достижимость внутреннего DNS; после любого позднего сбоя закрывать platform PFD |
| Android reference `VPNService.kt` | per-app список применяется в `VpnService.Builder`, пакет VPN-приложения добавляется в include-режиме | глобальная allowlist является platform policy, а не доверием импортированному JSON |
| Android reference `DefaultNetworkMonitor.kt` | текущий retry-цикл может отправить одно успешное обновление интерфейса до десяти раз | не копировать цикл буквально; дедуплицировать и сериализовать network events |

Pinned Android gitlink фиксирует полезный пример поведения, но не доказывает совместимость с ABI нашего libbox. В проверенном core API менялся после commit Android reference, а upstream Android workflow для stable/testing дополнительно переключает submodule на плавающие `main`/`dev`. Поэтому Zapret KVN компилирует собственный platform adapter против AAR, собранного в том же CI из pinned core commit; reference-код не копируется как готовый бинарно-совместимый слой.

В ходе аудита также обнаружено, что upstream release asset с именем `1.13.14-extended-2.5.2` содержит revision `ea48501b1d078cbf55437a8d37987b2feb842796`, а не commit тега `ff11f00…`. На дату аудита между ними в DNS/libbox/route-коде различий нет — изменён только `README.md` — поэтому проверенные JSON совместимы с обоими. Однако upstream workflow принудительно создаёт локальный tag и умеет заменять assets существующего release. Следовательно, имя release не является доказательством происхождения бинарника: Zapret KVN собирает libbox и проверочный CLI самостоятельно только из полного pinned commit.

## Решение

- FakeIP по умолчанию выключен.
- До запуска VPN адрес сервера разрешает системный resolver Android на физической сети.
- После запуска DNS по TCP/UDP 53 выбранных приложений принимает DNS-модуль sing-box внутри TUN.
- В режиме «Автоматически» сначала без изменений пробуется выбранный DNS профиля, если он существует; после подтверждённой DNS-ошибки выполняется чистая попытка с DoH через выбранный proxy outbound, затем последний резерв — DNS Android с предупреждением.
- Строгий Private DNS Android не отключается и не перехватывается: для управляемых режимов «Автоматически»/«Защищённый» это блокирующая несовместимость, а не повод тайно ослабить DNS.
- Невыбранные приложения вообще не попадают в TUN и продолжают использовать обычную сеть и системный DNS.
- Явные режимы «Из JSON», «DNS Android» и «Защищённый через VPN» не получают скрытого fallback. Отказ всех DNS-кандидатов Auto закрывает VPN.
- Управляемый Zapret/sing-box DNS-кэш существует только в памяти сессии. Android resolver может иметь собственный системный кэш вне контроля приложения. Отдельно сохраняется маленький last-known-good кэш адресов VPN-серверов.

Это даёт наиболее предсказуемое поведение для per-app VPN и не создаёт постоянной второй системы маршрутизации.

## Два уровня, один TUN

Физически создаётся ровно один Android TUN. libbox получает его file descriptor и поднимает поверх него userspace stack sing-box; это не второй TUN и не второй VPN-адаптер.

| Уровень | Отвечает за | Не должен решать |
|---|---|---|
| Android `VpnService.Builder` | allowlist UID, адреса TUN, полные IPv4/IPv6 routes, внутренний DNS, PFD | домены, `direct/proxy`, правила России/LAN |
| sing-box за TUN | DNS hijack, доменные/IP-правила, `direct`, proxy и выбранный outbound | какие Android-приложения система допускает в TUN |

Каждый критерий имеет ровно одного владельца. Переданный через `TunOptions` package-list применяется platform-кодом к `VpnService.Builder`; сгенерированные режимы sing-box не повторяют это решение через `package_name`. Получив пакет из PFD, ядро считает приложение уже допущенным и выполняет только сетевую политику. Пользовательские process/package-правила допустимы только как явная advanced-функция JSON, а не как скрытая часть preset.

Отсюда следуют два разных значения «напрямую»:

- невыбранное приложение не входит в TUN вообще и работает через Android как без VPN;
- выбранное приложение всегда входит в TUN, но правило sing-box может отправить его соединение через защищённый `direct` outbound, не нагружая VPN-сервер.

Границу нельзя размывать: package-list не превращаем в route-правила sing-box, а «Обход LAN»/GEO-режимы не реализуем исключением адресов из Android TUN routes. Поля TUN в runtime JSON служат входом для `VpnService.Builder`; прикладная маршрутизация остаётся только в `route.rules` sing-box.

### Где отсекаем трафик ради скорости

Порядок fast path для MVP:

1. **До TUN — только по приложению.** Android allowlist отсекает основной объём: невыбранные UID не проходят через PFD, userspace stack, DNS и route engine вообще.
2. **В sing-box — один короткий route-pass.** Сначала DNS/health, затем локальные и точные IP-правила, после них доменные/rule-set правила и final. Package/process rules в сгенерированных режимах не используются.
3. **До VPN-сервера — по outbound.** `direct` всё ещё проходит через локальный TUN/core, но сразу выходит в underlying network через защищённый socket и не создаёт нагрузку на VPN-сервер.

Android routes умеют отсекать только IP-префиксы, а не домены. Теоретический fast-LAN через `excludeRoute()` сэкономил бы немного локального CPU, но потребовал бы вырезать из исключений внутренние TUN/DNS-подсети, учитывать API до 33, IPv6 и пересоздавать TUN при смене LAN. Для MVP это хуже по сложности и риску, чем один короткий проход через sing-box. Такую оптимизацию допускаем только позже, отдельно от DNS ADR и только после профилирования на слабом API 26 устройстве.

Важное ограничение exact core: pinned commit при наличии Android platform interface безусловно вызывает owner/process lookup для каждого локального TCP/UDP flow ещё до route rules. Штатного libbox-переключателя нет. Это не вторая allowlist-политика, но лишняя цена для наших preset-ов. В MVP не форкаем ядро ради этой микрооптимизации: инвариант точного commit и простое обновление важнее. Сначала измеряем lookup на API 26 и современном Android. Если он материален, будущий patch делает lookup условным; тогда бинарник уже маркируется как base commit + patch hash и проходит всю матрицу заново.

## Главный инвариант: прямые приложения не затрагиваются

По умолчанию используется Android allowlist:

1. Глобальный пользовательский список берём из DataStore. Поля `include_package`/`exclude_package` импортированного профиля сохраняем в JSON, но не считаем разрешением изменить platform allowlist.
2. Перед `establish()` проверяем выбранные пользователем пакеты через `PackageManager`.
3. Если после проверки пользовательский список пуст, VPN не создаём. Отсутствие allowlist у Android означает «все приложения через VPN».
4. В MVP разрешён ровно один TUN inbound. Ноль TUN, несколько TUN либо `auto_route: false` отклоняются до запуска с предложением явного исправления профиля.
5. В runtime-копии единственного TUN очищаем и `include_package`, и `exclude_package`. Затем передаём ровно один итоговый список через libbox `OverrideOptions`; это важно, потому что проверенное ядро дописывает override к JSON, а не заменяет его.
6. После проверки пользовательского списка внутренне добавляем пакет Zapret KVN. Это позволяет выполнять DNS/HTTP health-check через VPN; остальные служебные запросы явно привязываются к VPN либо underlying network согласно операции.
7. `OpenTun(TunOptions)` применяет итоговый include-list только через `addAllowedApplication()`. Если пакет исчез между preflight и вызовом Builder, запуск отменяется, а не продолжается с неполным списком.
8. Маршруты IPv4/IPv6 и внутренний DNS применяются только к UID итогового allowlist.
9. Все остальные приложения используют сеть так, будто VPN не запущен.

Каноническая точка применения в Android-адаптере выглядит так:

```kotlin
check(userPackages.isNotEmpty())
val allowedPackages = (userPackages + packageName).distinct()
allowedPackages.forEach(builder::addAllowedApplication)
// Затем на тот же Builder добавляются TUN addresses, full routes и internal DNS.
val tunPfd = checkNotNull(builder.establish())
```

`NameNotFoundException`, любая Builder-ошибка или `null` из `establish()` отменяют весь запуск; нельзя продолжать с урезанным списком. Это единственное место, где исполняется глобальная package allowlist.

Runtime-подмена списка пакетов — узкое исключение из правила «JSON является источником истины»: она не создаёт второй движок маршрутизации, не сохраняется в профиль и существует только для применения глобальной Android-настройки. Эффективный redacted runtime JSON доступен в диагностике.

Режим исключений может использовать `addDisallowedApplication()`, но два вида списков никогда не смешиваются. Это явный advanced-режим «все, кроме перечисленных, через VPN»: он предупреждает о большей нагрузке, не запускается с пустым списком исключений и не обещает, что остальные приложения идут напрямую. Режим по умолчанию и главный инвариант этой ADR — включения.

Дополнительные ограничения:

- пакет Zapret KVN добавляется внутренне и не отображается как пользовательский выбор; системные приложения автоматически не добавляются;
- изменение списка приложений требует пересоздания TUN;
- `allowBypass()` не вызывается: невыбранным приложениям он не нужен, а выбранным позволил бы самостоятельно обходить VPN;
- в управляемых режимах IPv4 и IPv6 выбранных приложений полностью направляются в TUN (`0.0.0.0/0` и `::/0`), чтобы частичные routes и IPv6 не стали обходным каналом;
- `route_address`, `route_exclude_address` и их rule-set/deprecated-варианты не используются в управляемом Android TUN. В частности, «Обход LAN» реализуется route-правилами sing-box с outbound `direct`, а не исключением RFC1918 из Android routes: иначе диапазон `172.16.0.0/12` вырежет и внутренний DNS `172.19.0.2`;
- перед `establish()` отдельно проверяем, что адрес из `GetDNSServerAddress()` входит в фактические Builder routes и не исключён;
- в любом TUN-профиле `route.auto_detect_interface` обязан быть `true`: именно этот флаг подключает platform protect callback к default dialer. В управляемых режимах GUI записывает его в JSON; режим «Из JSON» с выключенным флагом не запускается и показывает исправление;
- каждый исходящий сокет libbox должен успешно пройти через `VpnService.protect(fd)`; если Android вернул `false`, platform callback выбрасывает ошибку в libbox. Игнорировать boolean, как это делает текущий reference-клиент SFA, нельзя: возможна петля обратно в TUN;
- для гарантии `protect(fd)` Android MVP отклоняет `route.default_interface`, `route.default_mark` и platform-specific dialer-поля `bind_interface`, `inet4_bind_address`, `inet6_bind_address`, `bind_address_no_port`, `routing_mark`, `netns`, `protect_path`. Они остаются видимыми в исходном JSON, но требуют будущего отдельного аудита и не исправляются скрыто;
- гарантия распространяется только на outbounds/endpoints, которые используют проверенный common dialer либо имеют собственную проверенную platform-защиту. Конфигурация с неаудированным raw socket backend в MVP не запускается в TUN-режиме и получает диагностическую ошибку;
- physical/underlying `Network` отслеживается отдельно от VPN-сети;
- если исходящие сокеты явно привязываются к конкретному `Network`, тот же список передаётся через `Builder.setUnderlyingNetworks()`/`VpnService.setUnderlyingNetworks()` и обновляется при смене сети. Без явной привязки оставляем системное значение `null`.

Always-on/Lockdown не поддерживаем в MVP и объявляем в manifest:

```xml
<meta-data
    android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON"
    android:value="false" />
```

На API 29+ сервис читает `VpnService.isAlwaysOn()` и `isLockdownEnabled()`.
Если OEM/старая настройка всё же активировала этот режим, запуск блокируется
до TUN, а UI и diagnostic JSON объясняют, где его отключить. На API 26–28 Android не даёт
приложению публичный status API, поэтому manifest opt-out остаётся главной гарантией.

Lockdown Android способен блокировать трафик приложений вне allowlist, поэтому он несовместим с обещанием «остальные приложения всегда напрямую». На Android 8.0, где системный opt-out ограничен, это отдельно проверяем и объясняем пользователю.

Граница Android API: per-app VPN применяется к текущему Android user/work profile. Пакеты с общим UID и ограничения, навязанные Device Owner/Lockdown, нельзя разъединить на уровне приложения; диагностика не должна обещать для них изоляцию сильнее системного контракта.

## Физическая сеть Android

Приложение хранит ссылку именно на текущую не-VPN сеть: Wi‑Fi, cellular или Ethernet.

- До TUN можно получить текущий `activeNetwork`.
- Во время работы нужен `NetworkCallback`, не принимающий VPN-интерфейс за underlying network. API 31+ использует best-matching callback. На API 26–30 обычный пассивный callback наблюдает все подходящие `NOT_VPN` сети, а приложение выбирает лучшую из актуальных: validated, не captive, затем Ethernet/Wi-Fi/cellular. `requestNetwork` здесь не используется: проверка на API 26/28 показала, что он может удерживать первую подходящую сеть и не сообщать нужный Wi-Fi ↔ cellular switch.
- Там, где используется `NetworkRequest`, он явно требует `NET_CAPABILITY_NOT_VPN`; callback живёт в scope `VpnService`, всегда снимается при stop и не использует `GlobalScope`.
- События сериализуются и дедуплицируются по компактному policy key: identity `Network`/интерфейса, captive flag, strict mode, strict hostname и strict readiness (`active && validated`). Поэтому смена или поломка strict DNS уже во время сессии вызывает один контролируемый restart/fail-close даже при прежнем интерфейсе. Короткие повторы callback объединяются; устаревший bootstrap/restart отменяется generation token.
- Для наблюдения объявляется обычное, не runtime-разрешение `ACCESS_NETWORK_STATE`;
  для legacy-чтения имени текущей сети на API 26–28 — `ACCESS_WIFI_STATE`;
  `CHANGE_NETWORK_STATE` приложению не требуется.
- Опциональная автоматика по типу transport не требует дополнительных разрешений. Только
  точное сравнение доверенного SSID запрашивает `ACCESS_FINE_LOCATION` по действию
  пользователя и callback с `FLAG_INCLUDE_LOCATION_INFO` на API 31+. При redaction,
  отказе разрешения или выключенной геолокации SSID считается неизвестным и VPN не
  отключается; имя сети не экспортируется в diagnostic JSON.
- При смене policy key обновляем resolver и, если используется явная socket binding, `setUnderlyingNetworks()`, после чего выполняем контролируемый перезапуск core.
- Наличие `NET_CAPABILITY_CAPTIVE_PORTAL` блокирует автоматическое подключение и открывает системный вход в Wi‑Fi.
- Отсутствие `NET_CAPABILITY_VALIDATED` показываем в диагностике и обычно само по себе не считаем отказом: системная проверочная точка может быть заблокирована при рабочем интернете. Исключение — режим «DNS Android» при настроенном strict Private DNS: сочетание strict с неактивным Private DNS или невалидированной сетью блокирует запуск, чтобы Android 9 не ушёл на обычный DNS.

Системный captive portal и остальные невыбранные системные приложения остаются вне TUN.

## Этап 1: bootstrap до VPN

Адрес активного VPN-сервера разрешается через resolver Android, привязанный к underlying `Network`:

- Android 10+ (API 29+): `DnsResolver` с обычным объектом `Network`, `CancellationSignal` и `FLAG_EMPTY`;
- Android 8–9: `Network.getAllByName()`.

Этой границей владеет отдельный модуль `network-bootstrap`. Он публикует сеть только после получения и `NetworkCapabilities`, и `LinkProperties`, ждёт короткое стабильное окно перед bootstrap и один раз повторяет операцию только при доказанной смене identity либо bootstrap-значимых полей той же сети: validation/captive, Private DNS или списка DNS-серверов. Обычный DNS timeout/error при неизменной сети скрыто не повторяется. Coroutine cancellation всегда пробрасывается наружу.

Ошибки bootstrap типизированы стабильными кодами: `NET-101/102` для отсутствующей/меняющейся сети и `DNS-101…106` для timeout, NXDOMAIN, REFUSED, пустого ответа, системной ошибки и прочего RCODE. На главной показываются код и понятное действие, а diagnostic JSON получает redacted `rcode`/`errno`/timeout без hostname сервера.

`NET-101`, `NET-102`, `DNS-101` и `DNS-105` не останавливают сервис: диагностика фиксирует провалившуюся попытку, а на главной вместо ошибки появляется «Переподключение» с кодом причины и номером попытки. Остальные коды, включая captive portal `NET-110`, остаются терминальными — они требуют пользователя, а не повтора.

Так приложение использует настройки сети Android и Private DNS на Android 9+. Нельзя получать hidden `getPrivateDnsBypassingCopy()` или отправлять собственный plaintext bootstrap-запрос: настройки Private DNS никогда не изменяются и не обходятся.

В platform `LocalDNSTransport` применяем ту же underlying network. На API 29+ `Raw()` возвращает `true`, а `Exchange()` передаёт полный пакет через `DnsResolver.rawQuery()`. На API 26–28 `Raw()` возвращает `false`, и libbox вызывает `Lookup()` только для A/AAAA через `Network.getAllByName()`.

В `LinkProperties` диагностируем:

- Private DNS выключен;
- opportunistic/automatic;
- strict с указанным hostname;
- underlying network отсутствует или не валидирована.

Если strict Private DNS не отвечает, запрещён скрытый запрос к публичному plaintext DNS. Managed Auto/Secure и DNS Android завершаются понятной ошибкой до TUN. В режиме «Из JSON» LKG ещё может помочь только bootstrap поддержанного аутентифицируемого outbound, но ответственность за пользовательскую DNS-схему остаётся у JSON.

`route.default_domain_resolver` указывает на локальный platform resolver:

```json
{
  "route": {
    "default_domain_resolver": "zapret-android-dns"
  }
}
```

Это разрешает hostname proxy outbound до того, как DoH через этот proxy станет доступен.

## Private DNS Android внутри VPN

Private DNS нельзя считать только свойством bootstrap-сети. AOSP поддерживает strict Private DNS и на VPN network: системный resolver может отправлять DoT на порт 853 вместо запроса к адресу из `addDnsServer()`. Такой трафик не совпадает с `port: 53 → hijack-dns`, а ответы не попадают в `dnsReverseMapping`; поэтому доменная маршрутизация sing-box может потерять имя назначения.

Политика MVP:

- на API 28+ strict-режим определяем по непустому `LinkProperties.privateDnsServerName`, даже если `isPrivateDnsActive` временно `false` из-за неудачной валидации;
- если выбран «Автоматически», strict Private DNS не блокирует запуск: цепочка кандидатов сужается до «DNS Android» (единственный кандидат, уважающий системный DoT) с явным предупреждением в логе; кандидаты «DNS профиля» и managed DoH при strict пропускаются, потому что подменяли бы выбранный пользователем резолвер. Для «Защищённого через VPN» strict остаётся блокирующей preflight-ошибкой (`DNS-110`) до `establish()`: этот режим явно обещает DoH и не может одновременно уважать strict. Приложение системную настройку само не меняет;
- в режиме «DNS Android» работающий strict Private DNS разрешён и остаётся системным источником истины. До TUN требуются `isPrivateDnsActive=true` и `NET_CAPABILITY_VALIDATED`; иначе подключение блокируется без plaintext fallback. На Android 9 при несуществующем strict hostname active может остаться `true`, но сеть теряет `VALIDATED`, поэтому проверять только один флаг нельзя. GUI предупреждает, что доменные правила, которым требуется `reverse_mapping`, могут быть неполными;
- в режиме «Из JSON» существующую DNS-секцию не переписываем, а при её отсутствии добавляем только в runtime минимальный local DNS Android; показываем обнаруженный strict Private DNS и оставляем ответственность за явно заданную схему пользователю;
- opportunistic Private DNS разрешён: Android может проверить DoT, а для внутреннего адреса без DoT вернуться к обычному DNS. Это поведение обязательно проверяется на API 28, 29 и современной версии Android;
- DoT/853 не подменяем, не расшифровываем и не блокируем ради принудительного downgrade: в strict-режиме блокировка просто уничтожила бы DNS.

Так мы действительно уважаем настройку Android: не заявляем, что `addDnsServer()` способен отменить strict Private DNS, и не выдаём обходящий нашу DNS-схему DoT за успешный managed-режим.

## Этап 2: DNS внутри VPN

После разбора TUN-настроек libbox возвращает адрес внутреннего DNS через `GetDNSServerAddress()`. Именно его передаём в `VpnService.Builder.addDnsServer()`.

Для стандартной сети `172.19.0.1/30` результатом будет `172.19.0.2`, но адрес не хардкодим. Текущий libbox выбирает следующий адрес после первого IPv4 TUN-адреса и отклоняет `/32`, где нет места для DNS.

В управляемых режимах единственный TUN обязан иметь IPv4-префикс минимум `/30`, IPv6-префикс и `auto_route: true`. Используются полные IPv4/IPv6 routes без route-exclude; IPv6-адрес и маршруты добавляются отдельно, но внутренний DNS-адрес в проверенном libbox всё равно вычисляется из первого IPv4-префикса. `strict_route` на Android reference не реализован, поэтому на него не опираемся.

```text
TUN: libbox TunOptions
  ├─ addresses/routes → VpnService.Builder
  └─ dnsServerAddress → addDnsServer(...)
```

Если не вызвать `addDnsServer()`, Android использует DNS default network. Для выбранных приложений это нарушит нашу DNS-политику.

Первым route-правилом добавляется:

```json
{
  "port": 53,
  "action": "hijack-dns"
}
```

Оно направляет стандартный DNS по TCP/UDP 53 выбранных приложений в DNS-модуль sing-box. Вариант `protocol: "dns"` без стоящего перед ним `action: "sniff"` не работает: значение protocol появляется только после sniff. Глобальный sniff всех соединений ради DNS нам не нужен.

## Режим «Автоматически»

Auto — это ограниченный выбор готовой DNS-политики, а не смешивание нескольких
resolver-ов внутри одной сессии:

1. Если профиль содержит непустой `dns.servers`, а `dns.final` отсутствует либо
   ссылается на существующий tag, первая попытка использует точную DNS-секцию
   профиля — как «Из JSON».
2. Если обязательная DNS-проба через TUN завершилась ошибкой или timeout, текущие
   core, TUN PFD, command clients и network callback полностью закрываются. Новая
   попытка использует системную DNS/Private DNS политику Android.
3. Только после второй подтверждённой DNS-ошибки выполняется последняя чистая
   попытка с защищённым DoH через выбранный proxy outbound.
4. После отказа DoH VPN закрывается. Цикла, фонового retry и перехода обратно нет.

Если профиль не задаёт DNS, цепочка начинается с Android. Ошибка JSON, bootstrap,
proxy/handshake или HTTPS health-check не является DNS-ошибкой и не переключает
resolver. Все попытки входят в общий deadline 45 секунд и используют один generation
token, поэтому одновременно существуют не более одного TUN и одного core.

App-level переход нужен из-за контракта exact core: встроенный `fallback/sequential`
передаёт всем транспортам один context, поэтому зависший первый resolver способен
израсходовать общий deadline и не дать второму начать полезную работу. Смешивать DNS
профиля, Android и DoH через `parallel` также нельзя: это отправило бы каждый новый
запрос сразу всем источникам и сделало бы результат гонкой разных политик. Чистый
restart сохраняет простой fail-close lifecycle и не оставляет старый DNS-кэш или
reverse mapping следующему кандидату.

DNS-решение внутри каждого managed Android/Secure этапа повторяет доменную часть
маршрутизации: LAN/direct-домены используют Android resolver, а proxy-домены в Secure
используют DoH. IP-CIDR правила применяются уже после получения IP и не могут сами
выбрать DNS до ответа.

Настройка «Только IPv4 через VPN» добавляет `strategy: "ipv4_only"` к сгенерированным DNS-действиям для доменов, которые по effective route пойдут через proxy. Она применяется в явных Secure/DNS Android и в соответствующих managed-этапах Auto, но не изменяет первую попытку с DNS профиля или явный режим «Из JSON». Pinned sing-box отвечает на AAAA пустым успешным ответом, поэтому приложение выбирает A и не тратит время на IPv6, которого нет у proxy-сервера. LAN/direct-домены, IPv6 TUN route и сохранённый JSON не меняются.

Для IPv6-only сайта пользователь может выключить опцию и вернуть dual-stack. Однако WireGuard outbound без внутреннего IPv6-адреса всё равно не способен передать IPv6: exact core завершает такой flow ошибкой `missing IPv6 local address`. Для него нужны IPv6-адрес интерфейса и соответствующий peer route в самом профиле. DNS-слой не может безопасно изобрести этот адрес или выдать AAAA только после неудачной IPv4-попытки приложения.

## Эталонный фрагмент защищённого этапа для sing-box 1.13.14

Фрагмент для явного Secure или DoH-этапа Auto с proxy по умолчанию и прямыми LAN-исключениями:

```json
{
  "dns": {
    "servers": [
      {
        "type": "local",
        "tag": "zapret-android-dns"
      },
      {
        "type": "https",
        "tag": "zapret-doh-1",
        "server": "9.9.9.9",
        "tls": {
          "enabled": true,
          "server_name": "dns.quad9.net"
        },
        "detour": "<SELECTED_PROXY_OUTBOUND_TAG>"
      },
      {
        "type": "https",
        "tag": "zapret-doh-2",
        "server": "8.8.8.8",
        "tls": {
          "enabled": true,
          "server_name": "dns.google"
        },
        "detour": "<SELECTED_PROXY_OUTBOUND_TAG>"
      },
      {
        "type": "https",
        "tag": "zapret-doh-3",
        "server": "208.67.222.222",
        "tls": {
          "enabled": true,
          "server_name": "dns.opendns.com"
        },
        "detour": "<SELECTED_PROXY_OUTBOUND_TAG>"
      },
      {
        "type": "https",
        "tag": "zapret-doh-4",
        "server": "1.1.1.1",
        "tls": {
          "enabled": true,
          "server_name": "cloudflare-dns.com"
        },
        "detour": "<SELECTED_PROXY_OUTBOUND_TAG>"
      },
      {
        "type": "https",
        "tag": "zapret-doh-5",
        "server": "77.88.8.8",
        "tls": {
          "enabled": true,
          "server_name": "common.dot.dns.yandex.net"
        },
        "detour": "<SELECTED_PROXY_OUTBOUND_TAG>"
      },
      {
        "type": "fallback",
        "tag": "zapret-secure-dns",
        "servers": [
          "zapret-doh-1",
          "zapret-doh-2",
          "zapret-doh-3",
          "zapret-doh-4",
          "zapret-doh-5"
        ],
        "strategy": "parallel"
      }
    ],
    "rules": [
      {
        "domain_regex": [
          "^[^.]+$"
        ],
        "action": "route",
        "server": "zapret-android-dns"
      },
      {
        "domain": [
          "home.arpa"
        ],
        "domain_suffix": [
          ".local",
          ".lan",
          ".home.arpa"
        ],
        "action": "route",
        "server": "zapret-android-dns"
      }
    ],
    "final": "zapret-secure-dns",
    "strategy": "prefer_ipv4",
    "cache_capacity": 4096,
    "reverse_mapping": true
  },
  "route": {
    "rules": [
      {
        "port": 53,
        "action": "hijack-dns"
      }
    ],
    "default_domain_resolver": "zapret-android-dns",
    "auto_detect_interface": true
  }
}
```

Это не самостоятельный профиль: полный JSON дополнительно содержит TUN inbound с `address: ["172.19.0.1/30", "fdfe:dcba:9876::1/126"]`, `auto_route: true`, IPv4/IPv6 routes, direct outbound и реально выбранный proxy/selector outbound. Все placeholder заменяются до `CheckConfig()`.

У DoH указан literal IP, а TLS hostname остаётся в `server_name`. Поэтому подключение к DoH не требует предварительно разрешать имя самого DoH. `detour` всегда заменяется тегом выбранного proxy/selector outbound, а не фиксированной строкой `proxy`.

Только этот защищённый этап использует extended `fallback/parallel`. Пять DoH-транспортов перечислены как Quad9, Google, OpenDNS, Cloudflare и Yandex, но на cache miss стартуют параллельно в одном bounded context; первый корректный DNS-пакет выигрывает, остальные вызовы отменяются. Это создаёт до пяти защищённых запросов на новый домен, но не затрагивает успешные profile/Android-этапы Auto. В exact core это единственный встроенный способ не потерять резервный DoH, когда основной завис до общего deadline. Кэш capacity 4096 ограничивает повторение этой цены. Периодического трафика, отдельного health-loop и plaintext fallback нет.

`fallback` не оценивает DNS RCODE: `NXDOMAIN`, `SERVFAIL` или `REFUSED` являются корректным DNS-пакетом и могут выиграть гонку. Поэтому все managed endpoints должны быть равно доверенными и семантически взаимозаменяемыми. Пользовательский режим «Из JSON» сохраняет явно заданную стратегию без подмены.

Созданные приложением объекты имеют префикс `zapret-`. GUI управляет только этими DNS-серверами и правилами, которые ссылаются на них; остальные JSON-поля и правила сохраняются. Полный импортированный JSON с собственным DNS по умолчанию открывается в режиме «Из JSON», без скрытого переписывания.

## FakeIP

FakeIP выключен по умолчанию и в MVP не требуется.

Причины:

- при типичном диапазоне `198.18.0.0/15` приложения и WebView могут сохранить FakeIP после остановки VPN;
- per-app VPN не гарантирует, что последующее соединение с FakeIP попадёт в тот же TUN;
- сложнее переключать сети и профили;
- больше проблем с LAN, mDNS и приложениями с собственным DNS.

Используем реальные IP и `reverse_mapping`, чтобы sing-box мог сопоставлять DNS-ответы с доменами при маршрутизации.

Проверенное ядро требует явно задать хотя бы один `inet4_range`/`inet6_range`; не полагаемся на скрытый default. FakeIP можно добавить позже как явно экспериментальный режим. При включённом persistent `store_fakeip` его хранилище должно иметь отдельный `cache_id` для каждого профиля и полностью очищаться при удалении профиля.

## Кэш

### Обычные домены

Только оперативный кэш sing-box:

- кэш включён;
- TTL соблюдается;
- `cache_capacity = 4096`;
- `independent_cache` не включаем: в проверенном ядре это делает cache key общим между DNS-транспортами и немного экономит память/CPU;
- persistent DNS cache в MVP не используем.

Общий cache key безопасен для сгенерированных режимов только при инварианте: один и тот же домен в пределах активной конфигурации всегда выбирает один и тот же DNS-транспорт. Zapret KVN не создаёт DNS-выбор одного домена, зависящий от пакета/UID. При смене DNS-режима или правил выполняется перезапуск core. Если пользователь вручную строит контекстно-зависимую схему, это режим «Из JSON», и он сам решает, включать ли `independent_cache`.

В проверенном commit `experimental.cache_file` умеет сохранять FakeIP и rejected-response cache, но поля persistent DNS cache нет. Поэтому обычные DNS-ответы на диск не записываются. Возможности будущей версии не считаются частью этой ADR до нового аудита.

### Адрес VPN-сервера

Отдельный небольшой bootstrap cache содержит:

- ID профиля и hostname сервера;
- последний успешно проверенный набор A/AAAA;
- время разрешения и последнего успешного подключения.

Политика MVP:

- системный Android resolver всегда пробуется первым;
- до 24 часов — резервный кандидат после ошибки системного DNS или неудачи всех новых адресов;
- от 24 часов до 7 дней — только аварийный кандидат, когда системное разрешение имени не удалось;
- старше 7 дней, после изменения hostname или удаления профиля — удалить;
- не сохранять токены, UUID, пароли или полный URL подписки.

Кэш хранится небольшим атомарным файлом в `noBackupFilesDir`, ограничивается по размеру и очищается кнопкой диагностики.

При аварийном использовании сохранённый JSON не меняется. В runtime-копию добавляется `hosts` DNS server с точным hostname активного proxy outbound, а `domain_resolver` только этого outbound временно указывает на него. Само поле `server` остаётся доменным, поэтому сохраняются TLS SNI, Reality и HTTP Host. Такой overlay существует только во время одного повторного запуска core и никогда не сохраняется в профиль.

Адрес попадает в last-known-good только после успешной протокольной проверки через этот сервер. Автоматический stale-fallback запрещён для plaintext SOCKS/HTTP и других outbounds без проверки удалённой стороны: устаревший IP не должен получить учётные данные или трафик.

## Смена сети

При переходе Wi‑Fi ↔ cellular:

0. дождаться, пока Android действительно настроит новый линк: есть интерфейс, резолверы и вердикт validated либо captive portal. Пока сеть дозревает, debounce перезапуска увеличен, а потолок ожидания не даёт зависнуть на сети, которую Android не пометит validated никогда;
1. получить новый underlying `Network`;
2. обновить `setUnderlyingNetworks()`, только если сокеты явно привязаны к underlying network;
3. отменить незавершённые platform DNS-запросы;
4. заново разрешить hostname сервера через новый `Network`;
5. в MVP контролируемо перезапустить core, чтобы удалить DNS response cache, reverse mapping и старые соединения;
6. использовать last-known-good только после неудачи нового разрешения/подключения.

Одного libbox `ResetNetwork()` для строгого сброса недостаточно: оно очищает response cache и сбрасывает DNS-транспорты, но текущий `dnsReverseMapping` остаётся жить до TTL. Полный перезапуск нужен только при фактической смене `Network`, DNS-режима/правил и по ручной команде, а не при каждом `LinkProperties` callback.

Кнопка «Очистить DNS-кэш» очищает bootstrap cache и выполняет контролируемый перезапуск core. Она не может глобально очистить системный/Private DNS cache Android. На API 29+ следующую диагностическую bootstrap-пробу выполняем с `FLAG_NO_CACHE_LOOKUP`; на API 26–28 публичного no-cache API нет. Отдельный сложный cache manager не нужен.

## Ошибки и состояние подключения

Последовательность запуска:

```text
Проверка исходного JSON
→ проверка непустого списка приложений
→ ожидание пригодной физической сети вне общего deadline
→ получение underlying Network
→ проверка совместимости с Private DNS
→ bootstrap адреса сервера
→ построение и повторная libbox-проверка effective runtime JSON
→ запуск libbox и TUN
→ проверка, что VPN стал default network для UID Zapret KVN
→ DNS-проба обычным сокетом UID Zapret KVN через TUN
→ только в Auto при DNS-ошибке: полный stop и следующий кандидат профиль → DoH → Android
→ HTTPS-пробы Cloudflare, Google и OpenDNS через TUN со ступенчатым стартом
→ первый корректный ответ побеждает и отменяет остальные пробы
→ при провале всех трёх — одна спасательная проба с удвоенными тайм-аутами
→ Подключено
```

После `establish()` приложение получает VPN `Network` через `ConnectivityManager` и проверяет, что он стал `activeNetwork` именно для UID Zapret KVN. Сам пакет клиента уже внутренне присутствует в Android allowlist. DNS использует resolver этого VPN `Network`, а обычная HTTPS-проба — `Network.openConnection()`, поэтому оба запроса входят в TUN. Явный `Network.bindSocket()` к собственному VPN Network не используется: instrumented-проверка Android 16 вернула владельцу `VpnService` `EPERM`. Underlying `Network` используется только для bootstrap и защищённых исходящих сокетов core; health-check никогда не привязывается к underlying. Вход в TUN сам по себе ещё не гарантирует proxy-путь, поэтому отдельное раннее health route запрещает выход проверки через внутренний `direct`.

Во всех четырёх режимах runtime-копия ставит перед пользовательскими destination rules две узкие операции: TLS sniff только для package Zapret KVN, TCP/443, а затем точные HTTPS probe-hostnames → selected outbound для того же package. Поэтому проверка не зависит от пользовательского `dns.reverse_mapping`, не может ложно выйти через `direct` и не включает global sniff для выбранных приложений. Test 24 показал, что отдельная WireGuard-only concrete URL-test из трёх публичных сайтов не отличает поломку WireGuard от блокировки самих сайтов и добавляет ровно 12 секунд; этот дублирующий gate удалён. В managed-режимах и при минимальном Android fallback также стоит `port: 53 → hijack-dns`. Probe-hostnames не сохраняются в профиль и не используются для фонового polling.

Для выбранного userspace WireGuard приложение отдельно читает только внутренние
`address` endpoint. Если у него есть IPv4, но нет IPv6, HTTPS health-check
разрешает имя через resolver активной VPN `Network`, выбирает только IPv4 и
открывает числовой адрес через `Network.getSocketFactory()`. Исходное имя
одновременно сохраняется в TLS SNI, HTTPS hostname verification и HTTP `Host`,
поэтому проверка сертификата не ослабляется. Для IPv6-only endpoint применяется
симметричный выбор IPv6; dual-stack и остальные outbound сохраняют обычный resolver.
Если требуемого семейства в DNS-ответе нет, приложение возвращает точный `VPN-201`,
а не общий `VPN-200`. Адреса, `allowed_ips`, DNS и route сохранённого JSON при этом
не меняются.

Несколько DNS server objects сами по себе не образуют резервирование. В режиме
«Из JSON» приложение анализирует ссылки из `dns.final`, `dns.rules`,
`route.default_domain_resolver`, outbound/endpoint `domain_resolver` и дочерних
`fallback.servers`. Недостижимые серверы показываются предупреждением при импорте
и в bounded diagnostic log, но приложение не создаёт `fallback` скрыто и не
переписывает пользовательскую семантику.

Для защищённого кандидата DNS-проба отправляет собственный минимальный UDP-запрос, а при необходимости TCP-повтор, на адрес из `GetDNSServerAddress()` обычным сокетом health UID; так проверяются per-app admission, TUN route и `hijack-dns`, а не OS cache. Для DNS профиля и DNS Android используется `DnsResolver` с VPN `Network`: запрос всё равно входит в TUN и проверяет фактически объявленный Android DNS этой сессии. Только полученная здесь DNS-ошибка имеет право перевести Auto к следующему кандидату.

Один внешний HTTPS URL не является единственным критерием подключения: перед ним обязательны bootstrap/proxy и DNS-пробы. Три небольших HTTPS probe (Cloudflare, Google, OpenDNS) стартуют через выбранный outbound со ступенчатой задержкой 1 секунда между стартами («happy eyeballs»: медленный туннель не получает залп из шести DNS-запросов и трёх TCP-сессий одновременно); первый корректный ответ выигрывает, а остальные пробы отменяются с немедленным закрытием их сокетов. Любой корректный TLS+HTTP ответ 2xx–5xx доказывает полезный путь; точный код 204 не требуется. DNS-резолв внутри туннеля для health-проб использует укороченный тайм-аут 3 секунды (bootstrap на физической сети сохраняет 8), чтобы fallback на следующий DNS-режим начинался быстрее. Если все три пробы провалились, выполняется одна спасательная последовательная проба первого endpoint с удвоенными тайм-аутами (резолв 5 с, сокет 8 с): живой, но медленный туннель проходит проверку со статусом recovered, мёртвый — нет. Только отказ всех трёх endpoints и спасательной пробы закрывает VPN — при этом ошибки в диагностике сохраняют фиксированный порядок endpoints и включают исход спасательной пробы. Внешний IP остаётся дополнительной диагностикой, а не блокирующим условием. Эти запросы существуют только при подключении/контролируемом restart: периодического опроса нет.

Если underlying network работает, но proxy или активный DNS не восстановились в пределах bounded health deadline (20 секунд для DNS+HTTPS части одной попытки; 45 секунд для всего Auto-запуска):

- отменить health-check и network jobs;
- идемпотентно остановить core и в `finally` закрыть сохранённый `ParcelFileDescriptor` TUN;
- остановить foreground service;
- показать причину;
- вернуть выбранные приложения в обычную сеть Android.

Не оставляем активный TUN с мёртвым внутренним DNS и не показываем «Подключено» после проваленной обязательной пробы.

Тот же cleanup обязателен при исключении из `OpenTun()`, ошибке `instance.Start()`, `establish() == null`, `onRevoke()` и отмене запуска. Проверенный `StartedService` может перейти в `FATAL`, сохранив ссылку на уже закрытый instance, а Java/Kotlin PFD ядро закрыть не может. Поэтому PFD принадлежит одному `VpnService`-координатору, все start/stop/reload события сериализуются, а повторное закрытие безопасно.

Возможные причины:

- «Нет активной сети»;
- «Требуется вход в Wi‑Fi»;
- «Системный/Private DNS не отвечает»;
- «Не удалось разрешить адрес VPN-сервера»;
- «VPN-сервер не отвечает»;
- «DNS через VPN не отвечает»;
- «Конфигурация DNS несовместима с этой версией ядра».

Невыбранные приложения при всех этих переходах остаются вне TUN и не ждут десятисекундное восстановление.

## Настройки DNS

- **Автоматически** — DNS профиля, если он задан; после подтверждённой DNS-ошибки чистая попытка с DoH через proxy, затем последний резерв — DNS Android с предупреждением. При strict Private DNS используется только DNS Android.
- **DNS Android** — все стандартные DNS-запросы выбранных приложений через системную DNS-политику Android/Private DNS.
- **Защищённый через VPN** — DNS по TCP/UDP 53 выбранных приложений через DoH fallback и proxy outbound; strict Private DNS блокирует запуск этого режима.
- **Из JSON** — существующая DNS-секция используется без замены и fallback-цепочки. Если DNS-секции или серверов в профиле нет, runtime-копия получает минимальный local DNS Android; это не записывается в профиль и не включает DoH.
- **Только IPv4 через VPN** — включённая по умолчанию опция для Secure/DNS Android и managed-этапов Auto: подавляет AAAA только для доменов, направляемых через proxy; первая попытка с DNS профиля, direct/LAN и пользовательский JSON остаются без изменений. Для IPv6-only proxy-сайта опцию можно выключить, но WireGuard-профиль при этом обязан иметь собственный внутренний IPv6-адрес.
- **DNS-переопределение** — одна глобальная редактируемая пара `точный hostname → IPv4`, применяемая только в Secure/DNS Android и соответствующих managed-этапах Auto. Настройка включена по умолчанию как `ntc.party → 130.255.77.28`; пользователь может изменить пару или выключить её. Runtime добавляет отдельный `hosts` transport и точное DNS-правило после `reject`, но до правил выбора resolver. Первая попытка Auto с DNS профиля не меняется. Имя назначения, TLS SNI и HTTP Host не подменяются; правило маршрутизации сайта также не создаётся.

DNS-переопределение является таким же bounded global runtime-параметром, как режим DNS и `proxy_ipv4_only`: его единственным источником истины служит `ui_settings` DataStore, а сохранённый JSON профиля не переписывается. Поддерживается ровно одна запись, чтобы не создавать второй hosts-формат или неограниченный список вне профиля. В режиме «Из JSON» эта настройка сохраняется, но не применяется: пользовательская DNS-секция остаётся полностью самостоятельной. Диагностика показывает только активность override, но не копирует hostname или IP.

FakeIP находится только в расширенных экспериментальных настройках и выключен по умолчанию.

## Блокировка доменов

Managed-действие «Блокировать» добавляет domain rule-set в `dns.rules` с `action: reject` до правил выбора resolver. По умолчанию sing-box отвечает `REFUSED`; отдельный block DNS server не создаётся. Такое же правило обязательно остаётся в `route.rules` как защита при наличии domain/reverse mapping. Прямое обращение только к IP требует отдельного IP-set.

IP-only rule-set не вставляется в DNS-правила: адрес ещё неизвестен до DNS-ответа. Встроенный DoH/DoT приложения может скрыть домен от managed DNS, поэтому domain-only блокировка без соответствующего IP-set или reverse mapping не объявляется полноценным firewall. Глобальный sniff для компенсации не включаем.

## Честные ограничения

- Быстрое правило MVP перехватывает DNS только по TCP/UDP 53. Оно не ловит plaintext DNS на нестандартном порту, DoT/853 и встроенный DoH; такой трафик следует обычным route-правилам.
- DNS-переопределение действует только на перехваченный стандартный DNS. Встроенный DoH/DoT приложения может вернуть другой ответ или отсутствие записи; приложение честно показывает это ограничение и не пытается MITM/блокировать защищённый resolver.
- Android strict Private DNS применяется и к VPN network на поддерживаемых версиях ОС. Его DoT/853 обходит `hijack-dns` и `reverse_mapping`, поэтому при strict Auto автоматически сужается до кандидата «DNS Android», а Secure не запускается, пока strict настроен.
- mDNS использует multicast/5353 и не обслуживается правилом `port: 53 → hijack-dns`. Наличие `.local` в DNS-правилах помогает только тем запросам, которые приложение отправило обычному Android resolver; полноценная LAN/mDNS-совместимость проверяется отдельно.
- На Android ниже 10 platform DNS-интерфейс sing-box поддерживает только A/AAAA.
- DNS-выбор можно синхронизировать с доменными/rule-set правилами, но не с IP-CIDR до получения ответа.
- Доступ к `.local` и другим LAN-именам зависит от возможностей platform resolver и настроенного пользователем Private DNS; приложение не обходит strict Private DNS plaintext-запросом.
- `reverse_mapping` содержит не более 1024 IP→domain записей с TTL DNS-ответа и не очищается текущим `ResetNetwork()`. Поэтому при значимой смене сети/режима перезапускается core.
- Кнопка приложения не может очистить глобальный DNS/Private DNS cache Android; на API 29+ она может лишь запросить следующую диагностику без cache lookup.
- В режиме «Из JSON» ответственность за утечки и совместимость пользовательской DNS-схемы остаётся у конфигурации; приложение только валидирует и диагностирует её.
- Strict Private DNS уважается для Android resolver; приложение не может обещать одновременно принудительный собственный DoH и неизменённый system strict resolver, поэтому конфликт разрешается явной блокировкой, а не скрытым fallback.

## Версионирование и проверка

Для аудита CLI собран локально из точного commit `ff11f00…` с Go 1.26.4; `sing-box version` подтвердил встроенный revision, SHA-256 audit-бинарника — `845b9370443894f163176599aa63a231dbdae8f943e6d9e0e15804cb5fd1843c`. Повторный прогон дал 4/4 для хранящихся в репозитории эталонных JSON:

| Эталон | SHA-256 |
|---|---|
| [Android DNS](../testdata/dns/android-dns.json) | `74ed42ab142a16700710be544c0d3d8c4289924457df3e02259bf03a71b34f36` |
| [Auto, proxy-final](../testdata/dns/auto-proxy-final.json) | `7452e4d4f284557300ba79f7764359289cf9eb4668331c02826c9ea308cd5682` |
| [Auto, direct-final](../testdata/dns/auto-direct-final.json) | `531122a14a5665c6ce1be753be64f8c79da6a92de09afd1d78fea8dfd41d15d0` |
| [LKG hosts overlay](../testdata/dns/lkg-hosts.json) | `c0df41216c1eba5f11163bdc724c1458feec18392311d685b87c337fb37fa747` |

Это schema/graph fixtures, а не готовые пользовательские профили: `selected-proxy` в них заменён на `direct`, а probe/LKG адреса принадлежат зарезервированным example-диапазонам. CI проверяет именно схему, связи tag-ов и runtime-overlay.

Этим CLI также подтверждены:

- DNS Android;
- Auto с proxy-final;
- Auto с direct-final;
- `port: 53 → hijack-dns`;
- runtime `exact probe domains → selected outbound` без process/package lookup;
- DoH `fallback/parallel`;
- runtime `hosts.predefined` + `outbound.domain_resolver`.

Воспроизводимый audit-test временно копируется в `dns/transport/fallback` exact pinned checkout и запускается скриптом сборки без изменения сохранённого source tree. Он подтвердил: первый success не вызывает резервный transport; transport error вызывает; зависший первый transport исчерпывает общий context, поэтому второй получает уже истёкший deadline; `NXDOMAIN`, `SERVFAIL` и `REFUSED` без Go-ошибки не запускают следующий transport. Также подтверждены `172.19.0.1/30 → 172.19.0.2`, отказ для `/32`, полный capture обеих IP-family и потеря `172.19.0.2` при исключении RFC1918 `172.16.0.0/12`. Повторный `go test ./dns/... ./route/rule ./experimental/libbox` прошёл. В upstream fallback test-файлов нет; наш audit закрывает поведенческую часть P15, но не заменяет сравнение энергии и cache-burst на физических устройствах.

Source-аудит отдельно подтвердил, что libbox package override является append только для первого TUN, Android owner lookup в этом commit включён безусловно, platform `protect(fd)` пропускается рядом bind-полей, а pinned Android monitor может повторить одно обновление до десяти раз. Это не свойства JSON schema, поэтому `sing-box check` их обнаружить не способен.

Те же JSON принял скачанный upstream release binary, но его embedded revision — `ea48501…`, поэтому он служит только второй проверкой совместимости, а не источником истины. `sing-box check` доказывает корректность схемы и ссылок между объектами, но не исполняет Android `OpenTun`, platform resolver и per-app `VpnService.Builder`; их покрывают только Android-тесты.

При обновлении ядра CI должен:

1. принимать только полный 40-символьный commit SHA, а не плавающую branch или содержимое upstream release asset;
2. собрать libbox и CLI из одного commit и проверить, что `sing-box version` содержит ровно этот revision;
3. выполнить `sing-box check` для четырёх эталонов: DNS Android, Auto с proxy-final, Auto с direct-final и LKG overlay;
4. core audit-тестом зафиксировать семантику `sequential`/`parallel`, общий timeout и поведение RCODE; JVM-тестом проверить, что managed runtime генерирует `fallback/parallel`;
5. проверить `TunOptions.GetDNSServerAddress()`, полный IPv4/IPv6 capture и недостижимость DNS при конфликтующем route-exclude;
6. собрать AAR и скомпилировать наш platform adapter против него: Android reference не считается ABI-тестом;
7. negative-тестами preflight проверить ноль/несколько TUN, оба package-list в импортированном JSON, запрещённые bind-поля и partial routes;
8. запустить Android instrumented-тесты platform resolver, strict Private DNS, `protect(fd)`, port-53 hijack и allowlist: выбранное приложение через TUN, контрольное приложение вне TUN;
9. не выпускать APK при несовместимом JSON, изменившемся libbox API или несовпадении revision.

Любое обновление sing-box-extended является отдельной миграцией: сравниваются как минимум `dns/`, `route/`, `common/dialer/`, `experimental/libbox/`, `option/`, Android reference и release workflow. Приложение и встроенное ядро обновляются только вместе.

## Минимальная проверочная матрица

Это детализация пунктов P1, P4, P5, P6, P8, P9 и P15 из единого раздела [«Потом проверить»](ARCHITECTURE.md#потом-проверить--открытые-вопросы). Она задаёт тесты, но не добавляет новых архитектурных решений.

- Android 8, 9, 10 и современная версия Android;
- Private DNS: off, automatic, strict working, strict broken; Auto при strict working сужается до «DNS Android» и подключается, Secure и любой режим при strict broken обязаны остановиться до `establish()`, Android DNS — сохранить системное поведение;
- Wi‑Fi, cellular, Wi‑Fi ↔ cellular, IPv6/NAT64;
- captive portal;
- заблокирован системный DNS, есть/нет last-known-good;
- Auto с DNS профиля: успех без Android/DoH, DNS error/timeout с переходом к DoH, затем к Android; HTTPS/proxy/JSON-ошибка не должна запускать DNS fallback; после каждой неудачной попытки ноль старых core/PFD/callback;
- все пять DoH стартуют на cache miss: один успешен/быстро недоступен/завис, все недоступны, первый корректный ответ выигрывает; отдельно проверяются гонки с `NXDOMAIN`/`SERVFAIL`/`REFUSED`;
- proxy доступен по hostname и по IP;
- каждый поддерживаемый MVP outbound отдельно проходит socket-protection/loop test; неаудированный backend отклоняется до `establish()`;
- UDP и TCP DNS на 53; DoT/853, DoH/443, custom DNS port и mDNS/5353;
- allowlist пуст, пакет удалён до и во время Builder, один/несколько выбранных пакетов, общий UID, импортированный JSON пытается задать другой package list;
- параллельная проверка: выбранное приложение через VPN и невыбранное напрямую;
- симулированный `protect(fd) == false` обязан сорвать подключение;
- ноль/два TUN, partial IPv4/IPv6 routes, RFC1918 route-exclude и запрещённые bind/mark/netns-поля отклоняются preflight;
- health-check в режиме `route.final = direct` всё равно проходит через выбранный outbound;
- остановка core, ошибка после `OpenTun`, revoke VPN, смена профиля, смена DNS-режима и проверка закрытия PFD/reverse mapping;
- повторяющиеся и устаревшие network callbacks не запускают несколько core restart;
- benchmark owner/process lookup на API 26 и современном Android; отдельно считаются TCP/UDP flow/s, CPU и battery impact;
- приложение со стандартным DNS и приложение со встроенным DoH.

### Состояние автоматизированной матрицы — 22 июля 2026

- DNS-матрица входит в прошедшие 66/66 instrumented-тестов на AVD API 26/29 и
  текущие 67/67 на API 36.
- JVM-тесты подтверждают конечный порядок Auto `профиль → DoH → Android`, отсутствие fallback у явных режимов и запрет переключения на non-DNS ошибке. Новый instrumented-тест clean restart DoH→Android с fault injection компилируется; физическое подтверждение входит в Test 21 и пока не считается закрытым gate.
- Проверены legacy `Network.getAllByName()` и API 29+ `DnsResolver`, Private DNS off/automatic/strict working/strict broken, включая поломку strict во время активного TUN, реальные переключения emulator Wi-Fi ↔ cellular, IPv6, fresh/emergency/expired/no LKG, managed DoH success, недоступный proxy/managed DoH, мёртвый внутренний DNS и немедленный возврат обычной Android network после stop. Третий OpenDNS закреплён exact fixture и JVM-тестом; физический сценарий с блокировкой первых двух endpoints остаётся обязательной проверкой.
- Captive-portal ветка проверена детерминированной подстановкой platform state до `establish()`. Это доказывает fail-close кода, но не заменяет настоящий портал Android validation.
- Эмуляторная сеть dual-stack не является IPv6-only/NAT64. Поэтому настоящий captive portal, NAT64 и повтор blocked-DNS/LKG/DoH на физических Wi-Fi/mobile сетях остаются открытой частью Gate 3.
- Отдельный stress на API 29/36 прошёл 100 connect/stop и 50 Wi-Fi/cellular transitions с ровно одним core restart на переход.

## Источники

- [Android: per-app VPN, allowlist и always-on](https://developer.android.com/develop/connectivity/vpn)
- [Android: VpnService.Builder и addDnsServer](https://developer.android.com/reference/android/net/VpnService.Builder)
- [Android: protect() и underlying networks](https://developer.android.com/reference/android/net/VpnService)
- [Android: Private DNS в LinkProperties](https://developer.android.com/reference/android/net/LinkProperties)
- [Android: DnsResolver API](https://developer.android.com/reference/android/net/DnsResolver)
- [Android: foreground service type `systemExempted`](https://developer.android.com/develop/background-work/services/fgs/service-types#system-exempted)
- [Android: системный DNS resolver](https://source.android.com/docs/core/ota/modular-system/dns-resolver)
- [AOSP Android 10: normal Network уважает Private DNS, bypass требует отдельного hidden copy](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/core/java/android/net/Network.java)
- [AOSP: strict Private DNS поддерживается на VPN network](https://android.googlesource.com/platform/frameworks/base/+/0929eb918071c1e76fd41b677af0973412f8a098%5E%21/)
- [AOSP CTS: strict Private DNS на VPN](https://android.googlesource.com/platform/cts/+/b5f64d0f81233ab8b8319b08e0409aaef1dc5072/hostsidetests/net/app/src/com/android/cts/net/hostside/VpnTest.java)
- [sing-box: DNS](https://sing-box.sagernet.org/configuration/dns/)
- [sing-box: Local DNS на Android](https://sing-box.sagernet.org/configuration/dns/server/local/)
- [sing-box: DNS over HTTPS](https://sing-box.sagernet.org/configuration/dns/server/https/)
- [sing-box: DNS rule action](https://sing-box.sagernet.org/configuration/dns/rule_action/)
- [sing-box: route rule action и hijack-dns](https://sing-box.sagernet.org/configuration/route/rule_action/)
- [sing-box: FakeIP](https://sing-box.sagernet.org/configuration/dns/server/fakeip/)
- [sing-box: cache file](https://sing-box.sagernet.org/configuration/experimental/cache-file/)
- [Cisco Umbrella: OpenDNS DNS-over-HTTPS endpoint и адреса](https://umbrella.cisco.com/blog/enhancing-support-dns-encryption-with-dns-over-https)
- [Проверенный commit sing-box-extended](https://github.com/shtorm-7/sing-box-extended/tree/ff11f007ec798136a5de258f947a4f34011a37ea)
- [Pinned source: libbox TunOptions](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/experimental/libbox/tun.go)
- [Pinned source: platform DNS](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/experimental/libbox/dns.go)
- [Pinned source: fallback strategies](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/dns/transport/fallback/strategy.go)
- [Pinned source: DNS client/cache](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/dns/client.go)
- [Pinned source: DNS router/reset/reverse mapping](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/dns/router.go)
- [Pinned source: libbox package override](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/daemon/instance.go)
- [Pinned source: Android owner lookup activation](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/route/router.go)
- [Pinned source: owner lookup before route matching](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/route/route.go)
- [Pinned source: protected/default dialer](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/common/dialer/default.go)
- [Pinned source: libbox platform wrapper](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/experimental/libbox/service.go)
- [Pinned source: TUN inbound lifecycle](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/protocol/tun/inbound.go)
- [Pinned source: release workflow](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/.github/workflows/build.yml)
- [Pinned Android reference: VPNService](https://github.com/SagerNet/sing-box-for-android/blob/eb87216961321de1802e1355c470242f2ed5faa8/app/src/main/java/io/nekohasekai/sfa/bg/VPNService.kt)
- [Pinned Android reference: LocalResolver](https://github.com/SagerNet/sing-box-for-android/blob/eb87216961321de1802e1355c470242f2ed5faa8/app/src/main/java/io/nekohasekai/sfa/bg/LocalResolver.kt)
- [Pinned Android reference: underlying Network listener](https://github.com/SagerNet/sing-box-for-android/blob/eb87216961321de1802e1355c470242f2ed5faa8/app/src/main/java/io/nekohasekai/sfa/bg/DefaultNetworkListener.kt)
- [Pinned Android reference: interface monitor](https://github.com/SagerNet/sing-box-for-android/blob/eb87216961321de1802e1355c470242f2ed5faa8/app/src/main/java/io/nekohasekai/sfa/bg/DefaultNetworkMonitor.kt)
- [Pinned Android reference: platform owner lookup](https://github.com/SagerNet/sing-box-for-android/blob/eb87216961321de1802e1355c470242f2ed5faa8/app/src/main/java/io/nekohasekai/sfa/bg/PlatformInterfaceWrapper.kt)
