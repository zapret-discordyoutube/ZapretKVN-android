package io.github.zapretkvn.android.vpn

import android.net.Network
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants.AF_INET
import android.system.OsConstants.AF_INET6
import android.system.OsConstants.IPPROTO_ICMP
import android.system.OsConstants.IPPROTO_ICMPV6
import android.system.OsConstants.O_NONBLOCK
import android.system.OsConstants.POLLIN
import android.system.OsConstants.SOCK_DGRAM
import android.system.StructPollfd
import java.io.FileDescriptor
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ServerPingTarget(
    val outboundTag: String,
    val hostname: String,
)

internal class IcmpProbeException(
    val failure: LatencyFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private class IcmpNoResponseException(message: String) : Exception(message)
private class IcmpInvalidReplyException(message: String) : Exception(message)

/** Measures real ICMP Echo RTT to a VPN server over Android's underlying network. */
class IcmpPingProbe(
    private val replyTimeoutMillis: Int = REPLY_TIMEOUT_MILLIS,
) {
    init {
        require(replyTimeoutMillis > 0)
    }

    suspend fun measure(network: Network, target: ServerPingTarget): Long {
        var resolving = true
        return try {
            withTimeout(TOTAL_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                val addresses = try {
                    network.getAllByName(target.hostname).toList()
                } catch (error: UnknownHostException) {
                    throw IcmpProbeException(
                        LatencyFailure.Dns,
                        "Не удалось разрешить адрес VPN-сервера.",
                        error,
                    )
                }
                if (addresses.isEmpty()) {
                    throw IcmpProbeException(LatencyFailure.Dns, "DNS не вернул адрес VPN-сервера.")
                }
                resolving = false
                val failures = mutableListOf<Throwable>()
                for (address in addresses) {
                    try {
                        return@withContext measureOnce(network, address)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        failures += error
                    }
                }
                val noResponse = failures.all { it is IcmpNoResponseException }
                throw IcmpProbeException(
                    if (noResponse) LatencyFailure.NoResponse else LatencyFailure.Failed,
                    if (noResponse) "VPN-сервер не ответил на ICMP Echo."
                    else "ICMP Echo завершился ошибкой для всех адресов endpoint.",
                    failures.firstOrNull(),
                )
            }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw IcmpProbeException(
                if (resolving) LatencyFailure.Dns else LatencyFailure.NoResponse,
                if (resolving) "DNS endpoint не ответил вовремя."
                else "VPN-сервер не ответил на ICMP Echo вовремя.",
                timeout,
            )
        }
    }

    private fun measureOnce(network: Network, address: InetAddress): Long {
        val ipv6 = address is Inet6Address
        val descriptor = Os.socket(
            if (ipv6) AF_INET6 else AF_INET,
            SOCK_DGRAM or O_NONBLOCK,
            if (ipv6) IPPROTO_ICMPV6 else IPPROTO_ICMP,
        )
        try {
            network.bindSocket(descriptor)
            Os.connect(descriptor, address, 0)
            val request = IcmpEchoPacket.request(ipv6, ECHO_SEQUENCE)
            val startedAt = SystemClock.elapsedRealtimeNanos()
            Os.write(descriptor, request, 0, request.size)
            val poll = StructPollfd().apply {
                fd = descriptor
                events = POLLIN.toShort()
            }
            if (Os.poll(arrayOf(poll), replyTimeoutMillis) <= 0 ||
                (poll.revents.toInt() and POLLIN) == 0
            ) {
                throw IcmpNoResponseException(
                    "VPN-сервер не ответил на ICMP Echo за $replyTimeoutMillis мс.",
                )
            }
            val response = ByteArray(MAX_PACKET_SIZE)
            val length = Os.read(descriptor, response, 0, response.size)
            val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedAt
            if (!IcmpEchoPacket.isMatchingReply(response, length, ipv6, ECHO_SEQUENCE)) {
                throw IcmpInvalidReplyException("VPN-сервер вернул несвязанный ICMP-пакет.")
            }
            return ((elapsedNanos + NANOS_PER_MILLI / 2) / NANOS_PER_MILLI).coerceAtLeast(0)
        } finally {
            close(descriptor)
        }
    }

    private fun close(descriptor: FileDescriptor) {
        runCatching { Os.close(descriptor) }
    }

    private companion object {
        const val ECHO_SEQUENCE = 1
        const val REPLY_TIMEOUT_MILLIS = 1_000
        const val TOTAL_TIMEOUT_MILLIS = 6_000L
        const val MAX_PACKET_SIZE = 256
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

internal object IcmpEchoPacket {
    private const val HEADER_SIZE = 8
    private const val PAYLOAD_SIZE = 56
    private val payload = ByteArray(PAYLOAD_SIZE).also { bytes ->
        "ZapretKVN".encodeToByteArray().copyInto(bytes)
    }

    fun request(ipv6: Boolean, sequence: Int): ByteArray = ByteArray(HEADER_SIZE + payload.size).apply {
        this[0] = if (ipv6) 128.toByte() else 8
        this[1] = 0
        this[6] = (sequence ushr 8).toByte()
        this[7] = sequence.toByte()
        payload.copyInto(this, HEADER_SIZE)
    }

    fun isMatchingReply(packet: ByteArray, length: Int, ipv6: Boolean, sequence: Int): Boolean {
        if (length != HEADER_SIZE + payload.size) return false
        val expectedType = if (ipv6) 129.toByte() else 0.toByte()
        if (packet[0] != expectedType || packet[1] != 0.toByte()) return false
        val replySequence = ((packet[6].toInt() and 0xff) shl 8) or (packet[7].toInt() and 0xff)
        if (replySequence != sequence and 0xffff) return false
        return payload.indices.all { packet[HEADER_SIZE + it] == payload[it] }
    }
}
