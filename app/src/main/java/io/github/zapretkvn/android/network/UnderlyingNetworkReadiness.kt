package io.github.zapretkvn.android.network

import io.github.zapretkvn.networkbootstrap.UnderlyingNetworkState

/**
 * Пригодность физической сети Android.
 *
 * Разделение на два уровня существует ровно из-за перехода Wi‑Fi ↔ cellular:
 * `ConnectivityManager` публикует новый `Network` задолго до того, как Android
 * закончит DHCP и проверку доступа в интернет. Перезапуск на «сыром» кандидате
 * заканчивался `NET-101`/`NET-102`, поэтому решения принимаются по `settled`,
 * а `usable` остаётся нижней границей, после которой попытка вообще имеет смысл.
 *
 * Обе функции принимают примитивы: политика обязана оставаться проверяемой
 * обычным JVM-тестом, без инстанса `android.net.Network`.
 */
internal object UnderlyingNetworkReadiness {
    /** Минимум для bootstrap: Android отдал сеть и разрешил её интерфейс. */
    fun usable(hasNetwork: Boolean, interfaceIndex: Int): Boolean =
        hasNetwork && interfaceIndex >= 0

    /**
     * Android закончил настройку линка: есть резолверы и вынесен вердикт о
     * доступе в интернет. Captive portal тоже считается завершённым состоянием —
     * дальше сеть сама не «дозреет», решение принимает пользователь.
     */
    fun settled(
        hasNetwork: Boolean,
        interfaceIndex: Int,
        validated: Boolean,
        captivePortal: Boolean,
        dnsServerCount: Int,
    ): Boolean = usable(hasNetwork, interfaceIndex) &&
        dnsServerCount > 0 &&
        (validated || captivePortal)
}

internal fun UnderlyingNetworkState.isUsableForConnect(): Boolean =
    UnderlyingNetworkReadiness.usable(network != null, interfaceIndex)

internal fun UnderlyingNetworkState.isSettledForConnect(): Boolean =
    UnderlyingNetworkReadiness.settled(
        hasNetwork = network != null,
        interfaceIndex = interfaceIndex,
        validated = validated,
        captivePortal = captivePortal,
        dnsServerCount = dnsServers.size,
    )
