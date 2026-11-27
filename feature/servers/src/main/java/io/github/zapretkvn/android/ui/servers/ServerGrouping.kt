package io.github.zapretkvn.android.ui.servers

import androidx.compose.runtime.Immutable
import java.util.Locale

/**
 * Группировка списка серверов, которую выбирает пользователь (владелец, 2026-11-27: «пользователь сам выбирает, как
 * группировать серверы»). Те же уровни, что в Windows-версии, кроме «Подписки»: на этом экране профиль уже один.
 *
 * Режим — строка, чтобы лежать в настройках как есть: `auto` (прежнее поведение: страны в длинной группе), `none`
 * (плоский список) либо уровни через «+», не больше трёх: `country+server+protocol`.
 */
enum class GroupLevel(val id: String, val title: String) {
    Country("country", "Страна"),
    Server("server", "Сервер"),
    Protocol("protocol", "Протокол"),
}

object ServerGrouping {
    const val AUTO = "auto"
    const val NONE = "none"
    const val MAX_LEVELS = 3

    /** Готовые режимы меню: (режим, подпись). */
    val presets: List<Pair<String, String>> = listOf(
        AUTO to "Авто: по странам в длинных списках",
        NONE to "Без групп",
        "country" to "Страна",
        "server" to "Сервер",
        "protocol" to "Протокол",
        "country+server" to "Страна › Сервер",
        "country+protocol" to "Страна › Протокол",
        "server+protocol" to "Сервер › Протокол",
        "country+server+protocol" to "Страна › Сервер › Протокол",
    )

    /** Уровни режима; `null` — «авто», пустой список — «без групп». Негодный режим читается как «авто». */
    fun levels(mode: String): List<GroupLevel>? {
        val text = mode.trim().lowercase(Locale.ROOT)
        if (text == AUTO || text.isEmpty()) return null
        if (text == NONE) return emptyList()
        val parsed = text.split('+').map { id -> GroupLevel.entries.firstOrNull { it.id == id } ?: return null }
        if (parsed.size > MAX_LEVELS || parsed.distinct().size != parsed.size) return null
        return parsed
    }

    /** Режим как строка из выбора в диалоге: пустые и повторные уровни пропускаются. */
    fun modeOf(levels: List<GroupLevel?>): String {
        val picked = levels.filterNotNull().distinct().take(MAX_LEVELS)
        return if (picked.isEmpty()) NONE else picked.joinToString("+") { it.id }
    }

    fun normalize(mode: String): String = when (val parsed = levels(mode)) {
        null -> AUTO
        else -> modeOf(parsed)
    }

    fun title(mode: String): String {
        presets.firstOrNull { it.first == mode }?.let { return it.second }
        val parsed = levels(mode) ?: return presets.first().second
        return parsed.joinToString(" › ") { it.title }
    }
}

/**
 * Папка списка: заголовок со счётчиком и лучшей задержкой, внутри — вложенные папки или строки. [rows] — все строки
 * ниже папки (для счётчика, проверки «только эту папку» и раскрытия по умолчанию); [leaves] — строки прямо в ней.
 */
@Immutable
data class ServerFolder(
    val key: String,
    val depth: Int,
    val level: GroupLevel,
    val code: String?,
    val flag: String?,
    val title: String,
    val rows: List<ServerRowUi>,
    val bestMillis: Int?,
    val children: List<ServerFolder>,
    val leaves: List<ServerRowUi>,
    /** Строки без значения этого уровня («Другие»): всегда последняя папка. */
    val other: Boolean = false,
)

/** Ключ, название и флаг папки, в которую строка попадает на этом уровне; `null` — строка «без значения». */
private data class FolderId(val key: String, val title: String, val code: String?, val flag: String?)

private fun folderIdOf(row: ServerRowUi, level: GroupLevel): FolderId? = when (level) {
    GroupLevel.Country -> row.countryCode?.let { FolderId(it, row.countryName ?: it, it, row.flag) }
    GroupLevel.Server -> row.server.takeIf(String::isNotBlank)?.let {
        FolderId(it.lowercase(Locale.ROOT), it, null, row.flag)
    }
    GroupLevel.Protocol -> row.protocol.takeIf(String::isNotBlank)?.let { FolderId(it.lowercase(Locale.ROOT), it, null, null) }
}

private fun bestOf(rows: List<ServerRowUi>): Int? =
    rows.filter { it.latency.band != null }.mapNotNull { it.latency.rankMillis }.minOrNull()

/**
 * Вкладывает строки группы в папки по [levels] (внешний уровень первым). Порядок папок: как строки или по [sort];
 * папка «Другие» (у строк нет значения этого уровня) всегда последняя. Ключ папки хранит весь путь, поэтому один и тот же
 * сервер в двух странах — две папки со своим раскрытием.
 */
fun buildFolders(
    groupTag: String,
    rows: List<ServerRowUi>,
    levels: List<GroupLevel>,
    sort: ServerSort = ServerSort.Subscription,
    depth: Int = 0,
    parentKey: String = "sec:$groupTag",
): List<ServerFolder> {
    if (levels.isEmpty() || rows.isEmpty()) return emptyList()
    val level = levels.first()
    val byId = LinkedHashMap<String?, MutableList<ServerRowUi>>()
    val ids = HashMap<String?, FolderId?>()
    for (row in rows) {
        val id = folderIdOf(row, level)
        byId.getOrPut(id?.key) { mutableListOf() } += row
        if (id != null) ids.putIfAbsent(id.key, id)
    }
    val last = levels.size == 1
    val built = byId.map { (idKey, list) ->
        val id = ids[idKey]
        val key = "$parentKey:${idKey ?: "other"}"
        ServerFolder(
            key = key,
            depth = depth,
            level = level,
            code = id?.code,
            flag = id?.flag,
            title = id?.title ?: OTHER_SECTION_TITLE,
            rows = list,
            bestMillis = bestOf(list),
            children = if (last) emptyList() else buildFolders(groupTag, list, levels.drop(1), sort, depth + 1, key),
            leaves = if (last) list else emptyList(),
            other = idKey == null,
        )
    }
    val (named, other) = built.partition { !it.other }
    val ordered = when (sort) {
        ServerSort.Subscription -> named
        ServerSort.Fastest -> named.sortedWith(compareBy<ServerFolder> { it.bestMillis == null }.thenBy { it.bestMillis ?: 0 })
        ServerSort.Name -> named.sortedBy { it.title.lowercase(Locale.ROOT) }
    }
    return ordered + other
}
