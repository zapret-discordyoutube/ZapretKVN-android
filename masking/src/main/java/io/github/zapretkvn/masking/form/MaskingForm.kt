package io.github.zapretkvn.masking.form

import io.github.zapretkvn.masking.model.Desync
import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.MuxCool
import io.github.zapretkvn.masking.model.Problem
import io.github.zapretkvn.masking.model.SingboxFragment
import io.github.zapretkvn.masking.model.Span
import io.github.zapretkvn.masking.model.WireGuardMasking
import io.github.zapretkvn.masking.model.XrayFragment
import io.github.zapretkvn.masking.model.XrayShaping
import io.github.zapretkvn.masking.model.Xmux
import io.github.zapretkvn.masking.presets.MaskingPresets
import io.github.zapretkvn.masking.validate.MaskingValidator
import io.github.zapretkvn.masking.wireguard.DnsSignature

/**
 * The editable draft behind the "Маскировка" screen: every number as the text the user typed.
 * The screen only shows and edits it; [toMasking] turns it into settings and lists every
 * problem, and nothing is saved while an error remains. Pure, so it is tested like the rest.
 */
data class MaskingForm(
    val wireguardEnabled: Boolean = false,
    val jc: String = "",
    val jmin: String = "",
    val jmax: String = "",
    val i1: String = "",
    val muxEnabled: Boolean = false,
    val muxConcurrency: String = MuxCool().concurrency.toString(),
    val muxXudpConcurrency: String = MuxCool().xudpConcurrency.toString(),
    val muxUdp443: String = MuxCool().xudpProxyUDP443,
    val xmuxEnabled: Boolean = false,
    val xmuxMaxConnections: String = "",
    val xmuxMaxConcurrency: String = "",
    val xmuxCMaxReuseTimes: String = "",
    val xmuxHMaxRequestTimes: String = "",
    val xmuxHMaxReusableSecs: String = "",
    val fragmentEnabled: Boolean = false,
    val fragmentPackets: String = XrayFragment().packets,
    val fragmentLength: String = XrayFragment().length.toString(),
    val fragmentDelay: String = XrayFragment().delay.toString(),
    val desyncEnabled: Boolean = false,
    val desyncMethod: String = Desync().method,
    val desyncSplitAt: String = Desync().splitAt,
    val desyncTtl: String = Desync().fakeTtl.toString(),
    val desyncSni: String = Desync().fakeSni,
    val singboxEnabled: Boolean = false,
    val singboxFragment: Boolean = true,
    val singboxRecordFragment: Boolean = false,
    val singboxDelayMs: String = "",
) {
    data class Result(val masking: Masking, val problems: List<Problem>) {
        val hasErrors: Boolean get() = problems.any { it.severity == Problem.Severity.Error }
    }

    /** Fills the WireGuard fields from a preset; `domain` only matters for the DNS-like preset. */
    fun withWireGuardPreset(id: String, domain: String = DnsSignature.DOMAINS.first()): MaskingForm {
        val preset = when (id) {
            "wg-dns" -> MaskingPresets.wireGuardDns(domain)
            "wg-more-junk" -> MaskingPresets.wireGuardMoreJunk
            "wg-junk-only" -> MaskingPresets.wireGuardJunkOnly
            else -> return this
        }
        val section = checkNotNull(preset.masking.wireguard)
        return copy(
            wireguardEnabled = true,
            jc = section.jc?.toString().orEmpty(),
            jmin = section.jmin?.toString().orEmpty(),
            jmax = section.jmax?.toString().orEmpty(),
            i1 = section.signatures["i1"].orEmpty(),
        )
    }

    /** Fills the xmux fields from a preset (an empty set = the core's own defaults). */
    fun withXmuxPreset(id: String): MaskingForm {
        val xmux = when (id) {
            "xmux-default" -> MaskingPresets.xmuxCoreDefault
            "xmux-rotate" -> MaskingPresets.xmuxRotate
            "xmux-few" -> MaskingPresets.xmuxFew
            else -> return this
        }.masking.xray?.xmux ?: return this
        return copy(
            xmuxEnabled = true,
            xmuxMaxConnections = xmux.maxConnections?.toString().orEmpty(),
            xmuxMaxConcurrency = xmux.maxConcurrency?.toString().orEmpty(),
            xmuxCMaxReuseTimes = xmux.cMaxReuseTimes?.toString().orEmpty(),
            xmuxHMaxRequestTimes = xmux.hMaxRequestTimes?.toString().orEmpty(),
            xmuxHMaxReusableSecs = xmux.hMaxReusableSecs?.toString().orEmpty(),
        )
    }

    fun toMasking(): Result {
        val problems = mutableListOf<Problem>()

        fun int(field: String, text: String, label: String): Int? {
            if (text.isBlank()) return null
            return text.trim().toIntOrNull() ?: null.also { problems += Problem.error(field, "$label: нужно целое число.") }
        }

        fun span(field: String, text: String, label: String): Span? {
            if (text.isBlank()) return null
            return Span.parse(text) ?: null.also { problems += Problem.error(field, "$label: число или диапазон вида 8-16.") }
        }

        // A switched-off group is simply absent: its leftover text is neither checked nor saved.
        val wireguard = if (!wireguardEnabled) null else WireGuardMasking(
            jc = int("jc", jc, "Jc"),
            jmin = int("jmin", jmin, "Jmin"),
            jmax = int("jmax", jmax, "Jmax"),
            signatures = i1.trim().takeIf(String::isNotEmpty)?.let { mapOf("i1" to it) }.orEmpty(),
        )

        val mux = if (!muxEnabled) null else MuxCool(
            concurrency = int("mux.concurrency", muxConcurrency, "Потоков на соединение") ?: MuxCool().concurrency,
            xudpConcurrency = int("mux.xudpConcurrency", muxXudpConcurrency, "XUDP") ?: MuxCool().xudpConcurrency,
            xudpProxyUDP443 = muxUdp443,
        )
        val xmux = if (!xmuxEnabled) null else Xmux(
            maxConnections = span("xmux.maxConnections", xmuxMaxConnections, "maxConnections"),
            maxConcurrency = span("xmux.maxConcurrency", xmuxMaxConcurrency, "maxConcurrency"),
            cMaxReuseTimes = span("xmux.cMaxReuseTimes", xmuxCMaxReuseTimes, "cMaxReuseTimes"),
            hMaxRequestTimes = span("xmux.hMaxRequestTimes", xmuxHMaxRequestTimes, "hMaxRequestTimes"),
            hMaxReusableSecs = span("xmux.hMaxReusableSecs", xmuxHMaxReusableSecs, "hMaxReusableSecs"),
        )
        val fragment = if (!fragmentEnabled) null else {
            val length = span("fragment.length", fragmentLength, "Длина куска")
            val delay = span("fragment.delay", fragmentDelay, "Пауза")
            if (length == null || delay == null) {
                if (problems.none { it.field.startsWith("fragment") }) {
                    problems += Problem.error("fragment.length", "Для фрагментации нужны длина куска и пауза.")
                }
                null
            } else {
                XrayFragment(packets = fragmentPackets.trim().ifEmpty { "tlshello" }, length = length, delay = delay)
            }
        }
        val desync = if (!desyncEnabled) null else Desync(
            method = desyncMethod,
            splitAt = desyncSplitAt.trim().lowercase().ifEmpty { "sni" },
            fakeTtl = int("desync.fakeTtl", desyncTtl, "TTL обманки") ?: Desync().fakeTtl,
            fakeSni = desyncSni.trim().lowercase(),
        )
        val xray = XrayShaping(mux = mux, xmux = xmux, fragment = fragment, desync = desync)
            .takeIf { mux != null || xmux != null || fragment != null || desync != null }

        val singbox = if (!singboxEnabled) null else SingboxFragment(
            fragment = singboxFragment,
            recordFragment = singboxRecordFragment,
            fallbackDelayMs = int("singbox.fallbackDelayMs", singboxDelayMs, "Задержка"),
        )

        val masking = Masking(wireguard = wireguard, xray = xray, singbox = singbox)
        return Result(masking, problems + MaskingValidator.validate(masking))
    }

    companion object {
        fun from(masking: Masking): MaskingForm {
            val defaults = MaskingForm()
            val wg = masking.wireguard
            val mux = masking.xray?.mux
            val xmux = masking.xray?.xmux
            val fragment = masking.xray?.fragment
            val singbox = masking.singbox
            val desync = masking.xray?.desync
            return defaults.copy(
                wireguardEnabled = wg?.enabled == true,
                jc = wg?.jc?.toString().orEmpty(),
                jmin = wg?.jmin?.toString().orEmpty(),
                jmax = wg?.jmax?.toString().orEmpty(),
                i1 = wg?.signatures?.get("i1").orEmpty(),
                muxEnabled = mux?.enabled == true,
                muxConcurrency = mux?.concurrency?.toString() ?: defaults.muxConcurrency,
                muxXudpConcurrency = mux?.xudpConcurrency?.toString() ?: defaults.muxXudpConcurrency,
                muxUdp443 = mux?.xudpProxyUDP443 ?: defaults.muxUdp443,
                xmuxEnabled = xmux != null,
                xmuxMaxConnections = xmux?.maxConnections?.toString().orEmpty(),
                xmuxMaxConcurrency = xmux?.maxConcurrency?.toString().orEmpty(),
                xmuxCMaxReuseTimes = xmux?.cMaxReuseTimes?.toString().orEmpty(),
                xmuxHMaxRequestTimes = xmux?.hMaxRequestTimes?.toString().orEmpty(),
                xmuxHMaxReusableSecs = xmux?.hMaxReusableSecs?.toString().orEmpty(),
                fragmentEnabled = fragment?.enabled == true,
                fragmentPackets = fragment?.packets ?: defaults.fragmentPackets,
                fragmentLength = fragment?.length?.toString() ?: defaults.fragmentLength,
                fragmentDelay = fragment?.delay?.toString() ?: defaults.fragmentDelay,
                desyncEnabled = desync?.enabled == true,
                desyncMethod = desync?.method ?: defaults.desyncMethod,
                desyncSplitAt = desync?.splitAt ?: defaults.desyncSplitAt,
                desyncTtl = desync?.fakeTtl?.toString() ?: defaults.desyncTtl,
                desyncSni = desync?.fakeSni ?: defaults.desyncSni,
                singboxEnabled = singbox?.enabled == true,
                singboxFragment = singbox?.fragment ?: defaults.singboxFragment,
                singboxRecordFragment = singbox?.recordFragment ?: defaults.singboxRecordFragment,
                singboxDelayMs = singbox?.fallbackDelayMs?.toString().orEmpty(),
            )
        }
    }
}
