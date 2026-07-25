# Zapret KVN Android — главный план архитектуры

> **Читать первым.** Это главный документ для человека или ИИ, продолжающего проект. Он фиксирует границы MVP, владельцев данных, порядок реализации и запрещённые усложнения. Детали сетевой политики находятся в [ROUTING_ARCHITECTURE.md](ROUTING_ARCHITECTURE.md), детали DNS и Android VPN — в [DNS_ARCHITECTURE.md](DNS_ARCHITECTURE.md), rootless hardening — в [VPN_HIDING_ARCHITECTURE.md](VPN_HIDING_ARCHITECTURE.md), границы входных форматов — в [IMPORT_FORMATS.md](IMPORT_FORMATS.md), рабочие TODO и gates — в [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

| Поле | Значение |
|---|---|
| Статус | Этапы 0–7 и automated Gate 8 реализованы; внешний signing/release и физические release-gates открыты |
| Последний аудит | 22 июля 2026 года |
| Минимальная ОС | Android 8.0, API 26 |
| Устройства MVP | Только телефоны |
| Ядро | `sing-box-extended` tag `v1.13.14-extended-2.5.2` |
| Точный commit | `ff11f007ec798136a5de258f947a4f34011a37ea` |
| Текущий workspace | Android app + изолированные `app-updater`/`network-bootstrap`/import libraries, один VPN service, pinned libbox и packaged `.srs` |

## Порядок доверия

Если документы кажутся противоречивыми:

1. Этот файл определяет продукт, объём MVP и порядок работ.
2. Routing ADR определяет per-app, domain/IP/rule-set и `direct/proxy/reject`.
3. DNS ADR определяет `VpnService`, DNS, Private DNS, bootstrap и сетевой lifecycle.
4. VPN Hiding ADR определяет допустимый rootless hardening effective runtime.
5. Политика форматов определяет допустимые преобразования URI/subscription в JSON.
6. Поведение ядра определяется зафиксированным исходным commit и проверяемым patchset,
   SHA-256 которого входит в build/release/diagnostic metadata.

Нельзя молча придумывать второй формат конфигурации, скрытые правила или исправлять профиль без отражения в effective JSON. Обнаруженное расхождение сначала фиксируется в соответствующей ADR и тесте.

## Цель MVP

Zapret KVN — независимый нативный Android-клиент sing-box-extended:

- импортирует JSON, ссылки, подписки, QR, буфер и файл;
- позволяет подключиться без ручной работы с JSON;
- сохраняет настоящий sing-box JSON и неизвестные extended-поля;
- даёт простой GUI для наиболее частых полей и raw-редактор для остальных;
- для новых managed-профилей по умолчанию направляет RU и LAN напрямую, а остальное через VPN только у выбранных приложений, экономя VPN-сервер;
- распространяется готовым APK только через GitHub Releases.

Основной приоритет: корректность → простота → скорость → дополнительные функции.

## Канонические решения

| Область | Решение MVP |
|---|---|
| UI | Kotlin, Jetpack Compose, Material 3 |
| Навигация | 4 нижние вкладки: Главная, Профили, Маршрутизация, Настройки |
| Архитектура проекта | `app` владеет продуктом; `app-updater` изолирует release/download/verification; `network-bootstrap` изолирует Android network/DNS; libraries не зависят от app |
| Состояние UI | `ViewModel` + `StateFlow` |
| DI | Один ручной `AppContainer`, без Hilt/Koin |
| Процесс | Один Android process; `VpnService`, UI и libbox без отдельного `android:process` |
| VPN | Один foreground `VpnService`, один Android TUN, один libbox instance |
| Конфигурация | Настоящий sing-box JSON — единственная сетевая политика профиля |
| Импорт | Вход сразу преобразуется в JSON; Clash YAML и неподтверждённые URI не входят в MVP |
| Настройки | DataStore только для глобальных/UI-настроек |
| Профили | JSON-файлы через `AtomicFile`, одна резервная копия |
| Метаданные | Маленький app-private index без копии route/DNS-конфигурации |
| Приложения | Android allowlist применяется один раз в `VpnService.Builder` |
| Маршруты | sing-box `route.rules`: `direct`, selected proxy или `reject` |
| GEO/списки | Локальные binary `.srs` rule-set внутри APK |
| DNS | FakeIP выключен; Android bootstrap; Auto: DNS профиля → Android → DoH |
| Rootless hardening | Один compile-time/runtime overlay без process/thread/polling; localhost endpoints закрыты по умолчанию |
| Go runtime | `Libbox.SetMemoryLimit(false)`: стандартный GC, без Android GC=10 |
| Телеметрия | Только session totals; 1 Гц лишь пока главная видима |
| Ядро | Встроено в APK и обновляется только вместе с приложением |
| Обновления | GitHub Releases, Stable/Beta, APK + SHA-256 |
| Фоновая работа | Только активный VPN service; нет фоновой синхронизации |

Для targetSdk 34+ единственный VPN service объявляет foreground type `systemExempted`: официальная таблица Android прямо включает в допустимые случаи VPN-приложения, настроенные через системный VPN consent. `specialUse` не нужен и не создаёт лишнюю Play Console review-категорию. Lint пока требует `SCHEDULE_EXACT_ALARM` без учёта VPN-исключения, поэтому только на service стоит точечный `tools:ignore="ForegroundServicePermission"`; alarm-разрешение намеренно не добавляется. См. [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#system-exempted).

## Неподвижные инварианты

1. Физически существует ровно один Android TUN.
2. Невыбранный UID не попадает в TUN и не виден libbox.
3. Выбранный UID полностью попадает в TUN по IPv4 и IPv6.
4. Android решает только область приложений; sing-box решает только назначения и outbound.
5. Быстрые пресеты не создают дублирующие `package_name` route-правила.
6. В управляемом Android TUN нет GEO/LAN route exclusions.
7. `direct` проходит через локальный core, но не использует VPN-сервер.
8. Новая блокировка использует `action: reject`, а не legacy block outbound.
9. Блокировка действует только на приложения, уже включённые в область VPN.
10. FakeIP, глобальный sniff, root и второй VPN/TUN по умолчанию запрещены.
11. Любая ошибка после открытия TUN полностью закрывает PFD и core.
12. Статус «Подключено» появляется только после DNS и HTTPS health-check.
13. Приложение не держит `WakeLock`, не использует alarm/job/WorkManager и не делает периодический health-check.
14. «Автоматически» сначала пробует DNS профиля, затем DNS Android и только после подтверждённой DNS-ошибки — DoH; каждый переход создаёт чистую bounded-сессию без фонового retry.
15. Скорость не показывается в уведомлении; status/log streams существуют только пока нужен соответствующий экран.
16. Управляемый профиль не создаёт NTP, remote rule-set, периодический URL-test или явный persistent keepalive сверх defaults выбранного протокола.
17. `VpnService.Builder.allowBypass()` не вызывается; rootless hardening не обещает скрыть системный TUN/`TRANSPORT_VPN`.
18. Updater не создаёт постоянного GitHub route: VPN overlay существует только во время одной повторной операции, совпадает одновременно по package приложения и GitHub domain suffix и всегда снимается через восстановление сессии.

## Реальный сетевой путь

```text
невыбранное приложение
    └─ Android system network
       TUN, libbox, DNS и VPN-сервер его не видят

выбранное приложение
    └─ Android VpnService allowlist
        └─ один TUN
            └─ libbox / sing-box
                ├─ reject  → локальный отказ
                ├─ direct  → underlying Android network
                └─ proxy   → выбранный VPN outbound
```

Это максимально раннее безопасное отсечение на обычном Android без root. Приложение можно исключить до TUN, но нельзя запретить ему сеть публичным `VpnService` API: исключение означает direct. Домен появляется только после DNS/sniff/reverse mapping, поэтому доменное решение до sing-box невозможно.

## Последовательность подключения

Один `VpnController` и один service-lock выполняют запуск строго последовательно:

1. Проверить выбранный профиль и непустую allowlist.
2. Получить системное VPN-разрешение при необходимости.
3. Зафиксировать generation token и текущую underlying сеть через event-driven Android callback с
   `NET_CAPABILITY_NOT_VPN`; собственный TUN не может стать underlying network.
4. Прочитать профиль и создать runtime-копию JSON.
5. Проверить один TUN, полные IPv4/IPv6 routes, rule-set assets и запрещённые socket bind-поля.
6. Очистить импортированные `include_package`/`exclude_package` только в runtime-копии и применить одну глобальную allowlist.
7. Добавить только разрешённые runtime overlays: health-check, при необходимости bootstrap LKG, минимальный Android DNS для профиля без DNS и внутренний WireGuard MTU, если он отсутствует.
8. Выполнить `libbox CheckConfig()` до `establish()`.
9. Проверить captive portal/Private DNS и разрешить адрес активного proxy-сервера через underlying Android network.
10. Создать platform adapter и локальный libbox command server в том же Android process.
11. Вызвать `startOrReloadService()`: libbox синхронно вызывает `OpenTun(TunOptions)`, а adapter создаёт один `VpnService.Builder`, применяет приложения, адреса, полные routes и внутренний DNS.
12. `Builder.establish()` возвращает Android PFD; libbox дублирует его FD и запускает единственный core поверх этого же TUN. Второго адаптера или второго TUN здесь нет.
13. Проверить DNS и HTTPS через Android TUN. Для HTTPS runtime-копия включает TLS sniff только для package Zapret KVN и порта 443, после чего точные probe-hostnames направляются в выбранный outbound. Это доказывает настоящий путь через proxy без глобального sniff и без отдельного WireGuard-only gate. В Auto подтверждённая именно DNS-ошибка полностью закрывает текущие core/PFD/callbacks и запускает следующий кандидат `профиль → Android → DoH`; максимум три попытки внутри общего deadline 45 секунд. Ошибка data-plane, JSON или HTTPS-пути не считается DNS-ошибкой и не запускает переключение.
14. Только после успеха показать «Подключено»; сбор 1 Hz session-only статистики начать лишь при видимой главной.

Любая ошибка или revoke выполняет один идемпотентный stop без ожидания общего lifecycle mutex: generation немедленно инвалидируется, текущий lifecycle job отменяется, Android TUN/PFD закрывается первым, затем последовательно закрываются callbacks, command clients, libbox service, command server и network monitor. Терминальный `Error`/`Stopped` публикуется только после этого cleanup, чтобы немедленный повторный запуск не пересёкся со старым foreground startId на Android 8–9. Невыбранные приложения всё это время продолжают работать напрямую.

Включённая по умолчанию настройка «Только IPv4 через VPN» влияет только на сгенерированные DNS-правила доменов, которые effective route отправит в proxy. Она действует в явных Secure/DNS Android и после перехода Auto к этим managed-этапам, но не меняет первую попытку с DNS профиля, direct/LAN, IPv6-маршрут TUN, сохранённый профиль или режим «Из JSON». Это предотвращает выбор AAAA у dual-stack сайтов для WireGuard-профиля только с внутренним IPv4. IPv6-only трафик требует настоящего IPv6-адреса внутри WireGuard-профиля; отключение фильтра не может добавить его автоматически.

Baseline underlying-сети остаётся неизменяемым на всю сессию. Callback-flap
`A → B → A` отменяет pending restart; после 750 мс debounce итоговый policy key
проверяется ещё раз. Контролируемый restart выполняется только если сеть или
DNS/captive policy действительно остались отличными от baseline.

## Экраны

```text
Главная
├─ Состояние и большая кнопка подключения
├─ Профиль и выбранный сервер
├─ Внешний IP, пинг, время сессии
├─ Download / Upload
└─ Лёгкий график последних 60 секунд

Профили
├─ Подписки
├─ Импортированные
├─ Файлы
└─ Добавить: QR / буфер / URL / файл

Маршрутизация
├─ Область VPN: выбранные приложения
├─ Правило трафика: preset
├─ Читаемый итог двух решений
├─ Правила: VPN / напрямую / блокировать
└─ Расширенные: rule-set / raw JSON

Настройки
├─ Оформление и DNS
├─ Обновления
├─ Скрытие VPN
├─ Диагностика
├─ Сообщество
└─ О приложении
```

### Главная

Компактная карточка показывает максимум полезного без тяжёлого dashboard:

- состояние;
- профиль/сервер;
- IP и пинг;
- время;
- получено/отправлено;
- две тонкие линии скорости за 60 секунд.

Статистика хранится только в памяти текущей сессии. Суммарные счётчики читаются раз в секунду только пока главная находится в lifecycle `STARTED`; при уходе с экрана stream и ticker закрываются. IP запрашивается один раз после подключения; пинг — при подключении и вручную. Постоянное уведомление показывает только состояние, а не живую скорость.

В production runtime всегда создаётся внутренний `clash_api` traffic manager, но
без `external_controller` или сетевого listener. Постоянный command client слушает
только event-driven группы. Отдельный `CommandStatus` client с интервалом 1 секунду
существует лишь при одновременно `STARTED` Activity и выбранной вкладке «Главная»;
его закрытие прекращает ticker в самом core. `CommandConnections` не создаётся.
`CommandLog` кратко захватывает bounded core-лог во время connect health-check,
затем существует только при открытом экране диагностики.

Пинг на карточке — RTT одного ICMP Echo до hostname фактически выбранного
VPN-сервера. Сокет привязывается к underlying `Network` через Android
`Network.bindSocket()`, поэтому пакеты не проходят через TUN. HTTPS health-check
только подтверждает работоспособность маршрута и никогда не публикуется как пинг.
Если сервер блокирует ICMP, интерфейс показывает «—» без подмены значением
TCP/HTTPS URL-test. Один пакет отправляется при подключении и один — только по
ручному действию пользователя; периодического ping-loop нет. Внешний IP
дополнительно запрашивается один раз после успешного подключения или ручной смены
сервера через dual-stack [ipify endpoint](https://www.ipify.org/); ошибка этого
неблокирующего запроса не отключает VPN и не запускает retry. Значения, 60 точек
графика и URL-test history живут только до остановки текущей сессии.

Состояния: нет профиля, нет выбранных приложений, выключено, проверка, подключение, подключено, отключение, ошибка. Ошибка ведёт в «Диагностику».

### Маршрутизация

Пользователь видит не две конкурирующие «маршрутизации», а две карточки:

```text
Область VPN
Только выбранные приложения · YouTube, Discord · 7 >
Блокировать приложения · 0 >

Правило трафика
Россия напрямую · остальное через VPN >

Итог
выбранные: RU → напрямую, остальное → VPN
остальные приложения: напрямую, вне VPN
destination-блокировка: только для выбранных приложений
полная блокировка приложений: отдельный пустой по умолчанию список
```

Список приложений открывается отдельным полноэкранным picker с поиском. Список правил остаётся на том же экране в «Расширенных настройках».

Редактор правила имеет ровно три обычных действия:

- «Через VPN»;
- «Напрямую»;
- «Блокировать».

Глобальные presets:

- Всё через VPN;
- Обход LAN;
- Только выбранные сайты;
- Россия напрямую, остальное через VPN;
- Россия через VPN, остальное напрямую;
- Пользовательский.

Whole-app block не входит в основной MVP: он потребовал бы третьего состояния каждого приложения и `package_name → reject`. Advanced JSON может содержать такие правила под ответственность пользователя.

## Хранение и источник истины

```text
DataStore
├─ тема / канал обновлений / bounded DNS runtime-настройки
├─ активный профиль
└─ глобальная per-app allowlist и include/exclude mode

files/profiles/
├─ index.json       — только id, название, source и created/updated timestamps
├─ <id>.json        — настоящий sing-box JSON
└─ <id>.json.bak    — одна предыдущая версия

files/rule-sets/
├─ manifest.json
└─ *.srs            — проверенные встроенные binary rule-set

cache/
└─ временный APK и диагностический экспорт
```

`index.json` содержит только UI/import metadata и не дублирует DNS, outbound или route rules. Поэтому он не является вторым сетевым форматом.

Профиль и index записываются атомарно. Для профиля `AtomicFile` сначала формирует отдельный `<id>.json.atomic`, затем два rename оставляют либо прежний читаемый `<id>.json`, либо новый `<id>.json` и ровно один `<id>.json.bak`. Отдельный staging нужен потому, что служебный suffix `.bak` самого platform `AtomicFile` конфликтовал бы с постоянным пользовательским backup. При запуске незавершённый staging удаляется, а отсутствующий основной файл восстанавливается из backup.

До create/update/restore вызывается native `Libbox.checkConfig()`. Файл и буфер ограничены 4 МБ; файл читается только после ответа системного `OpenDocument`, явного Android `ACTION_VIEW` либо `ACTION_SEND` с одним потоковым URI, буфер — только в обработчике явного нажатия и на main thread для совместимости с API 26. Внешний intent принимает только выданный другим приложением URI и ведёт в тот же preview, без автосохранения и автоподключения. Credentials никогда не копируются в index или DataStore.

Глобальный include allowlist хранится отдельно в DataStore `vpn_scope`. Флаг `initialized` отличает первый запуск от уже созданного списка: установленные popular suggestions, Android-браузеры, обработчики схемы `tg://` и ссылок YouTube выбираются при первой инициализации. Версия suggestions разрешает ровно одну миграцию новой группы известных package: установленные ChatGPT/Claude/Gemini и связанные AI/media/productivity-приложения один раз добавляются к существующему include-списку после обновления. В exclude-режиме они не добавляются к прямому обходу, потому что неотмеченные приложения уже входят в VPN. После миграции ручное снятие не отменяется и приложение не возвращается автоматически. Каталог пакетов читается один раз при создании `AppsViewModel` и вручную при открытии picker; фонового polling и таймера нет. Собственный package приложения отбрасывается при чтении/записи DataStore и скрыт из каталога. Полный список нужен основной per-app функции, обрабатывается только локально и никогда не попадает в сеть, аналитику или диагностику.

Перед будущим `Builder.establish()` единственный `VpnAppScopePreflight` нормализует пользовательский список и отбрасывает отсутствующие/отключённые package до вызова Builder. Они попадают только в bounded diagnostic log и не мешают запуску, если осталось хотя бы одно доступное приложение. Нулевой итоговый список блокирует запуск. Затем preflight добавляет доступные пользовательские package и внутренний package Zapret KVN через `addAllowedApplication()`. Любое исключение Builder для существующего package возвращает блокирующий результат; частично заполненный Builder после ошибки не используется. Внутренний package существует только в effective platform allowlist для health-check и никогда не сохраняется как пользовательский выбор.

JSON редактируется через `kotlinx.serialization.json` как дерево. Неизвестные поля сохраняются. После GUI-изменения могут исчезнуть комментарии и исходные отступы; raw editor показывает это заранее.

Managed presets атомарно изменяют настоящий JSON. Runtime overlay не сохраняет произвольные пользовательские правила и всегда доступен в redacted-диагностике.

Подписки обновляются только вручную. Нет WorkManager, таймеров или скрытого сетевого refresh.

## Профили, серверы и переключение

Один профиль — один настоящий sing-box JSON. Proxy-серверы находятся в его массиве `outbounds`; WireGuard/AmneziaWG 2.0 использует нативный массив sing-box `endpoints`. Отдельной таблицы серверов, INI runtime-слоя и связанного набора полноразмерных JSON-шаблонов нет.

Профиль, созданный из одиночной ссылки, всё равно получает managed `selector` с одним сервером. Следующую одиночную ссылку можно сохранить новым профилем либо явно добавить сервером в существующий managed-профиль. Одна подписка создаёт один профиль-группу: несколько server outbounds и один основной selector. У создаваемого приложением selector стабильный tag `zapret-proxy`; managed route и DoH ссылаются на него.

Упрощённый фрагмент:

```json
{
  "outbounds": [
    {
      "type": "selector",
      "tag": "zapret-proxy",
      "outbounds": ["server-a", "server-b"],
      "default": "server-a",
      "interrupt_exist_connections": true
    },
    {
      "type": "vless",
      "tag": "server-a",
      "server": "vpn-a.example",
      "server_port": 443,
      "uuid": "<UUID>"
    },
    {
      "type": "vless",
      "tag": "server-b",
      "server": "vpn-b.example",
      "server_port": 443,
      "uuid": "<UUID>"
    }
  ],
  "route": {
    "final": "zapret-proxy"
  }
}
```

GUI получает список групп и текущий выбор из libbox. При работающем VPN переключение вызывает `CommandClient.SelectOutbound("zapret-proxy", serverTag)` без пересоздания TUN/core. `interrupt_exist_connections: true` закрывает только соединения из внутренней `interruptGroup` этого selector. Отдельный route в outbound `direct` в эту группу не входит, а невыбранные UID вообще отсутствуют в TUN; поэтому оба пути не затрагиваются. Это подтверждено [реализацией exact selector](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/protocol/group/selector.go#L145-L202) и Android integration-тестом switch без смены TUN.

Выбор сохраняется атомарным изменением `selector.default` в самом JSON после `CheckConfig()`. `experimental.cache_file` и DataStore для выбранного сервера не используются. Если runtime-переключение не удалось, выполняется один контролируемый restart уже с проверенным JSON. Переключение целого профиля всегда делает restart, потому что у профиля могут отличаться DNS, routes и TUN-настройки.

Raw JSON не нормализуется скрыто. В режиме «Из JSON» GUI показывает существующие selector-группы как есть. Для включения managed DNS/routing пользователь явно выбирает существующий основной selector либо разрешает создать `zapret-proxy`. Единственное отдельное исключение — явно описанный глобальный rootless overlay из [VPN Hiding ADR](VPN_HIDING_ARCHITECTURE.md): при включённой защите локальные control endpoints удаляются из runtime-копии, а non-TUN inbound блокирует запуск. Сохранённый JSON не меняется, effective-результат отражается в диагностике.

Полные JSON-шаблоны не являются долгоживущими данными. `ManagedProfileFactory` только один раз собирает начальный JSON из маленького base builder, protocol outbound builder и selector builder; после сохранения источником истины становится получившийся JSON. При ручном обновлении подписки выбранный server tag сохраняется, если он всё ещё существует; иначе выбирается первый доступный сервер и показывается уведомление.

Профили с credentials находятся только в app-private storage; Android Auto Backup для них выключен. Экспорт диагностики всегда redacted.

## Приложения

Режим по умолчанию — include:

- пользователь выбирает приложения, которые должны войти в VPN;
- Zapret KVN добавляет собственный package внутренне для health-check и служебных операций через VPN;
- пустой итоговый список запрещает запуск;
- исчезнувший/отключённый package пропускается с записью в bounded diagnostic log; запуск блокируется, только если доступных выбранных приложений не осталось;
- Builder-ошибка существующего package отменяет запуск целиком;
- остальные приложения идут напрямую.

Advanced exclude-mode поддерживается, потому что он был выбран в требованиях, но не является default. Пустой exclude-list всегда блокирует запуск: иначе весь телефон неожиданно войдёт в VPN.

В APK хранится небольшой список подсказок: Instagram, YouTube и YouTube Music (официальные, ReVanced, ReVanced Extended, Vanced и Morphe), Telegram Stable/Beta/Direct, Telegram X, ZaStoGram, распространённые Telegram-форки, WhatsApp, Discord, Signal, ChatGPT, Claude, Gemini, Perplexity, Copilot, DeepSeek, Grok, Suno, Spotify, Notion и известные браузеры, включая Chromium/Ultimatum; TikTok исключён. Дополнительно при первой инициализации Android сообщает все установленные обработчики `CATEGORY_APP_BROWSER`, браузерных HTTPS-ссылок, схемы `tg://` и ссылок `youtube.com`/`youtu.be`/`vnd.youtube`, поэтому неизвестный заранее браузер, Telegram- или YouTube-клиент тоже выбирается. Произвольные неизвестные приложения автоматически не добавляются. Системные скрыты до нажатия «Показать системные».

После импорта профиль сохраняется без автоматического подключения. Диалог «Выбрать приложения» показывается только при инициализированной allowlist с нулём выбранных package. Снятие выбора с одного приложения при наличии остальных окно не вызывает. Исчезнувший или отключённый package пропускается с bounded diagnostic warning; запуск блокируется только тогда, когда доступных выбранных приложений не осталось. Если хотя бы одно приложение выбрано, импорт завершается обычным snackbar без лишнего окна. После очистки defaults скрыто не восстанавливаются.

## Routing и rule-set

- Legacy `geoip`/`geosite` не генерируются: в sing-box 1.13 они удалены.
- Используются domain/IP `route.rule_set`.
- Встроенные presets зависят только от локальных `.srs`, входящих в APK.
- Нет managed remote rule-set, отдельного updater или `experimental.cache_file`.
- `.srs` обновляются только вместе с APK и проверяются SHA-256.
- Rule-set содержит совпадения, но не выбирает outbound: действие и порядок остаются в JSON.
- Пользовательские inline/local/remote rule-set разрешены только как явный JSON-сценарий.

Domain block создаёт DNS `reject` и route `reject`. IP block создаёт только route `reject`. Глобальный sniff ради блокировки не включается. Встроенный DoH приложения может скрыть domain-only блокировку; полноценный firewall/ad blocker не обещается.

Полная блокировка приложения — отдельная политика DataStore, а не правило профиля.
В include-режиме её пакеты добавляются к Android TUN boundary, в exclude-режиме не
могут одновременно быть direct-исключениями. Runtime-копия ставит для них первые
DNS/route `package_name → reject`; сохранённый JSON не изменяется.

## DNS

Режимы GUI:

- **Автоматически** — выбранный DNS профиля; при подтверждённой DNS-ошибке чистая попытка с DNS Android, затем последняя попытка с DoH через proxy;
- **DNS Android** — системная DNS/Private DNS политика;
- **Защищённый через VPN** — стандартный DNS выбранных приложений через DoH/proxy;
- **Из JSON** — существующая DNS-секция не переписывается; если секции/серверов нет, runtime-копия получает один local DNS Android, `hijack-dns` и `default_domain_resolver`, а сохранённый JSON остаётся прежним.

FakeIP выключен. Системные настройки Android приложение не меняет и Private DNS не обходит.

При strict Private DNS Auto не падает, а сужает цепочку кандидатов до «DNS Android» с предупреждением в логе — системный DoT уважается, пользователь ничего не настраивает. Secure при strict не запускается (`DNS-110`): он явно обещает DoH, а системный DoT обошёл бы port-53 hijack и reverse mapping. Явные режимы не получают скрытого fallback. Полная политика, bootstrap cache и fail-close описаны в DNS ADR.

## Проверка простоты и скорости

| Возможная «оптимизация» | Результат аудита |
|---|---|
| Android allowlist | Используем: это единственное бесплатное отсечение до TUN по приложению |
| GEO через Android routes | Отклонено: 10 842 exclusions на API 33+ и около 35 041 complement routes на API 26–32 |
| `route_exclude_address_set` | Exact libbox не разворачивает set в Android platform TUN |
| Отдельные TUN для direct/proxy/block | Нельзя и архитектурно лишнее |
| Global sniff | Отклонён: читает первые пакеты и замедляет весь трафик |
| FakeIP | Отклонён по умолчанию: кэш и per-app edge cases |
| Root/TProxy/iptables | Вне неинвазивного Android MVP |
| Proxy-only mode | Быстрее только для приложений с ручным SOCKS/HTTP; ломает «скачал и работай» |
| Dynamic core/rule updates | Отклонены: больше кода, мусора и точек отказа |
| Remote managed rule-set | Отклонены: сеть может заблокировать запуск VPN |
| Fork ради owner lookup | Пока отклонён: exact core вызывает lookup безусловно, но сначала нужен device benchmark |
| Три DoH параллельно | Только последний Auto-этап или явный Secure: exact core с `sequential` не достигает резерва, если первый DoH занял общий deadline; на успешных profile/Android-этапах DoH-запросов нет |
| Отдельный VPN-процесс | Отклонён до профилирования: добавляет IPC/process overhead и усложняет lifecycle |

Pinned Android AAR собирается с `with_gvisor`. Для managed TUN поле `stack` не переопределяем: exact core выбирает upstream default `mixed`. Exact pinned core при пустом `tun.mtu` выбирает на Android `9000`, а userspace WireGuard — собственный меньший MTU. Поэтому стандартный runtime задаёт внешний TUN явно: `1500` для остальных профилей и `min(1500, effective userspace WireGuard endpoint MTU)` для конфигураций с userspace WireGuard. Endpoint без MTU получает Android fallback `1280`; это совпадает с default официального AmneziaWG Android. Режим «По профилю» сохраняет явно заданный TUN MTU, но при отсутствии поля также использует MTU userspace WireGuard endpoint и не возвращается к несовместимому 9000. Stored JSON не меняется. Исправление основано на системном logcat stable 0.2.3, где фактический Android TUN был `9000` при endpoint `1280`; окончательный физический A/B `1280 ↔ 9000` остаётся обязательным.

Runtime-копия каждого профиля, включая raw JSON и endpoint-only WireGuard/AWG, всегда получает `log.level=warn`; явно более строгие `error`, `fatal` и `panic` сохраняются. `trace`, `debug`, `info`, `notice` и отсутствие уровня ограничиваются до `warn`, а `log.output` удаляется. Это обязательно, потому что exact core при отсутствующем `log.level` выбирает `trace`, что создаёт ненужную работу на data-plane. Сохранённый JSON не переписывается.

Итог аудита: безопасного более раннего фильтра для домена/IP на stock Android не найдено. Текущая схема использует самый ранний доступный UID-фильтр, binary IP/domain lookup и не гонит `direct/reject` на VPN-сервер. Дальнейшее ускорение возможно только ценой root, proxy-only, огромных system routes или собственного patch ядра.

## Политика CPU и батареи

Экономичный default является частью MVP, а не будущей оптимизацией:

- приложение, `VpnService`, локальный libbox command server и UI работают в одном Android process; управляемый профиль не создаёт второй service/process или внешний Clash controller;
- `Libbox.setup()` выполняется лениво при первом запуске VPN, а не в `Application.onCreate`; обычное открытие UI не поднимает core, command socket или сетевые callbacks;
- внутренний Clash traffic manager создаётся самим libbox из-за `PlatformLogWriter` и даёт session totals без сетевого listener; в managed JSON `experimental.clash_api.external_controller` и external UI отсутствуют;
- `Libbox.SetMemoryLimit(false)` вызывается явно. В pinned core значение `true` на Android меняет Go `GCPercent` со `100` на `10`, не устанавливая Android memory limit;
- runtime-копия managed JSON использует `log.level = "warn"`; raw JSON сохраняет явно выбранный уровень. Libbox хранит не более 256 строк в памяти, а `log.output` удаляется только из runtime-копии, поэтому запись runtime-лога на диск отсутствует;
- `CommandStatus` с интервалом 1 секунда включается только для видимой главной; `CommandConnections` не включается никогда; bounded `CommandLog` открывается на время connect health-check и затем только при видимой диагностике;
- 60 значений графика — обычный кольцевой массив в памяти. Уведомление, фон и закрытый UI не обновляют график;
- health-check выполняется при подключении и значимой смене сети, IP — один раз за соединение, ping — при подключении и вручную. Периодических проверок «на всякий случай» нет;
- DNS cache остаётся включённым с capacity 4096. Только явный Secure или последний Auto-этап использует `parallel`: Quad9, Google, OpenDNS, Cloudflare и Yandex стартуют на cache miss, потому что `sequential` exact core не достигает резерва при зависании первого; успешный DNS профиля/Android не создаёт DoH-трафика;
- updater, подписки и импорт работают только после действия пользователя;
- rootless hardening выполняется один раз при сборке runtime JSON и не создаёт scanner, timer, listener или отдельный process;
- приложение не запрашивает `WAKE_LOCK` и исключение из battery optimization;
- managed presets используют selector и ручной ping, а не `urltest`; не создают NTP, remote rule-set и явные persistent keepalive.

Импортированный JSON остаётся источником истины и может сам содержать `urltest`, NTP, remote rule-set, внешний Clash controller, verbose log или keepalive. Перед запуском GUI показывает единое предупреждение «Профиль содержит фоновую или внешнюю активность». Включённая по умолчанию и видимая в настройках localhost-защита удаляет control listener только из effective runtime; остальные поля не меняются.

### Обязательный release-gate энергии

Измеряется release/profileable APK, без debugger, минимум пять повторов каждого сценария при одинаковых яркости, температуре, сети и объёме данных:

1. VPN выключен, экран погашен, трафика нет — baseline устройства.
2. VPN подключён, экран погашен, managed-профиль без трафика — idle overhead.
3. Невыбранное приложение передаёт фиксированный объём — Zapret KVN не должен получать его пакеты или расти по CPU пропорционально трафику.
4. Выбранное приложение с `direct` — цена TUN/core без VPN-сервера.
5. Выбранное приложение с proxy — полный пользовательский путь.
6. Видимая главная против закрытой — цена status stream и Compose-графика.
7. Серия уникальных DNS-имён — принятый `parallel` против контрольного `sequential` по надёжности, числу запросов, CPU и энергии.
8. Wi-Fi ↔ mobile, reconnect и сломанный DNS — отсутствие бесконечного retry и утечки jobs/threads/fd.

Сохраняются Perfetto/System Trace, process CPU time, RSS/PSS, GC count/pause, wakeups, wakelocks, network bytes/packets и энергия. На поддерживаемом устройстве используется [Android Power Profiler/ODPM](https://developer.android.com/studio/profile/power-profiler) и Macrobenchmark [`PowerMetric`](https://developer.android.com/reference/androidx/benchmark/macro/PowerMetric); на API 26 — system trace и batterystats. APK не выпускается, если managed idle создаёт периодические сетевые запросы/таймеры, обнаружен app-owned wakelock/job/alarm, невыбранный трафик создаёт per-packet работу Zapret KVN или выбранный вариант не проходит стабильность DNS/IPv6/QUIC.

Жёсткий порог mW заранее не выдумываем: первый воспроизводимый прогон фиксирует baseline. Из двух совместимых вариантов принимается более экономичный; разница менее 5% считается шумом и не оправдывает усложнение архитектуры.

## Структура модулей

```text
app/                 product orchestration, UI, profile/config, libbox и VpnService
app-updater/         GitHub release API, download, checksum/APK/signing policy и retry callback
network-bootstrap/   Android underlying Network, согласованный snapshot, bootstrap DNS и коды NET/DNS
wireguard-import/    независимый parser WireGuard/AWG без зависимости от app
```

Новые Gradle-модули создаются только для самостоятельной границы с направленной зависимостью на `app`, а не для каждого слоя/класса. `app-updater` не знает о Compose, профилях, `libbox` и VPN lifecycle: app передаёт ему только installer factory и одноразовый VPN lease callback. `network-bootstrap` не знает о Compose, профилях, `libbox` и VPN lifecycle; `app` не реализует повторно bootstrap resolver или выбор физической сети. Конкретные продуктовые классы передаются через один `AppContainer`. `VpnService` остаётся единственным владельцем активного PFD/core lifecycle.

## Что сознательно не входит в MVP

- Room/SQLite;
- Hilt, Koin или другой DI framework;
- WebView;
- аналитика и telemetry;
- WorkManager и периодическая синхронизация;
- отдельный Android process для VPN;
- `WAKE_LOCK`, alarm/job и запрос исключения из battery optimization;
- live speed в уведомлении и фоновый status/connection polling;
- динамическая загрузка или смена core;
- второй TUN/VPN service;
- root, iptables, eBPF и Device Owner режим;
- Always-on/Lockdown;
- FakeIP по умолчанию;
- глобальный traffic sniff;
- полноценный firewall/ad blocker;
- whole-app block в основном picker;
- планшетный/desktop layout;
- собственный параллельный движок правил;
- автоматическое исправление неизвестных JSON-полей.

## Разрешения

- `INTERNET`, `ACCESS_NETWORK_STATE`;
- VPN consent при первом подключении;
- foreground VPN service и соответствующий Android 14+ service type/permission;
- `POST_NOTIFICATIONS` на Android 13+;
- камера только при открытии QR scanner;
- `QUERY_ALL_PACKAGES` для полного per-app picker на Android 11+; основной
  `getInstalledApplications()` сверяется с явными `MAIN`/`LAUNCHER` queries, а
  `getInstalledPackages()` вызывается только при ошибке, почти пустом результате
  или доказанном launcher-расхождении. Типизированный discovery-report различает
  полный, восстановленный, частичный и неудачный каталог без сохранения package list.
  Ошибка источника не заменяется придуманным сообщением: picker показывает имя
  операции, исходный `Throwable.toString()` и фактические counts остальных источников;
- `REQUEST_INSTALL_PACKAGES` только для явно запущенного GitHub updater и штатного installer;
- системный file picker без broad storage permission;
- буфер только после явного нажатия.

`WAKE_LOCK` и `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` не запрашиваются. Usage Access, Accessibility, root и изменение системного Private DNS не используются.

## Оформление, диагностика и обновления

Material 3 следует системной светлой/тёмной теме. Dynamic Color применяется на Android 12+; Android 8–11 получают встроенную согласованную палитру.

Диагностика остаётся обычным пакетом внутри `app`, а не отдельным Gradle-модулем или сервисом. Каждая попытка подключения/контролируемого restart создаёт в памяти bounded timeline максимум из 20 этапов с монотонной длительностью: профиль и scope, сеть Android, bootstrap DNS/TCP, runtime overlay, `CheckConfig`, platform adapter, command server, запуск core/TUN, selector/log clients, ожидание VPN network, отдельные UDP/TCP/Android DNS probes, HTTPS probe и финализация. Экран и diagnostic JSON v4 показывают итог, общую длительность, status/detail каждого этапа и самый долгий этап. Отдельный stop timeline фиксирует отмену запуска, закрытие Android TUN, callback/job, command clients, libbox service, command server и network monitor; незавершённый этап экспортируется со статусом `running` и текущей длительностью. Замеры происходят только при переходах между этапами: ticker, polling, worker и дополнительный сетевой запрос для них не создаются.

Диагностика и главная показывают стабильный support-код (`NET-*`, `DNS-*`, `SRV-*`, `CFG-*`, `CORE-*`, `VPN-*`, `AUTH-*`) рядом с понятным сообщением. `VPN-200` означает, что DNS-проверка уже пройдена, но полезный HTTPS-трафик через выбранный VPN outbound не прошёл. `AUTH-100` назначается только по явному отказу авторизации в ошибке или startup-логе ядра; `timeout`, `EOF` и молчаливое закрытие VLESS не считаются доказательством отключённого UUID и остаются `VPN-200`. Для WireGuard startup core-лог различает два паттерна поверх `VPN-200`/`DNS-200`: `VPN-210` — «received handshake response» есть, но данные через туннель не возвращаются (блокировка протокола DPI или сломанный форвардинг/NAT на сервере — с клиента неразличимо), `VPN-211` — рукопожатие отправляется, но ответ не приходит; сырые строки-доказательства из core-лога (redacted) попадают в техническую деталь ошибки. `VPN-210` требует явных доказательств мёртвого data-plane (retry «stopped hearing back» или «dns: exchange failed … deadline exceeded»), а успешный «dns: exchanged» через туннель отменяет вердикт: полевой случай июля 2026 показал, что strict Private DNS (DoT :853 refused) валит DNS-пробу при полностью живом туннеле. Сам паттерн VPN-210 подтверждён полевыми отчётами: тот же WG-профиль работал из другой сети и с AmneziaWG-обфускацией, то есть DPI режет именно чистый WireGuard после рукопожатия. Для bootstrap код задаёт типизированная ошибка `network-bootstrap`; для остальных старых путей app назначает стабильный код категории. Вся suspend-цепочка запуска имеет один 30-секундный deadline: по истечении VPN fail-close останавливается с `VPN-120`, без retry-loop. Diagnostic JSON хранит redacted техническую деталь (`rcode`, `errno`, timeout), но endpoint и credentials туда не попадают. В памяти остаются три последние попытки подключения, до 20 этапов и до 48 startup core-записей на попытку. Одинаковые соседние сообщения схлопываются, а handshake/endpoint/TUN/timeout/warn/error имеют приоритет над обычным packet/DNS-шумом; counters показывают полученные, схлопнутые и отброшенные строки. Вход одного callback ограничен 48 записями, общий журнал — 80, внутренний backlog libbox — 256. `CommandLog` кратко работает во время connect health-check; после подключения он остаётся только пока экран диагностики видим и Activity находится в `STARTED`. Runtime/core traffic log не попадает в Logcat и на диск. На диск атомарно записывается только один последний uncaught Kotlin/Java crash: timestamp, тип, redacted message и максимум 16 сокращённых stack frames в `noBackupFilesDir`; следующий crash заменяет предыдущий. На API 30+ дополнительно читается одна системная запись о прошлом завершении процесса (включая native crash/ANR), но большой system trace намеренно не копируется.

Diagnostic JSON создаётся только явной кнопкой, не содержит raw profile, package list, endpoint, внешний IP или credentials и включает app/core revision+patch SHA-256, Android/API/device ABI, non-VPN network/Private DNS, runtime resource counters, connection timeline, одну прошлую process-exit запись, последний app crash, log counters и структурную сводку effective `zapret-*` overlay. Временный файл перезаписывает предыдущий, передаётся системным Sharesheet через non-exported `FileProvider` с read grant и удаляется при следующем запуске.

Updater проверяет только GitHub Releases Stable/Beta и только после явной кнопки. По `Build.SUPPORTED_ABIS` он выбирает один APK из `release-metadata-v2.json` (`arm64-v8a`, `armeabi-v7a` или `x86_64`), требует отдельный SHA-256 и GitHub digest, ограничивает HTTPS/redirect/размер, затем проверяет package, повышение versionCode, minSdk и signing history содержимого APK. Лишь после этого APK из `cache/updates` передаётся штатному Android installer через bounded non-exported FileProvider. Ошибка, отмена и следующий запуск удаляют временный APK. Core никогда не скачивается отдельно: libbox меняется только вместе с подписанным APK. Legacy `release-metadata.json` сохраняется для одного переходного arm64-обновления старых клиентов.

Release workflow строит CLI/AAR/APK из одного полного commit и одного tracked patchset, проверяет embedded revision и patch SHA-256,
подписывает постоянным ключом из GitHub environment и публикует три одно-ABI APK, checksums,
metadata и release notes. APK с чужим ABI, отсутствующим `libbox` или debug symbols отклоняется. Assets существующего tag не заменяются. Порядок создания и
восстановления офлайн-ключа зафиксирован в [SIGNING.md](SIGNING.md).

Ссылки находятся только в «Настройки → Сообщество»:

- [Zapret KVN](https://t.me/bypassblock)
- [VPN Discord YouTube](https://t.me/vpndiscordyooutube)
- [Zapret VPN bot](https://t.me/zapretvpns_bot)

## План реализации

Каждый этап должен оставлять собираемое приложение и не начинать следующий до своего gate.

### Этап 0 — воспроизводимая основа

- создать один Compose `app` с minSdk 26;
- настроить Material 3, fallback palette и четыре нижние вкладки;
- добавить CI-сборку pinned libbox/CLI и проверку revision;
- запускать `sing-box check` для всех эталонных JSON;
- зафиксировать `Libbox.SetMemoryLimit(false)`, release `Debug=false` и bounded log на 256 строк.

Gate: debug APK собирается локально и в CI; revision ядра совпадает.

### Этап 1 — хранение и минимальный импорт

- `ProfileStore`, metadata index и `AtomicFile` backup;
- импорт raw JSON из файла/буфера;
- список профилей и raw editor;
- `CheckConfig()` без запуска VPN.

Gate: JSON round-trip сохраняет неизвестные extended-поля; backup восстанавливается.

### Этап 2 — вертикальный VPN slice

- global per-app picker/include mode;
- foreground `VpnService` и один lifecycle owner;
- один Android process, без второго VPN/daemon service;
- platform adapter, protect callback, один TUN;
- подключение одного валидного JSON-профиля;
- постоянное уведомление и полный stop/revoke cleanup.

Gate: выбранное приложение проходит через TUN, контрольное невыбранное не появляется в TUN.

### Этап 3 — DNS и отказоустойчивость

- underlying network monitor;
- Android bootstrap resolver и LKG;
- четыре DNS-режима;
- port-53 hijack, DoH fallback/parallel;
- captive portal, strict Private DNS preflight;
- health-check state machine.

Gate: Android device matrix из DNS ADR; мёртвый DNS никогда не оставляет активный TUN.

### Этап 4 — маршрутизация

- экран с карточками «Область VPN» и «Правило трафика»;
- presets и читаемый effective summary;
- packaged `.srs` + manifest/hash;
- действия proxy/direct/reject;
- DNS + route block;
- advanced include/exclude mode и JSON rules.

Gate: Routing ADR matrix, RU/non-RU IPv4/IPv6, selected/unselected apps и block fixture.

### Этап 5 — полный импорт и подписки

- QR scanner, URL и connection URI parsers;
- subscription groups и ручное обновление;
- понятный preview результата без автоподключения;
- популярные package suggestions без TikTok;
- предупреждение о `urltest`/NTP/remote rule-set/external Clash/verbose log/keepalive в импортированном JSON.

Реализация хранит refresh URL отдельно в `noBackupFilesDir/subscriptions/index.json`:
`profiles/index.json` остаётся UI-only и не получает token/credentials. Parser создаёт
настоящий sing-box JSON сразу; transient import candidate не становится вторым
источником routing. QR использует отдельную неэкспортируемую Activity и запрашивает
CAMERA только после явного действия. Ни updater, ни subscription refresh не имеют
фонового scheduler.

Точный core содержит Clash subscription parser, но текущий libbox AAR его не
экспортирует. Kotlin YAML parser намеренно не добавляется: подробная семантика,
кандидат Hysteria v1 и критерии возврата описаны в
[политике форматов импорта](IMPORT_FORMATS.md).

Gate: malformed input не меняет существующие профили; секреты не попадают в UI/log.

### Этап 6 — продуктовый минимум

- главная карточка, IP, ping, session timer и график;
- lifecycle-gated `CommandStatus` 1 Гц; без `CommandConnections` и live-speed notification;
- диагностика/redacted share;
- тема, сообщество и About.

Gate: полный пользовательский путь «установил → импортировал → выбрал приложения → подключился → отправил диагностику».

### Этап 7 — updater и GitHub Release

- ручной Stable/Beta check без фоновой синхронизации;
- checksum/package/version/signing-history validation до installer;
- подписанные exact-core `arm64-v8a`, `armeabi-v7a`, `x86_64` APK, metadata, SHA-256 и notes;
- same-key upgrade с сохранением app-private данных.

Updater находится в отдельном `app-updater` library-модуле. Прямая retryable ошибка
проверки или загрузки допускает ровно один повтор: `app` временно перезапускает/поднимает
текущий VPN с runtime-only правилом для package Zapret KVN и доменов GitHub. После запроса
предыдущее состояние VPN восстанавливается даже при отмене; stored JSON и трафик других
приложений не меняются. Отдельного VPN service, фонового worker или постоянного правила нет.

Gate: битый/прерванный/чужой/downgrade APK не запускает installer и не оставляет cache.

### Этап 8 — выпускная матрица

- выполнить весь список «Потом проверить» ниже на реальных устройствах;
- сохранить результаты, версии ОС, модели устройств и сырые замеры как release artifact;
- перенести в канонические решения только выводы, прошедшие критерий соответствующего пункта.

Gate: APK не выпускается при failed fixture, instrumented test, ABI/revision mismatch или провале обязательного release-gate энергии.

## Текущая проверка

Локально реализованы Этапы 0–7:

- 7/7 JSON приняты CLI, собранным из точного commit;
- 7/7 приняты compatibility release CLI;
- [Android WireGuard ClientBind fixture](../testdata/routing/wireguard-android-client-bind.json),
  SHA-256 `822dcc9c6a138418b8adc51be44260ac3ff0dceb10187506e1506a5c710974c7`,
  фиксирует Android WireGuard endpoint, health-route и раздельные TUN/endpoint MTU;
- `go test ./dns/... ./route/rule ./experimental/libbox` проходит; наш воспроизводимый audit test дополнительно проверяет exact pinned fallback success/error/hang/RCODE внутри исходного Go package;
- Gradle-проект с направленными library-модулями собирает одно-ABI debug и R8 release-матрицу; каждый APK содержит ровно один ABI, один process и один `VpnService`;
- 165/165 JVM unit-тестов проходят, включая DNS/routing/import/updater/signing,
  Always-on policy и полную блокировку приложений;
- полный текущий набор 67/67 Android instrumented-тестов проходит на AVD API 36; API 26
  прошёл предыдущую матрицу 66/66 и security delta 3/3, API 29 — 66/66; API 29/36
  дополнительно прошли 100 connect/stop и 50 Wi-Fi/cellular transitions;
- API 36 AVD прошёл 16 performance-сценариев по пять повторов: невыбранные 8 MiB дали median TUN=0, System Trace/batterystats/raw metrics сохранены, а `mixed`, MTU 9000 и GCPercent 100 оставлены без изменений по порогу 5%; физическая энергия не считается доказанной;
- same-key upgrade probe на API 26/36 сохраняет настоящий профиль, active id, DataStore и allowlist между versionCode 701001→701002; Android отклоняет downgrade и APK с другим ключом без потери данных;
- подписанный R8 release bundle с временным тестовым ключом локально прошёл `apksigner`, package/version/core metadata и SHA-256 consistency; постоянный production key и внешний GitHub Release остаются действиями владельца;
- отдельный debug-only adb probe на API 26/36 подтверждает hard process contract: после смерти процесса Android снимает service/TUN/core и новый UI показывает `Stopped`, а следующий connect создаёт ровно один экземпляр; receiver отсутствует в release manifest;
- exact CLI проверяет packaged RU domain/IP `.srs` на RU/non-RU domain, IPv4 и IPv6; manifest закрепляет источник, commit/license и SHA-256, а installer атомарно восстанавливает повреждённый asset до запуска VPN;
- exact core benchmark: загрузка `.srs` 1 114 мкс/758 624 allocation bytes, lookup 329 нс/op, 1 104 B/op и 2 allocs/op; полный Android debug-прогон дал cold connect 41/63 мс и 2,5/7,5 мс CPU на flow на API 36/26 без продолжающегося роста PSS;
- в каждом lifecycle-цикле внутренние счётчики PFD/TUN, adapter, callback и libbox instance возвращаются в ноль; process FD/thread trend не показывает продолжающегося роста;
- Markdown, локальные ссылки, fixture hashes, lint и полный локальный аналог CI проверены;
- P12–P14, физическая/энергетическая часть P15 и обязательный release-gate энергии на устройстве ещё не выполнены.

Эти результаты закрывают локальные автоматизированные Gate 2/4/6/7, но не закрывают физический Gate 3 и выпускную OEM/energy matrix: ещё нужны настоящий captive portal, IPv6-only/NAT64, blocked-DNS/LKG/DoH и повтор per-app/routing на физических сетях/устройствах. Они также не заменяют внешний GitHub Actions run, постоянный signing key и фактически опубликованный Release. Приложение нельзя считать готовым к выпуску до этапа 8.

## Потом проверить — открытые вопросы

Это единственный список недоказанного. Пункты ниже **не являются принятыми решениями** и не разрешают ИИ заранее добавлять переключатели, новые слои, зависимости или fork ядра. До получения измерений сохраняются канонические defaults выше. После теста результат переносится в соответствующую ADR, а пункт отмечается закрытым с датой, устройствами и ссылкой на лог.

| ID | Что пока не доказано | Как проверить | Что делать до результата |
|---|---|---|---|
| P1 | Реальная per-app изоляция на всех целевых API/OEM | API 26, 28, 29 и актуальный Android: выбранное приложение видно в TUN, невыбранное не видно; отдельно shared UID, удаление package во время запуска, Always-on/Lockdown | Использовать include allowlist; при пустом/ошибочном списке не запускаться |
| P2 | Какой userspace stack быстрее именно на наших устройствах | Одинаковые TCP/UDP/QUIC сценарии для `mixed` и `system`: throughput, CPU, RAM, connect time, потери и стабильность на Wi‑Fi/mobile | Оставить поле `stack` пустым, то есть upstream `mixed`; не показывать выбор в обычном GUI |
| P3 | Отсутствие OEM/операторских проблем у default `1500`, а для userspace WireGuard — `min(1500, endpoint MTU)` | Проверить IPv4, IPv6, NAT64, QUIC, крупные загрузки, PMTU/fragmentation и смену Wi‑Fi/mobile; отдельно A/B WireGuard 1280 ↔ core 9000 | Оставить вычисляемый runtime MTU с явным откатом «По профилю»; менять только по воспроизводимой регрессии |
| P4 | Цена безусловного Android owner/process lookup в pinned core | Профилирование connect latency, CPU и throughput с текущим core; отдельная экспериментальная сборка без lookup допустима только для сравнения | Не форкать ядро. Patch рассматривается лишь при повторяемом существенном выигрыше и полном повторе матрицы |
| P5 | Реальное поведение DNS на Android/OEM | Private DNS off/automatic/strict working/broken, captive portal, заблокированный system DNS, DoH через proxy, port-53 hijack, DNS/HTTPS health-check | Следовать fail-close DNS ADR; системные настройки не менять и strict Private DNS скрыто не обходить |
| P6 | Сетевой lifecycle без гонок | Wi‑Fi ↔ mobile, потеря underlying network, быстрые callback bursts, reconnect 8–10 секунд, LKG fresh/stale, revoke, kill process и повторный старт | Один generation token, один service-lock и идемпотентный stop |
| P7 | Production RU/domain/block rule-set | Выбрать источники и лицензии, закрепить commit/hash, собрать настоящие `.srs`, проверить RU/non-RU IPv4/IPv6, память, cold start и повреждённый asset | Эталонные fixtures считать только schema/graph тестами, не production-базой |
| P8 | Практическая граница domain block | Стандартный DNS, кэшированный ответ, direct IP и приложения со встроенным DoH; TCP/UDP/ICMP reject | Не обещать firewall. Для гарантии IP-блокировки требовать IP/CIDR set; глобальный sniff не включать |
| P9 | Android/libbox lifecycle и ABI release build | Собрать AAR/APK из exact commit для выбранных ABI; проверить PFD ownership, `protect(fd)`, cancel/revoke, process death, отсутствие утечек fd/threads и совпадение embedded revision | Не выпускать APK только на основании CLI/unit-тестов |
| P10 | Достаточность диагностики и redaction | Экспорт конфигов всех поддержанных типов; проверить удаление UUID/password/token/URL credentials и возможность воспроизвести ошибку по очищенному файлу | Не логировать исходные секреты; подробный экспорт только по действию пользователя |
| P12 | Idle CPU и энергия ещё не измерены | Сценарии 1–8 из release-gate на слабом API 26 и современном устройстве; пять повторов, одинаковые условия | Никаких polling/wakelock/jobs; один process; managed background полностью event-driven |
| P13 | Цена status/traffic tracking и bounded logs | Сравнить главную видимую/закрытую, status 1 Гц/выключен, diagnostics stream закрыт/открыт; измерить CPU, allocations и GC | Только totals на главной; никогда не подписываться на connections; log stream только в диагностике |
| P14 | GC=100 против GC=10 и риск памяти | Сравнить CPU, GC count/pause и PSS/RSS под длительной TCP/UDP/QUIC нагрузкой | `SetMemoryLimit(false)`; менять только при доказанном OOM и выигрыше без роста энергии |
| P15 | Цена надёжного DNS fallback на физических устройствах | Cache burst и уникальные имена; сравнить принятый `parallel` с `sequential` по энергии и запросам, не возвращая заведомо сломанный hang-path | Managed default `parallel`; кэш 4096; без периодических проверок и plaintext fallback |
| P16 | Стабильность нового Android WireGuard/AWG data-plane | Test 23 доказал старую неисправность после handshake; проверить новый split engine во всех DNS-режимах, затем длительную сессию, mobile/network switch и IPv4/IPv6-capable profiles | Один Android TUN и pinned patch; до физического теста исправление не объявлять подтверждённым |

Критерий для P2–P4 и P12–P15: одного удачного speed test недостаточно. Нужны минимум пять повторов на слабом API 26 устройстве и одном современном устройстве без регрессии DNS, IPv6, QUIC и стабильности. Разница менее 5% не оправдывает усложнение архитектуры.

## Источники мастер-плана

- [Android: per-app VPN contract](https://developer.android.com/develop/connectivity/vpn)
- [Android: Power Profiler/ODPM](https://developer.android.com/studio/profile/power-profiler)
- [AndroidX Macrobenchmark: PowerMetric](https://developer.android.com/reference/androidx/benchmark/macro/PowerMetric)
- [Android PackageManager: APK/signing contract](https://developer.android.com/reference/android/content/pm/PackageManager)
- [Android SigningInfo: rotation and multiple signers](https://developer.android.com/reference/android/content/pm/SigningInfo)
- [Android installer intent contract](https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE)
- [GitHub REST API: releases and assets](https://docs.github.com/en/rest/releases/releases)
- [Pinned core: Android memory/GC policy](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/experimental/libbox/memory.go)
- [Pinned core: status/log command server](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/daemon/started_service.go)
- [Pinned core: internal Clash API creation from PlatformLogWriter](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/box.go)
- [Pinned core: traffic manager](https://github.com/shtorm-7/sing-box-extended/blob/ff11f007ec798136a5de258f947a4f34011a37ea/experimental/clashapi/trafficontrol/manager.go)
