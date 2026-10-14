package io.github.zapretkvn.android.network.geo

import android.net.Network
import io.github.zapretkvn.networkbootstrap.TrustedDnsOutcome
import io.github.zapretkvn.networkbootstrap.TrustedDohResolver
import java.net.InetAddress
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Адреса сервера для определения страны — только по зашифрованному DoH к резолверам,
 * заданным IP-литералом (тот же доверенный путь, что и при подключении VPN), по
 * физической сети мимо туннеля. Системный DNS не используется совсем: ни открытым
 * текстом, ни через цепочку провайдера, поэтому имена серверов не уходят в открытом виде.
 *
 * Если доверенный путь не отвечает, страна просто не определяется до следующей попытки
 * (раз в сутки), а не ищется обходным путём.
 */
class DohHostAddressResolver(
    private val physicalNetwork: () -> Network?,
    private val trusted: TrustedDohResolver = TrustedDohResolver(
        // Два резолвера вместо пяти: каждое имя спрашивается у минимума сторон.
        servers = listOf("1.1.1.1", "8.8.8.8"),
        timeoutMillis = TIMEOUT_MILLIS,
    ),
) : HostAddressResolver {
    override suspend fun resolve(host: String): List<InetAddress>? {
        val network = physicalNetwork() ?: return null
        val outcome = withTimeoutOrNull(TOTAL_TIMEOUT_MILLIS) { trusted.resolve(network, host) }
        return (outcome as? TrustedDnsOutcome.Addresses)?.value ?: emptyList()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 4_000
        const val TOTAL_TIMEOUT_MILLIS = 12_000L
    }
}
