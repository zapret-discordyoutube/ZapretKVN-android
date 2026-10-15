package io.github.zapretkvn.android.network

import android.net.Network

/**
 * Физическая сеть, через которую сейчас работает VPN: её же VPN сообщает системе
 * (`setUnderlyingNetworks`). Прямые запросы мимо VPN (обновления, подписки, служба
 * очереди) идут через неё — так они видят ту же сеть, что и сам туннель.
 */
object UnderlyingNetworkHint {
    @Volatile
    var network: Network? = null
}

/** Что известно о прямой сети для выбора: только числа, без Android-классов. */
data class DirectNetworkFacts(
    val id: Long,
    val validated: Boolean,
    val captivePortal: Boolean,
    val suspended: Boolean,
    /** 3 — проводная, 2 — Wi-Fi, 1 — мобильная, 0 — прочее. */
    val transportRank: Int,
    val notMetered: Boolean,
)

/**
 * Какую прямую сеть взять. Сначала — из [preferred] по порядку (сеть VPN, затем сеть
 * системы по умолчанию), если она проверена системой и не приостановлена. Иначе лучшая
 * по правилу: проверена → без страницы входа → не приостановлена → проводная, Wi-Fi,
 * мобильная → безлимитная. Раньше бралась первая проверенная в произвольном порядке
 * `allNetworks`: на телефоне с Wi-Fi и мобильной сетью это могла оказаться мобильная
 * без работающего DNS.
 */
fun chooseDirectNetwork(candidates: List<DirectNetworkFacts>, preferred: List<Long>): Long? {
    for (id in preferred) {
        val facts = candidates.firstOrNull { it.id == id } ?: continue
        if (facts.validated && !facts.suspended) return facts.id
    }
    return candidates.maxWithOrNull(
        compareBy<DirectNetworkFacts>(
            { it.validated },
            { !it.captivePortal },
            { !it.suspended },
            { it.transportRank },
            { it.notMetered },
        ).thenByDescending { it.id },
    )?.id
}
