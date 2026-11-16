package io.github.zapretkvn.masking.presets

import io.github.zapretkvn.masking.model.Desync
import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.MuxCool
import io.github.zapretkvn.masking.model.SingboxFragment
import io.github.zapretkvn.masking.model.Span
import io.github.zapretkvn.masking.model.WireGuardMasking
import io.github.zapretkvn.masking.model.XrayFragment
import io.github.zapretkvn.masking.model.XrayShaping
import io.github.zapretkvn.masking.model.Xmux
import io.github.zapretkvn.masking.wireguard.DnsSignature

/**
 * Named starting points. Every number stays editable: a preset only fills the fields, and the
 * tester ("Подбор") tries several of them on the user's own network. No preset claims to
 * work everywhere; each one says what it is for.
 *
 * Group order is the expected benefit (masking-design/05): WireGuard first (UDP is not
 * affected by the measured per-connection TCP freeze), then XHTTP connection rotation, then
 * the experiments.
 */
object MaskingPresets {
    enum class Group { WireGuard, Xray, Experiment }

    data class Preset(val id: String, val group: Group, val title: String, val note: String, val masking: Masking)

    fun wireGuardDns(domain: String = DnsSignature.DOMAINS.first()): Preset = Preset(
        id = "wg-dns",
        group = Group.WireGuard,
        title = "Плановый DNS",
        note = "Перед рукопожатием уходит пакет, похожий на DNS-запрос к $domain, и немного мусора. " +
            "Работает и с обычным WireGuard-сервером.",
        masking = Masking(
            wireguard = WireGuardMasking(
                jc = 3,
                jmin = 10,
                jmax = 30,
                signatures = mapOf("i1" to checkNotNull(DnsSignature.chain(domain)) { "preset domain must be valid" }),
            ),
        ),
    )

    val wireGuardMoreJunk = Preset(
        id = "wg-more-junk",
        group = Group.WireGuard,
        title = "Больше мусора",
        note = "Больше мусорных пакетов разного размера перед рукопожатием. Каждый меньше MTU, чтобы не дробиться.",
        masking = Masking(wireguard = WireGuardMasking(jc = 6, jmin = 40, jmax = 80)),
    )

    val wireGuardJunkOnly = Preset(
        id = "wg-junk-only",
        group = Group.WireGuard,
        title = "Только junk",
        note = "Только мусорные пакеты, без сигнатурного I1: для сетей, где пакет под DNS отбрасывают.",
        masking = Masking(wireguard = WireGuardMasking(jc = 3, jmin = 10, jmax = 30)),
    )

    val xrayMux = Preset(
        id = "xray-mux",
        group = Group.Xray,
        title = "Mux (VLESS, VMess, Trojan по TCP)",
        note = "Много запросов идут в нескольких долгих соединениях: меньше рукопожатий подряд. " +
            "QUIC в mux отклоняется, приложения перейдут на TCP.",
        masking = Masking(xray = XrayShaping(mux = MuxCool())),
    )

    val xmuxCoreDefault = Preset(
        id = "xmux-default",
        group = Group.Xray,
        title = "XHTTP: как в ядре",
        note = "Значения самого Xray: 3 соединения, смена после 600-900 запросов или 30-50 минут.",
        masking = Masking(xray = XrayShaping(xmux = Xmux())),
    )

    val xmuxRotate = Preset(
        id = "xmux-rotate",
        group = Group.Xray,
        title = "XHTTP: частая смена",
        note = "Соединения живут недолго и несут мало данных: против заморозки соединения после ~16-30 КБ.",
        masking = Masking(
            xray = XrayShaping(
                xmux = Xmux(
                    maxConcurrency = Span(16, 32),
                    cMaxReuseTimes = Span(8, 16),
                    hMaxRequestTimes = Span(60, 120),
                    hMaxReusableSecs = Span(60, 120),
                ),
            ),
        ),
    )

    val xmuxFew = Preset(
        id = "xmux-few",
        group = Group.Xray,
        title = "XHTTP: мало соединений",
        note = "Два долгих соединения: меньше рукопожатий подряд, но каждое несёт больше данных.",
        masking = Masking(
            xray = XrayShaping(
                xmux = Xmux(maxConnections = Span.of(2), hMaxReusableSecs = Span(1800, 3000)),
            ),
        ),
    )

    val xrayFragment = Preset(
        id = "xray-fragment",
        group = Group.Experiment,
        title = "Фрагментация TLS (Xray)",
        note = "Эксперимент. Первый пакет TLS режется на куски с паузами. Помогает против блокировки по имени " +
            "сайта, но не против заморозки соединения.",
        masking = Masking(xray = XrayShaping(fragment = XrayFragment())),
    )

    val singboxFragment = Preset(
        id = "singbox-fragment",
        group = Group.Experiment,
        title = "Фрагментация TLS (AnyTLS, HTTP, ShadowTLS)",
        note = "Эксперимент. Для протоколов sing-box. На REALITY не действует.",
        masking = Masking(singbox = SingboxFragment()),
    )

    fun desyncFake(ttl: Int) = Preset(
        id = "desync-fake-$ttl",
        group = Group.Experiment,
        title = "Обманка, TTL $ttl",
        note = "Эксперимент. Перед настоящим первым пакетом уходит пакет-обманка с другим именем сайта и " +
            "коротким TTL: его видит фильтр, но не сервер. TTL зависит от сети: подбор пробует несколько.",
        masking = Masking(xray = XrayShaping(desync = Desync(method = "fake", fakeTtl = ttl))),
    )

    val desyncDisorder = Preset(
        id = "desync-disorder",
        group = Group.Experiment,
        title = "Беспорядок пакетов",
        note = "Эксперимент. Начало первого пакета, с частью имени сайта, отправляется так, что теряется по пути, " +
            "и уходит повторно после остального: фильтр видит куски не по порядку.",
        masking = Masking(xray = XrayShaping(desync = Desync(method = "disorder"))),
    )

    val all: List<Preset>
        get() = listOf(
            wireGuardDns(),
            wireGuardMoreJunk,
            wireGuardJunkOnly,
            xrayMux,
            xmuxCoreDefault,
            xmuxRotate,
            xmuxFew,
            xrayFragment,
            desyncFake(3),
            desyncFake(5),
            desyncDisorder,
            singboxFragment,
        )
}
