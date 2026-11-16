package io.github.zapretkvn.masking.wireguard

/**
 * Builds an I1 signature packet shaped like an ordinary DNS query for a chosen domain.
 *
 * The transaction id is `<r 2>`: the core draws two fresh random bytes for EVERY packet it
 * sends (obf_rand.go), so no two sessions start with the same bytes. The official Amnezia
 * client ships one static blob (a fixed QUIC/DNS-looking capture) to all users; a constant
 * first packet is a shared fingerprint, which is exactly what masking must avoid.
 *
 * Layout (RFC 1035 4.1): id(2, random) flags 0x0100 (standard query, recursion desired)
 * qdcount 1, ancount/nscount/arcount 0, the QNAME labels, QTYPE A (1), QCLASS IN (1).
 */
object DnsSignature {
    /** Ordinary names that resolve in Russia; the user may type any other. */
    val DOMAINS = listOf("yandex.ru", "vk.com", "mail.ru", "ozon.ru", "avito.ru", "gosuslugi.ru")

    private const val HEADER_AFTER_ID = "0100" + "0001" + "0000" + "0000" + "0000"
    private const val QTYPE_A_CLASS_IN = "0001" + "0001"
    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** The I1 tag chain for [domain], or null when [domain] is not a valid host name. */
    fun chain(domain: String): String? {
        val qname = qname(domain) ?: return null
        return "<r 2><b 0x$HEADER_AFTER_ID$qname$QTYPE_A_CLASS_IN>"
    }

    fun isValidDomain(domain: String): Boolean = qname(domain) != null

    private fun qname(domain: String): String? {
        val name = domain.trim().trimEnd('.').lowercase()
        if (name.isEmpty() || name.length > 253) return null
        val labels = name.split('.')
        if (labels.size < 2 || labels.any { !LABEL.matches(it) }) return null
        return buildString {
            labels.forEach { label ->
                append("%02x".format(label.length))
                label.forEach { append("%02x".format(it.code)) }
            }
            append("00")
        }
    }
}
