package io.github.zapretkvn.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.core.designsystem.CardTitle
import io.github.zapretkvn.core.designsystem.SettingsRow
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.SwitchRow
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.toneColor
import io.github.zapretkvn.masking.form.MaskingForm
import io.github.zapretkvn.masking.merge.MaskingSettings
import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.MaskingOverride
import io.github.zapretkvn.masking.model.MuxCool
import io.github.zapretkvn.masking.presets.ServerChoices
import io.github.zapretkvn.masking.model.Problem
import io.github.zapretkvn.masking.wireguard.DnsSignature

/**
 * «Маскировка»: общий набор для всех серверов. Экран только показывает и редактирует черновик
 * ([MaskingForm]); проверка и перевод в настройки — в модуле :masking. Сохраняется кнопкой
 * «Применить» целиком: каждое нажатие клавиши не перезапускает VPN.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MaskingScreen(
    contentPadding: PaddingValues,
    settings: MaskingSettings,
    onEnabledChange: (Boolean) -> Unit,
    onApply: (Masking) -> Unit,
    servers: List<MaskingServer> = emptyList(),
    onOverride: (serverKey: String, override: MaskingOverride) -> Unit = { _, _ -> },
) {
    var form by remember(settings.global) { mutableStateOf(MaskingForm.from(settings.global)) }
    var dnsDomain by remember { mutableStateOf(DnsSignature.DOMAINS.first()) }
    val result = form.toMasking()
    val changed = result.masking != settings.global

    SettingsPageList(contentPadding, tag = "settings-page-masking") {
        item(key = "intro", contentType = "card") {
            ZapretCard(contentPadding = PaddingValues(bottom = Spacing.m), verticalSpacing = 0.dp) {
                SwitchRow(
                    title = "Маскировка",
                    subtitle = "Меняет вид соединения с VPN-сервером для систем фильтрации. По умолчанию " +
                        "выключена: пока она выключена, подключение такое же, как раньше.",
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChange,
                    icon = ZapretIcons.Shield,
                    modifier = Modifier.testTag("masking-enabled"),
                    switchDescription = "Маскировка",
                )
                HintText(
                    "Каждый сервер получает только то, что понимает его ядро: WireGuard — мусорные и " +
                        "сигнатурные пакеты, VLESS — настройки Xray. Для отдельного сервера маскировку можно " +
                        "выключить или выбрать свою в его карточке.",
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
            }
        }

        item(key = "wireguard", contentType = "card") {
            ZapretCard {
                SwitchRow(
                    title = "WireGuard-маскировка",
                    subtitle = "Перед рукопожатием уходят мусорные пакеты и пакет, похожий на обычный трафик. " +
                        "Работает и с обычным WireGuard-сервером.",
                    checked = form.wireguardEnabled,
                    onCheckedChange = { form = if (it && form.jc.isBlank()) form.withWireGuardPreset("wg-dns", dnsDomain) else form.copy(wireguardEnabled = it) },
                    modifier = Modifier.testTag("masking-wireguard"),
                )
                if (form.wireguardEnabled) {
                    ChipRow(
                        listOf("wg-dns" to "Плановый DNS", "wg-more-junk" to "Больше мусора", "wg-junk-only" to "Только junk"),
                        selected = null,
                        onSelect = { form = form.withWireGuardPreset(it, dnsDomain) },
                        tagPrefix = "masking-wg-preset",
                    )
                    Text("Имя в DNS-пакете", style = MaterialTheme.typography.labelLarge)
                    ChipRow(
                        DnsSignature.DOMAINS.map { it to it },
                        selected = dnsDomain,
                        onSelect = { domain ->
                            dnsDomain = domain
                            DnsSignature.chain(domain)?.let { form = form.copy(i1 = it) }
                        },
                        tagPrefix = "masking-wg-domain",
                    )
                    NumberField("Jc — число мусорных пакетов", form.jc, "masking-jc") { form = form.copy(jc = it) }
                    NumberField("Jmin — наименьший размер, байт", form.jmin, "masking-jmin") { form = form.copy(jmin = it) }
                    NumberField("Jmax — наибольший размер, байт", form.jmax, "masking-jmax") { form = form.copy(jmax = it) }
                    TextField("I1 — сигнатурный пакет (теги <b>, <r>, <t>, <rc>, <rd>)", form.i1, "masking-i1") { form = form.copy(i1 = it) }
                    HintText("H1-H4 и S1-S4 не меняются: они должны совпадать с сервером и остаются из профиля.")
                }
                Problems(result.problems, prefixes = listOf("jc", "jmin", "jmax", "i"))
            }
        }

        item(key = "xray", contentType = "card") {
            ZapretCard {
                CardTitle("Xray-соединения (VLESS)")
                SwitchRow(
                    title = "Mux",
                    subtitle = "Запросы идут в нескольких долгих соединениях: меньше рукопожатий подряд. " +
                        "Для VLESS по TCP и REALITY; с XTLS Vision в mux идёт только UDP.",
                    checked = form.muxEnabled,
                    onCheckedChange = { form = form.copy(muxEnabled = it) },
                    modifier = Modifier.testTag("masking-mux"),
                )
                if (form.muxEnabled) {
                    NumberField("Потоков на соединение (Xray поднимает до 128)", form.muxConcurrency, "masking-mux-concurrency") {
                        form = form.copy(muxConcurrency = it)
                    }
                    Text("QUIC (UDP 443) в mux", style = MaterialTheme.typography.labelLarge)
                    ChipRow(
                        MuxCool.UDP443_MODES.map { it to udp443Title(it) },
                        selected = form.muxUdp443,
                        onSelect = { form = form.copy(muxUdp443 = it) },
                        tagPrefix = "masking-mux-udp443",
                    )
                }
                SwitchRow(
                    title = "XHTTP: смена соединений (xmux)",
                    subtitle = "Сколько живёт и сколько несёт одно соединение. Против заморозки соединения после " +
                        "~16-30 КБ помогают короткие соединения, против частых рукопожатий — долгие.",
                    checked = form.xmuxEnabled,
                    onCheckedChange = { form = if (it) form.withXmuxPreset("xmux-default") else form.copy(xmuxEnabled = false) },
                    modifier = Modifier.testTag("masking-xmux"),
                )
                if (form.xmuxEnabled) {
                    ChipRow(
                        listOf("xmux-default" to "Как в ядре", "xmux-rotate" to "Частая смена", "xmux-few" to "Мало соединений"),
                        selected = null,
                        onSelect = { form = form.withXmuxPreset(it) },
                        tagPrefix = "masking-xmux-preset",
                    )
                    TextField("maxConnections — соединений", form.xmuxMaxConnections, "masking-xmux-maxConnections") { form = form.copy(xmuxMaxConnections = it) }
                    TextField("maxConcurrency — запросов на соединение", form.xmuxMaxConcurrency, "masking-xmux-maxConcurrency") { form = form.copy(xmuxMaxConcurrency = it) }
                    TextField("cMaxReuseTimes — повторов соединения", form.xmuxCMaxReuseTimes, "masking-xmux-cMaxReuseTimes") { form = form.copy(xmuxCMaxReuseTimes = it) }
                    TextField("hMaxRequestTimes — запросов HTTP", form.xmuxHMaxRequestTimes, "masking-xmux-hMaxRequestTimes") { form = form.copy(xmuxHMaxRequestTimes = it) }
                    TextField("hMaxReusableSecs — секунд жизни", form.xmuxHMaxReusableSecs, "masking-xmux-hMaxReusableSecs") { form = form.copy(xmuxHMaxReusableSecs = it) }
                    HintText("Числа или диапазоны вида 8-16: Xray берёт случайное значение из диапазона. Пустое поле — как в ядре.")
                }
                Problems(result.problems, prefixes = listOf("mux", "xmux"))
            }
        }

        item(key = "experiments", contentType = "card") {
            ZapretCard {
                CardTitle("Эксперименты")
                HintText(
                    "Помогают против блокировки по имени сайта в первом пакете TLS, но не против заморозки " +
                        "соединения. Проверяйте на своей сети.",
                )
                SwitchRow(
                    title = "Фрагментация TLS (Xray)",
                    subtitle = "Первый пакет TLS режется на куски с паузами.",
                    checked = form.fragmentEnabled,
                    onCheckedChange = { form = form.copy(fragmentEnabled = it) },
                    modifier = Modifier.testTag("masking-fragment"),
                )
                if (form.fragmentEnabled) {
                    TextField("Пакеты: tlshello или номера, например 1-3", form.fragmentPackets, "masking-fragment-packets") { form = form.copy(fragmentPackets = it) }
                    TextField("Длина куска, байт", form.fragmentLength, "masking-fragment-length") { form = form.copy(fragmentLength = it) }
                    TextField("Пауза между кусками, мс", form.fragmentDelay, "masking-fragment-delay") { form = form.copy(fragmentDelay = it) }
                }
                SwitchRow(
                    title = "Фрагментация TLS (Trojan, VMess)",
                    subtitle = "То же для протоколов sing-box. На REALITY не действует.",
                    checked = form.singboxEnabled,
                    onCheckedChange = { form = form.copy(singboxEnabled = it) },
                    modifier = Modifier.testTag("masking-singbox"),
                )
                if (form.singboxEnabled) {
                    SwitchRow(
                        title = "Делить на TCP-сегменты",
                        checked = form.singboxFragment,
                        onCheckedChange = { form = form.copy(singboxFragment = it) },
                    )
                    SwitchRow(
                        title = "Делить на TLS-записи",
                        checked = form.singboxRecordFragment,
                        onCheckedChange = { form = form.copy(singboxRecordFragment = it) },
                    )
                    NumberField("Запасная задержка, мс (пусто — как в ядре)", form.singboxDelayMs, "masking-singbox-delay") { form = form.copy(singboxDelayMs = it) }
                }
                Problems(result.problems, prefixes = listOf("fragment", "singbox"))
            }
        }

        if (servers.isNotEmpty()) {
            item(key = "servers", contentType = "card") {
                ZapretCard {
                    CardTitle("Серверы активного профиля")
                    HintText("Для отдельного сервера маскировку можно выключить или выбрать готовый набор.")
                    servers.forEach { server -> ServerChoiceRow(server, onOverride) }
                }
            }
        }

        item(key = "apply", contentType = "card") {
            Button(
                onClick = { onApply(result.masking) },
                enabled = changed && !result.hasErrors,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("masking-apply"),
            ) { Text("Применить") }
            HintText(
                if (result.hasErrors) "Исправьте ошибки выше: настройки с ошибкой ядру не передаются."
                else "Подключённый VPN перезапустится. Сохранённые профили не переписываются.",
            )
        }
    }
}

/** One server of the active profile on the page; [key] is the stored override key. */
data class MaskingServer(val key: String, val name: String, val type: String, val override: MaskingOverride)

@Composable
private fun ServerChoiceRow(server: MaskingServer, onOverride: (String, MaskingOverride) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choices = remember(server.type) { ServerChoices.forType(server.type) }
    Box {
        SettingsRow(
            title = server.name,
            subtitle = "${server.type.uppercase()} · ${ServerChoices.label(server.override)}",
            onClick = { open = true },
            modifier = Modifier.testTag("masking-server-${server.key}"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.title) },
                    onClick = {
                        open = false
                        onOverride(server.key, choice.override)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, tagPrefix: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        options.forEach { (id, title) ->
            FilterChip(
                selected = id == selected,
                onClick = { onSelect(id) },
                label = { Text(title) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("$tagPrefix-$id"),
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

@Composable
private fun TextField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

@Composable
private fun Problems(problems: List<Problem>, prefixes: List<String>) {
    problems.filter { problem -> prefixes.any { problem.field.startsWith(it) } }.forEach { problem ->
        Text(
            problem.message,
            style = MaterialTheme.typography.bodySmall,
            color = toneColor(if (problem.severity == Problem.Severity.Error) Tone.Bad else Tone.Warning),
        )
    }
}

private fun udp443Title(mode: String): String = when (mode) {
    "reject" -> "Отклонять"
    "allow" -> "Пускать в mux"
    else -> "Мимо mux"
}
