package io.github.zapretkvn.android.ui.servers

import androidx.compose.runtime.Immutable
import io.github.zapretkvn.android.network.probes.PersistedServerLatency
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileServerSummary
import io.github.zapretkvn.android.ui.ScreenshotPrivacy
import io.github.zapretkvn.android.vpn.LATENCY_FRESHNESS_MILLIS
import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeProgress
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencyUnsupportedReason
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.uimodel.LATENCY_OLD_MILLIS
import io.github.zapretkvn.core.uimodel.LatencyBand
import io.github.zapretkvn.core.uimodel.CountryGuess
import io.github.zapretkvn.core.uimodel.ServerName
import io.github.zapretkvn.core.uimodel.inferCountries
import io.github.zapretkvn.core.uimodel.clearlyFastest
import io.github.zapretkvn.core.uimodel.countLabel
import io.github.zapretkvn.core.uimodel.formatAge
import io.github.zapretkvn.core.uimodel.formatPing
import io.github.zapretkvn.core.uimodel.plural
import io.github.zapretkvn.core.uimodel.protocolLabel
import io.github.zapretkvn.core.uimodel.protocolMonogram

/*
 * Чистые преобразования «домен → то, что показываем» для вкладки «Серверы» и
 * быстрого выбора на Главной. Ничего Android-специфичного: всё проверяется
 * обычными JVM-тестами. Composable-код только рисует готовые модели.
 */

internal const val DASH = "—"

/** Порог, начиная с которого появляются поиск и фильтры. */
internal const val FILTER_THRESHOLD = 8

internal enum class LatencyIcon { None, Ok, Slow, Failed, Old }

/** Задержка в виде, готовом к показу: число, слово, тон и иконка (цвет — не единственный носитель смысла). */
@Immutable
internal data class LatencyDisplay(
    val value: String,
    val word: String,
    val tone: Tone,
    val icon: LatencyIcon,
    /** Известная задержка в мс (в том числе устаревшая): по ней сортируем. */
    val rankMillis: Int?,
    /** Только для свежего удачного замера: по нему считаются «быстрые» и «лучший». */
    val band: LatencyBand?,
    val running: Boolean = false,
    val failed: Boolean = false,
) {
    val tested: Boolean get() = rankMillis != null || failed
}

private val UNTESTED = LatencyDisplay(DASH, "не проверен", Tone.Neutral, LatencyIcon.None, null, null)

internal fun latencyDisplay(state: LatencyProbeState?, nowEpochMillis: Long): LatencyDisplay = when (state) {
    null, LatencyProbeState.NotTested -> UNTESTED
    is LatencyProbeState.Running -> LatencyDisplay(
        value = state.previous?.let { formatPing(it.millis.toLong()) } ?: DASH,
        word = "проверяем…",
        tone = Tone.Neutral,
        icon = LatencyIcon.None,
        rankMillis = state.previous?.millis,
        band = null,
        running = true,
    )
    is LatencyProbeState.Success -> {
        val age = nowEpochMillis - state.sample.measuredAtEpochMillis
        if (age >= LATENCY_OLD_MILLIS) {
            oldLatency(state.sample.millis, "давно (${formatAge(age)})")
        } else {
            val band = LatencyBand.of(state.sample.millis)
            LatencyDisplay(
                value = formatPing(state.sample.millis.toLong()),
                word = band.word,
                tone = band.tone(),
                icon = band.icon(),
                rankMillis = state.sample.millis,
                band = band,
            )
        }
    }
    is LatencyProbeState.Stale -> {
        val age = nowEpochMillis - state.sample.measuredAtEpochMillis
        // Свежий замер, но из другой сети Android: число ещё полезно, но не как «лучший».
        oldLatency(
            state.sample.millis,
            if (age < LATENCY_FRESHNESS_MILLIS) "другая сеть" else "давно (${formatAge(age)})",
        )
    }
    is LatencyProbeState.Failed -> LatencyDisplay(
        value = DASH,
        word = when (state.reason) {
            LatencyFailure.NoResponse -> "не отвечает"
            LatencyFailure.Dns -> "не нашёлся адрес"
            LatencyFailure.Failed -> "ошибка проверки"
        },
        tone = Tone.Bad,
        icon = LatencyIcon.Failed,
        rankMillis = null,
        band = null,
        failed = true,
    )
    is LatencyProbeState.Unsupported -> LatencyDisplay(
        value = DASH,
        word = when (state.reason) {
            LatencyUnsupportedReason.MissingEndpoint -> "адрес неизвестен"
            LatencyUnsupportedReason.NestedGroup -> "внутренняя группа"
        },
        tone = Tone.Neutral,
        icon = LatencyIcon.None,
        rankMillis = null,
        band = null,
    )
}

private fun oldLatency(millis: Int, word: String) = LatencyDisplay(
    value = formatPing(millis.toLong()),
    word = word,
    tone = Tone.Neutral,
    icon = LatencyIcon.Old,
    rankMillis = millis,
    band = null,
)

private fun LatencyBand.tone(): Tone = when (this) {
    LatencyBand.Fast -> Tone.Good
    LatencyBand.Normal -> Tone.Warning
    LatencyBand.Slow -> Tone.Bad
}

private fun LatencyBand.icon(): LatencyIcon = when (this) {
    LatencyBand.Fast -> LatencyIcon.Ok
    LatencyBand.Normal -> LatencyIcon.None
    LatencyBand.Slow -> LatencyIcon.Slow
}

// ---------------------------------------------------------------------------
// Строка сервера
// ---------------------------------------------------------------------------

@Immutable
internal data class ServerRowUi(
    val key: String,
    val groupTag: String,
    /** Исходный тег сервера: именно он уходит в выбор. */
    val tag: String,
    val title: String,
    val flag: String?,
    val countryCode: String?,
    val countryName: String?,
    val protocol: String,
    val monogram: String,
    /** Уже с учётом скрытия адресов. */
    val endpoint: String?,
    val latency: LatencyDisplay,
    val selected: Boolean,
    /** «★ Лучший», «★ Сейчас выбран» и т. п. */
    val badge: String?,
    val searchText: String,
    val a11y: String,
)

internal fun serverRowUi(
    groupTag: String,
    tag: String,
    type: String,
    endpoint: String?,
    hideAddresses: Boolean,
    latency: LatencyDisplay,
    selected: Boolean,
    countryHint: String? = null,
): ServerRowUi {
    // Страна и протокол берутся из настоящего тега, даже когда его текст скрыт маской:
    // маскируется только то, что видно на экране, а не то, что мы о сервере знаем.
    val masked = ScreenshotPrivacy.serverLabel(tag, hideAddresses)
    val labelHidden = masked != tag
    val parsed = ServerName.parse(tag)
    val title = if (labelHidden) masked else parsed.title
    val protocolType = type.ifBlank { parsed.protocolPrefix.orEmpty() }
    val protocol = protocolLabel(protocolType).ifBlank { "Сервер" }
    // Адрес скрыт — строки с ним нет совсем (ни «********», ни в озвучке); показываем только по настройке.
    val shownEndpoint = if (hideAddresses) null else endpoint
    // Нет флага в имени — страна по адресу сервера (GeoIP), по соседу на том же
    // адресе или по словам имени/хоста, см. inferCountries.
    val hintCode = countryHint?.takeIf { parsed.countryCode == null }
    val countryCode = parsed.countryCode ?: hintCode
    val flag = parsed.flag ?: hintCode?.let(CountryGuess::flagOf)
    val countryName = parsed.countryName ?: hintCode?.let(::russianCountryName)
    val search = buildString {
        append(title)
        if (!labelHidden) append(' ').append(parsed.original)
        append(' ').append(protocol)
        countryName?.let { append(' ').append(it) }
        countryCode?.let { append(' ').append(it) }
        if (!hideAddresses && !endpoint.isNullOrBlank()) append(' ').append(endpoint)
    }.lowercase()
    val a11y = buildString {
        append(title)
        countryName?.let { append(", ").append(it) }
        append(", ").append(protocol)
        if (latency.rankMillis != null && latency.value != DASH) {
            append(", задержка ").append(latency.rankMillis).append(' ')
            append(plural(latency.rankMillis, "миллисекунда", "миллисекунды", "миллисекунд"))
        }
        append(", ").append(latency.word)
        if (selected) append(", выбран")
    }
    return ServerRowUi(
        key = "$groupTag/$tag",
        groupTag = groupTag,
        tag = tag,
        title = title,
        flag = flag,
        countryCode = countryCode,
        countryName = countryName,
        protocol = protocol,
        monogram = protocolMonogram(protocolType),
        endpoint = shownEndpoint?.takeIf(String::isNotBlank),
        latency = latency,
        selected = selected,
        badge = null,
        searchText = search,
        a11y = a11y,
    )
}

// ---------------------------------------------------------------------------
// Группы
// ---------------------------------------------------------------------------

internal enum class GroupKind(val title: String, val explanation: String?) {
    Selector("Ручной выбор", "Вы сами выбираете сервер. Приложение его не меняет."),
    UrlTest(
        "Авто · самый быстрый",
        "Приложение само выбирает самый быстрый сервер по тесту. Выбирать вручную не нужно.",
    ),
    Fallback(
        "Резервные",
        "Используется первый рабочий сервер по порядку. Если он упал, переключится на следующий.",
    ),
    Balancer("Распределение", "Нагрузка делится между серверами."),
    Other("Группа серверов", null),
    ;

    companion object {
        fun of(type: String): GroupKind = when (type.lowercase()) {
            "selector", "" -> Selector
            "urltest", "url-test", "url_test" -> UrlTest
            "fallback" -> Fallback
            "balancer", "loadbalance", "load-balance", "leastping", "leastload" -> Balancer
            else -> Other
        }
    }
}

@Immutable
internal data class BetterHint(val serverTag: String, val text: String)

@Immutable
internal data class ServerGroupUi(
    val tag: String,
    val kind: GroupKind,
    val title: String,
    val explanation: String?,
    val selectable: Boolean,
    val primary: Boolean,
    val rows: List<ServerRowUi>,
    /** Данные идут из запущенного ядра: проверка и мгновенное переключение доступны. */
    val live: Boolean,
    val measuring: Boolean,
    val progressFraction: Float?,
    val probeLine: String?,
    val better: BetterHint?,
    /** «Сейчас: 🇳🇱 Amsterdam-2 · 84 мс быстро» — для свёрнутой карточки. */
    val currentLine: String?,
)

private val GENERIC_GROUP_TAGS = Regex("(?i)^(proxy|select|selector|zapret-proxy|auto|urltest|fallback|main|primary|default)$")

/** Тег группы без «шума» (`proxy`, `select`): если ничего осмысленного не осталось — null. */
internal fun groupTagLabel(tag: String): String? =
    tag.trim().takeIf { it.isNotEmpty() && !GENERIC_GROUP_TAGS.matches(it) }

internal data class MemberInput(
    val tag: String,
    val type: String,
    val endpoint: String?,
    val latency: LatencyProbeState?,
)

internal fun buildGroup(
    tag: String,
    type: String,
    selectable: Boolean,
    primary: Boolean,
    selected: String?,
    members: List<MemberInput>,
    progress: LatencyProbeProgress?,
    live: Boolean,
    hideAddresses: Boolean,
    nowEpochMillis: Long,
    countryHints: Map<String, String> = emptyMap(),
): ServerGroupUi {
    val kind = GroupKind.of(type)
    val rows = members.distinctBy { it.tag }.map { member ->
        serverRowUi(
            groupTag = tag,
            tag = member.tag,
            type = member.type,
            endpoint = member.endpoint,
            hideAddresses = hideAddresses,
            latency = latencyDisplay(member.latency, nowEpochMillis),
            selected = member.tag == selected,
            countryHint = countryHints[member.tag],
        )
    }
    return decorateGroup(
        tag = tag, kind = kind, selectable = selectable, primary = primary, rows = rows,
        progress = progress, live = live,
    )
}

internal fun decorateGroup(
    tag: String,
    kind: GroupKind,
    selectable: Boolean,
    primary: Boolean,
    rows: List<ServerRowUi>,
    progress: LatencyProbeProgress?,
    live: Boolean,
): ServerGroupUi {
    val candidates = rows.filter { it.latency.band != null }
    val best = if (selectable && kind != GroupKind.UrlTest) {
        clearlyFastest(candidates.map { it.key to checkNotNull(it.latency.rankMillis) })
    } else {
        null
    }
    val labelled = rows.map { row ->
        val badge = when {
            !selectable && row.selected -> "★ Сейчас выбран"
            best == row.key && row.selected -> "★ Выбран и самый быстрый"
            best == row.key -> "★ Лучший"
            else -> null
        }
        if (badge == null) row else row.copy(badge = badge)
    }
    val current = labelled.firstOrNull { it.selected }
    val bestRow = labelled.firstOrNull { it.key == best }
    val better = if (
        selectable && current != null && bestRow != null && bestRow.key != current.key &&
        current.latency.band != null
    ) {
        val diff = checkNotNull(current.latency.rankMillis) - checkNotNull(bestRow.latency.rankMillis)
        if (diff >= BETTER_HINT_MIN_DIFF_MILLIS) {
            BetterHint(bestRow.tag, "${bestRow.title} быстрее текущего на $diff мс")
        } else {
            null
        }
    } else {
        null
    }
    val measuring = progress?.running == true
    val tagLabel = groupTagLabel(tag)
    return ServerGroupUi(
        tag = tag,
        kind = kind,
        title = tagLabel?.let { "$it · ${kind.title}" } ?: kind.title,
        explanation = kind.explanation,
        selectable = selectable,
        primary = primary,
        rows = labelled,
        live = live,
        measuring = measuring,
        progressFraction = progress?.takeIf { it.running && it.relayTotal > 0 }
            ?.let { (it.relayCompleted.toFloat() / it.relayTotal).coerceIn(0f, 1f) },
        probeLine = if (labelled.size > 1) probeLine(labelled, progress, live) else null,
        better = better,
        currentLine = current?.let(::currentLine),
    )
}

private const val BETTER_HINT_MIN_DIFF_MILLIS = 30

private fun probeLine(rows: List<ServerRowUi>, progress: LatencyProbeProgress?, live: Boolean): String {
    if (!live) return "Проверка доступна после подключения"
    if (progress?.running == true) {
        val total = progress.relayTotal.takeIf { it > 0 } ?: rows.size
        return "Проверено ${progress.relayCompleted.coerceAtMost(total)} из $total"
    }
    val tested = rows.filter { it.latency.tested }
    if (tested.isNotEmpty() && tested.all { it.latency.failed }) {
        return "Ни один сервер не ответил. Возможно, проблема в сети, а не в серверах"
    }
    val fastest = rows.filter { it.latency.band != null }.minByOrNull { checkNotNull(it.latency.rankMillis) }
    return when {
        fastest != null -> "Лучший: ${fastest.title}, ${fastest.latency.value}"
        tested.isEmpty() -> "Нажмите «Проверить все», чтобы увидеть, какой сервер быстрее"
        else -> "Проверено ${tested.size} из ${rows.size}"
    }
}

private fun currentLine(row: ServerRowUi): String = buildString {
    append("Сейчас: ")
    row.flag?.let { append(it).append(' ') }
    append(row.title)
    if (row.latency.value != DASH) append(" · ").append(row.latency.value).append(' ').append(row.latency.word)
}

/** Группы запущенного ядра: задержки живые (Relay HTTPS, ICMP — вторичен). */
internal fun liveServerGroups(
    groups: List<RuntimeSelectorGroup>,
    hideAddresses: Boolean,
    nowEpochMillis: Long,
    hostCountries: Map<String, String> = emptyMap(),
): List<ServerGroupUi> {
    val primaryTag = (groups.firstOrNull { it.primary } ?: groups.firstOrNull())?.tag
    val hints = inferCountries(
        groups.flatMap { g -> g.items.map { it.tag to it.endpoint } }.distinctBy { it.first },
        hostCountries,
    )
    return groups.filter { it.items.isNotEmpty() }.map { group ->
        buildGroup(
            tag = group.tag,
            type = group.type,
            selectable = group.selectable,
            primary = group.tag == primaryTag,
            selected = group.selected,
            members = group.items.map { MemberInput(it.tag, it.type, it.endpoint, it.relay) },
            progress = group.probeProgress,
            live = true,
            hideAddresses = hideAddresses,
            nowEpochMillis = nowEpochMillis,
            countryHints = hints,
        )
    }
}

/** Группы из сохранённого профиля: задержки — последние сохранённые, проверка недоступна. */
internal fun offlineServerGroups(
    summary: ProfileServerSummary,
    savedLatency: Map<String, PersistedServerLatency>,
    hideAddresses: Boolean,
    nowEpochMillis: Long,
    hostCountries: Map<String, String> = emptyMap(),
): List<ServerGroupUi> {
    val primaryTag = summary.primaryGroup?.tag
    val hints = summaryCountryHints(summary, hostCountries)
    return summary.groups.filter { it.options.isNotEmpty() }.map { group ->
        buildGroup(
            tag = group.tag,
            type = "selector",
            selectable = true,
            primary = group.tag == primaryTag,
            selected = group.selected,
            members = group.options.map {
                MemberInput(it.tag, it.type, it.endpoint, savedLatency[it.tag]?.displayState())
            },
            progress = null,
            live = false,
            hideAddresses = hideAddresses,
            nowEpochMillis = nowEpochMillis,
            countryHints = hints,
        )
    }
}

// ---------------------------------------------------------------------------
// Шапка профиля и статистика
// ---------------------------------------------------------------------------

internal data class ServerStats(val servers: Int, val countries: Int, val flags: List<String>) {
    /** «48 серверов · 6 стран»; без флагов в именах — просто «48 серверов». */
    fun countsLabel(): String? {
        if (servers == 0) return null
        val base = countLabel(servers, "сервер", "сервера", "серверов")
        return if (countries > 0) "$base · ${countLabel(countries, "страна", "страны", "стран")}" else base
    }

    fun flagStrip(limit: Int = FLAG_LIMIT): String? =
        flags.take(limit).joinToString(" ").takeIf(String::isNotEmpty)

    fun extraFlags(limit: Int = FLAG_LIMIT): Int = (flags.size - limit).coerceAtLeast(0)
}

internal const val FLAG_LIMIT = 5

internal fun serverStats(tags: Collection<String>, countryHints: Map<String, String> = emptyMap()): ServerStats {
    val codes = tags.distinct().map { tag -> ServerName.parse(tag).countryCode ?: countryHints[tag] }
    return ServerStats(
        servers = codes.size,
        countries = codes.filterNotNull().distinct().size,
        flags = codes.filterNotNull().distinct().map(CountryGuess::flagOf),
    )
}

/** Подсказки стран для серверов профиля без флага в имени. */
internal fun summaryCountryHints(
    summary: ProfileServerSummary,
    hostCountries: Map<String, String> = emptyMap(),
): Map<String, String> =
    inferCountries(
        summary.groups.flatMap { g -> g.options.map { it.tag to it.endpoint } }.distinctBy { it.first },
        hostCountries,
    )

internal fun russianCountryName(code: String): String? =
    java.util.Locale("", code).getDisplayCountry(java.util.Locale("ru")).takeIf(String::isNotBlank)

/** Серверы профиля без самих групп: вложенный selector не считается сервером. */
internal fun memberTags(summary: ProfileServerSummary): List<String> {
    val groupTags = summary.groups.map { it.tag }.toSet()
    return summary.groups.flatMap { group -> group.options.map { it.tag } }
        .distinct()
        .filter { it !in groupTags }
}

@Immutable
internal data class ProfileHeaderUi(
    val name: String,
    val counts: String?,
    val flags: String?,
    val extraFlags: Int,
    val updated: String,
)

internal fun profileHeader(
    profile: ProfileMetadata,
    stats: ServerStats,
    nowEpochMillis: Long,
): ProfileHeaderUi = ProfileHeaderUi(
    name = profile.name,
    counts = stats.countsLabel(),
    flags = stats.flagStrip(),
    extraFlags = stats.extraFlags(),
    updated = "Обновлено ${formatAge(nowEpochMillis - profile.updatedAtEpochMillis)}",
)

// ---------------------------------------------------------------------------
// Фильтры и сортировка
// ---------------------------------------------------------------------------

internal sealed interface ServerChip {
    data object All : ServerChip
    data object Fast : ServerChip
    data class Country(val code: String) : ServerChip
}

@Immutable
internal data class ServerFilter(
    val query: String = "",
    val chip: ServerChip = ServerChip.All,
) {
    val active: Boolean get() = query.isNotBlank() || chip != ServerChip.All
}

internal fun ServerRowUi.matches(filter: ServerFilter): Boolean {
    val words = filter.query.trim().lowercase().split(' ').filter(String::isNotBlank)
    if (words.any { !searchText.contains(it) }) return false
    return when (val chip = filter.chip) {
        ServerChip.All -> true
        ServerChip.Fast -> latency.band == LatencyBand.Fast || latency.band == LatencyBand.Normal
        is ServerChip.Country -> countryCode == chip.code
    }
}

@Immutable
internal data class ChipUi(val chip: ServerChip, val label: String, val count: Int)

@Immutable
internal data class ServerChips(val primary: List<ChipUi>, val more: List<ChipUi>)

private const val COUNTRY_CHIPS = 6

internal fun serverChips(groups: List<ServerGroupUi>): ServerChips {
    val rows = groups.flatMap { it.rows }.distinctBy { it.tag }
    val fast = rows.count { it.latency.band == LatencyBand.Fast || it.latency.band == LatencyBand.Normal }
    val countries = rows.filter { it.countryCode != null }
        .groupBy { it.countryCode!! }
        .entries
        .sortedByDescending { it.value.size } // сортировка устойчива: при равенстве порядок подписки
        .map { (code, list) ->
            ChipUi(ServerChip.Country(code), "${list.first().flag.orEmpty()} ${list.size}".trim(), list.size)
        }
    val primary = buildList {
        add(ChipUi(ServerChip.All, "Все ${rows.size}", rows.size))
        if (fast > 0) add(ChipUi(ServerChip.Fast, "⚡ Быстрые $fast", fast))
        addAll(countries.take(COUNTRY_CHIPS))
    }
    return ServerChips(primary, countries.drop(COUNTRY_CHIPS))
}

internal enum class ServerSort(val label: String) {
    Subscription("Как в подписке"),
    Fastest("Сначала быстрые"),
    Name("По названию"),
}

/**
 * Порядок тегов, зафиксированный в момент выбора сортировки: дальнейшие замеры
 * строки не переставляют. Пустой список — «как в подписке».
 */
internal fun frozenOrder(groups: List<ServerGroupUi>, sort: ServerSort): List<String> {
    val rows = groups.flatMap { it.rows }.distinctBy { it.tag }
    return when (sort) {
        ServerSort.Subscription -> emptyList()
        ServerSort.Fastest -> rows.withIndex()
            .sortedWith(
                compareBy<IndexedValue<ServerRowUi>> { it.value.latency.rankMillis == null }
                    .thenBy { it.value.latency.rankMillis ?: 0 }
                    .thenBy { it.index },
            )
            .map { it.value.tag }
        ServerSort.Name -> rows.sortedBy { it.title.lowercase() }.map { it.tag }
    }
}

// ---------------------------------------------------------------------------
// Плоский список для LazyColumn
// ---------------------------------------------------------------------------

internal enum class RowPosition { Only, First, Middle, Last }

@Immutable
internal sealed interface ServerListItem {
    val key: String
    val contentType: String

    data class Profile(val header: ProfileHeaderUi) : ServerListItem {
        override val key get() = "profile"
        override val contentType get() = "profile"
    }

    data object Tools : ServerListItem {
        override val key get() = "tools"
        override val contentType get() = "tools"
    }

    data class Group(
        val group: ServerGroupUi,
        val expanded: Boolean,
        val collapsible: Boolean,
        val shown: Int,
        val showControls: Boolean,
    ) : ServerListItem {
        override val key get() = "group-${group.tag}"
        override val contentType get() = "group"
    }

    /** Свёрнутая/развёрнутая секция «страна» внутри группы с большим числом серверов. */
    data class Section(
        val section: CountrySection,
        val expanded: Boolean,
        val hasSelected: Boolean,
    ) : ServerListItem {
        override val key get() = section.key
        override val contentType get() = "section"
        val meta: String
            get() = buildString {
                append(countLabel(section.rows.size, "сервер", "сервера", "серверов"))
                section.bestLabel?.let { append(" · лучший ").append(it) }
                if (hasSelected && !expanded) append(" · выбран")
            }
    }

    data class Server(
        val row: ServerRowUi,
        val position: RowPosition,
        val readOnly: Boolean,
        /** Строка появляется плавно: только в небольших секциях (≤ [ANIMATE_SECTION_MAX]). */
        val animate: Boolean = false,
    ) : ServerListItem {
        override val key get() = row.key
        override val contentType get() = "server"
    }

    data class Note(override val key: String, val text: String) : ServerListItem {
        override val contentType get() = "note"
    }

    data object EmptyFilter : ServerListItem {
        override val key get() = "empty-filter"
        override val contentType get() = "empty-filter"
    }
}

@Immutable
internal data class ServerListModel(
    val items: List<ServerListItem>,
    /** Индекс выбранной строки в [items] или -1. */
    val selectedIndex: Int,
    val showTools: Boolean,
    val uniqueServers: Int,
)

/** Группа с большим числом серверов делится на секции по странам. */
internal const val SECTION_THRESHOLD = 12

/** Анимация раскрытия — только для секций не длиннее этого, иначе раскрываем мгновенно. */
internal const val ANIMATE_SECTION_MAX = 15

internal const val OTHER_SECTION_TITLE = "Другие"

/** Секция «страна» внутри группы: заголовок со счётчиком и лучшей задержкой. */
@Immutable
internal data class CountrySection(
    val key: String,
    val code: String?,
    val flag: String?,
    val title: String,
    val rows: List<ServerRowUi>,
    /** Лучшая свежая задержка в мс (только удачные свежие замеры) или null. */
    val bestMillis: Int?,
) {
    val bestLabel: String? get() = bestMillis?.let { formatPing(it.toLong()) }
}

/**
 * Состояние раскрытия секций, заданное пользователем. Без записи секция раскрыта,
 * если в ней выбранный сервер; запись «открыто»/«закрыто» это переопределяет.
 */
@Immutable
internal data class SectionState(
    val opened: Set<String> = emptySet(),
    val closed: Set<String> = emptySet(),
) {
    fun expanded(key: String, byDefault: Boolean): Boolean =
        key in opened || (byDefault && key !in closed)

    fun with(key: String, expand: Boolean): SectionState =
        if (expand) copy(opened = opened + key, closed = closed - key)
        else copy(opened = opened - key, closed = closed + key)

    fun save(): ArrayList<String> = ArrayList<String>().apply {
        opened.forEach { add("o:$it") }
        closed.forEach { add("c:$it") }
    }

    companion object {
        fun restore(list: List<String>) = SectionState(
            opened = list.filter { it.startsWith("o:") }.map { it.drop(2) }.toSet(),
            closed = list.filter { it.startsWith("c:") }.map { it.drop(2) }.toSet(),
        )
    }
}

private fun sectionKey(groupTag: String, code: String?) = "sec:$groupTag:${code ?: "other"}"

/**
 * Делит строки группы на секции по странам. Порядок: как строки (подписка) или по
 * сортировке [sort]; «Другие» (без страны) всегда в конце. Если делить нечего
 * (меньше двух секций или ни у кого нет страны) — null: показываем плоский список.
 */
internal fun countrySections(
    groupTag: String,
    rows: List<ServerRowUi>,
    sort: ServerSort = ServerSort.Subscription,
): List<CountrySection>? {
    if (rows.none { it.countryCode != null }) return null
    val byCode = LinkedHashMap<String?, MutableList<ServerRowUi>>()
    rows.forEach { byCode.getOrPut(it.countryCode) { mutableListOf() } += it }
    if (byCode.size < 2) return null
    val sections = byCode.map { (code, list) ->
        val first = list.first()
        CountrySection(
            key = sectionKey(groupTag, code),
            code = code,
            flag = first.flag.takeIf { code != null },
            title = if (code == null) OTHER_SECTION_TITLE else first.countryName ?: code,
            rows = list,
            bestMillis = list.filter { it.latency.band != null }.mapNotNull { it.latency.rankMillis }.minOrNull(),
        )
    }
    val (named, other) = sections.partition { it.code != null }
    val ordered = when (sort) {
        ServerSort.Subscription -> named
        ServerSort.Fastest -> named.sortedWith(compareBy<CountrySection> { it.bestMillis == null }.thenBy { it.bestMillis ?: 0 })
        ServerSort.Name -> named.sortedBy { it.title.lowercase() }
    }
    return ordered + other
}

internal fun buildServerList(
    groups: List<ServerGroupUi>,
    header: ProfileHeaderUi?,
    filter: ServerFilter,
    order: List<String> = emptyList(),
    toggledGroups: Set<String> = emptySet(),
    compact: Boolean = false,
    groupCards: Boolean = true,
    sections: SectionState = SectionState(),
    sort: ServerSort = ServerSort.Subscription,
    /** Серверы, чьи секции раскрыты по умолчанию кроме выбранного (где был выбор при открытии экрана). */
    pinnedTags: Set<String> = emptySet(),
): ServerListModel {
    val uniqueServers = groups.flatMap { it.rows }.distinctBy { it.tag }.size
    val single = uniqueServers == 1
    val showTools = uniqueServers > FILTER_THRESHOLD
    val activeFilter = if (showTools) filter else ServerFilter()
    val position = order.withIndex().associate { (index, tag) -> tag to index }
    val defaultExpanded = (groups.firstOrNull { it.primary } ?: groups.firstOrNull())?.tag
    val collapsible = groups.size > 1 && !single

    val items = mutableListOf<ServerListItem>()
    // Идёт поиск или фильтр: карточка профиля не нужна, результаты должны быть сразу под рукой.
    if (header != null && !activeFilter.active) items += ServerListItem.Profile(header)
    if (showTools) items += ServerListItem.Tools
    if (single) {
        groups.firstOrNull()?.rows?.firstOrNull()?.let { row ->
            if (!compact) items += ServerListItem.Note("single", "Единственный сервер в профиле")
            items += ServerListItem.Server(row, RowPosition.Only, readOnly = groups.first().let { !it.selectable })
        }
        return ServerListModel(items, items.indexOfFirst { it is ServerListItem.Server && it.row.selected }, false, uniqueServers)
    }

    var anyRows = false
    for (group in groups) {
        var rows = group.rows
        if (activeFilter.active) rows = rows.filter { it.matches(activeFilter) }
        if (position.isNotEmpty()) rows = rows.sortedBy { position[it.tag] ?: Int.MAX_VALUE }
        if (activeFilter.active && rows.isEmpty()) continue
        val expanded = activeFilter.active || !collapsible ||
            ((group.tag == defaultExpanded) != (group.tag in toggledGroups))
        if (groupCards && !(activeFilter.active && groups.size == 1)) {
            items += ServerListItem.Group(
                group = group,
                expanded = expanded,
                collapsible = collapsible && !activeFilter.active,
                shown = rows.size,
                showControls = true,
            )
        }
        if (!group.selectable && !compact && groupCards && !(activeFilter.active && groups.size == 1)) {
            items += ServerListItem.Note("auto-${group.tag}", "Сервер выбирается автоматически. Менять не нужно")
        }
        if (!expanded) continue
        val countrySections = if (!activeFilter.active && group.rows.size > SECTION_THRESHOLD) {
            countrySections(group.tag, rows, sort)
        } else {
            null
        }
        if (countrySections == null) {
            rows.forEachIndexed { index, row ->
                val pos = when {
                    rows.size == 1 -> RowPosition.Only
                    index == 0 -> RowPosition.First
                    index == rows.lastIndex -> RowPosition.Last
                    else -> RowPosition.Middle
                }
                items += ServerListItem.Server(row, pos, readOnly = !group.selectable)
                anyRows = true
            }
        } else {
            for (section in countrySections) {
                val hasSelected = section.rows.any { it.selected }
                val open = sections.expanded(section.key, byDefault = hasSelected || section.rows.any { it.tag in pinnedTags })
                items += ServerListItem.Section(section, expanded = open, hasSelected = hasSelected)
                if (!open) continue
                val animate = section.rows.size <= ANIMATE_SECTION_MAX
                section.rows.forEachIndexed { index, row ->
                    val pos = if (index == section.rows.lastIndex) RowPosition.Last else RowPosition.Middle
                    items += ServerListItem.Server(row, pos, readOnly = !group.selectable, animate = animate)
                    anyRows = true
                }
            }
        }
    }
    if (activeFilter.active && !anyRows) items += ServerListItem.EmptyFilter
    return ServerListModel(
        items = items,
        selectedIndex = items.indexOfFirst { it is ServerListItem.Server && it.row.selected },
        showTools = showTools,
        uniqueServers = uniqueServers,
    )
}

// ---------------------------------------------------------------------------
// Что показывает вкладка «Серверы»
// ---------------------------------------------------------------------------

@Immutable
internal sealed interface ServersUi {
    /** Профилей нет вообще. */
    data object Empty : ServersUi

    /** Профили есть, но активный не выбран. */
    data object NoActive : ServersUi

    /** Профиль без группы серверов (свой JSON): выбирать нечего. */
    data class NoServers(val profileId: String, val header: ProfileHeaderUi) : ServersUi

    data class Content(
        val profileId: String,
        val header: ProfileHeaderUi,
        val groups: List<ServerGroupUi>,
    ) : ServersUi
}

/**
 * Модель вкладки «Серверы». При подключённом VPN профиль берёт группы из
 * запущенного ядра ([runtimeGroups], живые замеры); иначе — из сохранённого
 * профиля ([summaries]) с последними сохранёнными задержками.
 */
internal fun buildServersUi(
    profiles: List<ProfileMetadata>,
    activeProfileId: String?,
    summaries: Map<String, ProfileServerSummary>,
    savedLatency: Map<String, Map<String, PersistedServerLatency>>,
    runtimeGroups: List<RuntimeSelectorGroup>,
    live: Boolean,
    hideAddresses: Boolean,
    nowEpochMillis: Long,
    hostCountries: Map<String, String> = emptyMap(),
): ServersUi {
    if (profiles.isEmpty()) return ServersUi.Empty
    val profile = profiles.firstOrNull { it.id == activeProfileId } ?: return ServersUi.NoActive
    val summary = summaries[profile.id]
    val groups = when {
        live && runtimeGroups.any { it.items.isNotEmpty() } ->
            liveServerGroups(runtimeGroups, hideAddresses, nowEpochMillis, hostCountries)
        summary != null ->
            offlineServerGroups(
                summary, savedLatency[profile.id].orEmpty(), hideAddresses, nowEpochMillis, hostCountries,
            )
        else -> emptyList()
    }
    if (groups.isEmpty()) {
        return ServersUi.NoServers(profile.id, profileHeader(profile, ServerStats(0, 0, emptyList()), nowEpochMillis))
    }
    val groupTags = groups.map { it.tag }.toSet()
    val rows = groups.flatMap { it.rows }.filter { it.tag !in groupTags }.distinctBy { it.tag }
    val stats = ServerStats(
        servers = rows.size,
        countries = rows.mapNotNull { it.countryCode }.distinct().size,
        flags = rows.mapNotNull { it.countryCode }.distinct().map(CountryGuess::flagOf),
    )
    return ServersUi.Content(profile.id, profileHeader(profile, stats, nowEpochMillis), groups)
}
