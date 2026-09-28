package io.github.zapretkvn.android.vpn.runtime

/** Текст foreground-уведомления для каждого состояния рантайма. */
internal enum class ForegroundState(val text: String) {
    Preparing("Подготовка VPN"),
    ValidatingProfile("Проверка профиля"),
    CheckingNetwork("Проверка сети Android"),
    ValidatingCore("Проверка sing-box"),
    CreatingTun("Создание TUN"),
    CheckingHealth("Проверка DNS и HTTPS"),
    Connected("Подключено"),
    Paused("VPN на паузе по правилу сети"),
    Restarting("Перезапуск VPN"),
    AwaitingNetwork("Ожидание сети Android"),
    Retrying("Повтор подключения"),
    Stopping("Отключение"),
}
