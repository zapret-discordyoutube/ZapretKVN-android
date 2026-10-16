package io.github.zapretkvn.networkbootstrap

import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Подменяет ли провайдер DNS — проверяется на «приманке», известном заблокированном
 * домене, а не на имени своего сервера: открытый запрос с именем сервера сам его
 * выдаёт. Запускается только по действию пользователя (экспорт диагностики),
 * один раз, без повторов и расписания.
 */
object DecoyDnsCheck {
    const val DECOY_HOST = "rutracker.org"
    const val BUDGET_MILLIS = 4_000L

    enum class Verdict(val wire: String) {
        /** Доверенный DNS знает имя, системный отвечает «имени нет». */
        Denied("denied"),

        /** Системный ответ ведёт в служебные или частные адреса — заглушка провайдера. */
        Bogus("bogus"),

        /** Адреса не пересекаются: подмена или другой узел CDN — не утверждаем. */
        Differs("differs"),
        Clean("clean"),

        /** Доверенного ответа нет или системный упал по другой причине. */
        Unknown("unknown"),
    }

    suspend fun run(
        network: Network,
        trusted: TrustedDohResolver = TrustedDohResolver(),
        system: AndroidBootstrapDnsResolver = AndroidBootstrapDnsResolver(trusted = null),
    ): Verdict = withTimeoutOrNull(BUDGET_MILLIS) {
        coroutineScope {
            val systemAnswer = async {
                try {
                    Result.success(system.resolveSystemOnly(network, DECOY_HOST, noCacheLookup = true))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
            val trustedAnswer = try {
                trusted.resolve(network, DECOY_HOST)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                TrustedDnsOutcome.Unavailable
            }
            classify(trustedAnswer, systemAnswer.await())
        }
    } ?: Verdict.Unknown

    fun classify(trusted: TrustedDnsOutcome, system: Result<List<InetAddress>>): Verdict {
        if (trusted !is TrustedDnsOutcome.Addresses || trusted.value.isEmpty()) return Verdict.Unknown
        val failure = system.exceptionOrNull()
        if (failure != null) {
            val reason = (failure as? BootstrapFailureException)?.reason
            return if (reason == BootstrapFailureCode.DnsNameNotFound || reason == BootstrapFailureCode.DnsEmptyAnswer) {
                Verdict.Denied
            } else {
                Verdict.Unknown
            }
        }
        val answer = system.getOrThrow()
        if (answer.isEmpty()) return Verdict.Denied
        if (answer.any(::isStubAddress)) return Verdict.Bogus
        val expected = trusted.value.mapNotNull(InetAddress::getHostAddress).toSet()
        return if (answer.any { it.hostAddress in expected }) Verdict.Clean else Verdict.Differs
    }

    private fun isStubAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            (address is Inet4Address && address.address[0].toInt() == 0)
}
