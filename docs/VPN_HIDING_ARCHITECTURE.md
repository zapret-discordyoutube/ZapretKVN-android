# Скрытие VPN и rootless hardening

Этот ADR фиксирует только меры, доступные обычному Android-приложению без root,
Shizuku, Accessibility, Device Owner и модификации ОС.

## Граница возможностей

Zapret KVN использует системный `VpnService`, поэтому Android намеренно показывает
`TRANSPORT_VPN`, `VpnTransportInfo`, TUN-интерфейс, маршруты и системный VPN indicator.
Приложение не может подменить эти данные для другого UID. Экран называется
«Скрытие VPN» как пользовательский термин, но не обещает полную невидимость.

Полное устранение системных признаков требует вынести туннелирование с устройства
либо перехватывать трафик привилегированным TPROXY/root-модулем. Эти варианты не
входят в Android APK Zapret KVN.

## Один лёгкий модуль

`hardening/VpnRuntimeHardening` работает один раз при сборке effective runtime JSON.
Он не создаёт service, process, thread, timer, listener, сетевой запрос или
периодический scanner. Настройки хранятся в существующем DataStore, а их изменение
контролируемо перезапускает активное подключение.

Сохранённый профиль никогда не переписывается. Итоговые признаки runtime попадают
в bounded `EffectiveOverlaySummary`: число non-TUN inbounds, наличие локального
control endpoint и effective MTU.

## Параметры

| Параметр | Default | Действие |
|---|---:|---|
| Блокировать localhost endpoints | on | Удаляет сетевые поля Clash API и `v2ray_api`; любой дополнительный non-TUN inbound блокирует запуск |
| Нейтральное имя VPN-сессии | off | Передаёт Android строку `Системная сеть` вместо `Zapret KVN`; package/VpnService это не скрывает |
| MTU TUN | `min(1500, WireGuard endpoint MTU)` | Runtime-копия; без userspace WireGuard остаётся 1500, stored JSON не меняется |

Managed-профили не открывают внешний Clash controller независимо от пользовательского
переключателя. Отключение localhost-защиты существует только для осознанной
совместимости advanced raw JSON и сопровождается предупреждением в UI.

## Неподвижные инварианты

1. `VpnService.Builder.allowBypass()` не вызывается.
2. Системный HTTP proxy не включается.
3. `ZapretVpnService` остаётся `exported=false` и защищён `BIND_VPN_SERVICE`.
4. Нет каталога «подозрительных» приложений и фонового наблюдения за процессами.
5. Нельзя заявлять, что опции скрывают `TRANSPORT_VPN`, TUN или установленный APK.
6. Любая защита применяется до `Libbox.checkConfig()` и `Builder.establish()`.
7. Единственный экспортируемый приёмник — `ExternalControlReceiver` внешнего управления (Tasker и подобные). Он отключён в манифесте (`android:enabled="false"`), приложение включает его только пока включён переключатель «Настройки → Автоматизация VPN → Внешнее управление» (по умолчанию выключен), а каждый вызов должен нести секрет владельца (48 шестнадцатеричных знаков, сравнение за постоянное время, неверные вызовы молча отбрасываются). Сервис VPN при этом остаётся `exported=false`. Пока переключатель включён, приёмник виден другим приложениям, которые могут перечислять компоненты пакета: это осознанная цена функции, поэтому она выключена по умолчанию. Запуск VPN из фона Android разрешает по `OP_ACTIVATE_VPN` (проверено на эмуляторе API 36, остальные уровни не проверялись); без согласия на VPN запуск не происходит, диалог из приёмника не показывается.

## Release-gate

- unit: stored JSON не меняется; controller/API удалены; non-TUN inbound блокируется;
- unit: отключённая защита сохраняет advanced raw endpoints;
- unit: MTU 1500 попадает только в effective runtime;
- unit: единственный экспортируемый приёмник в манифесте — `ExternalControlReceiver` с `enabled=false` (`ExternalControlManifestTest`); секрет и команды — `ExternalControlTest`;
- instrumented UI: настройки сохраняются и доступны из «Настройки → Скрытие VPN»;
- device: MTU 1500 отдельно проверяется на IPv4, IPv6/NAT64, TCP, UDP/QUIC,
  крупных загрузках и Wi-Fi ↔ mobile;
- battery: idle не должен получить новых wakeups, timers или network requests.

MTU 1500 включён по умолчанию после заметного выигрыша на реальном устройстве.
Полная матрица IPv6/NAT64, QUIC, операторов и OEM остаётся обязательным release-gate;
при несовместимости пользователь может вернуть MTU профиля/core.
