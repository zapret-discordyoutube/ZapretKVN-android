package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind
import io.github.zapretkvn.android.network.probes.services.PathResult
import io.github.zapretkvn.android.network.probes.services.ServiceAvailability
import io.github.zapretkvn.android.network.probes.services.ServiceAvailabilityState
import io.github.zapretkvn.android.network.probes.services.ServiceId
import io.github.zapretkvn.android.network.probes.services.ServiceSnapshot
import io.github.zapretkvn.android.network.probes.services.ServiceVerdict
import io.github.zapretkvn.android.network.probes.services.snapshot
import io.github.zapretkvn.android.vpn.LATENCY_FRESHNESS_MILLIS
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.uimodel.formatAge
import io.github.zapretkvn.core.uimodel.formatPing

/**
 * Что показывает карточка, пока VPN остановлен: результат проверки прямым путём.
 * Слова: «Открывается напрямую», «Заблокировано» (сброс, тайм-аут или сбой
 * защищённого соединения: так выглядит DPI) и «Недоступно» для остальных отказов.
 */
internal data class DirectVerdict(val label: String, val detail: String?, val tone: Tone, val blocked: Boolean, val opens: Boolean)

internal fun directVerdict(path: PathResult): DirectVerdict = when (path.verdict) {
    ServiceVerdict.Ok -> DirectVerdict("Открывается напрямую", null, Tone.Good, blocked = false, opens = true)
    ServiceVerdict.Partial ->
        DirectVerdict("Открывается частично", "Часть ресурсов сервиса не открылась", Tone.Warning, blocked = false, opens = true)
    ServiceVerdict.GeoRefused ->
        DirectVerdict("Недоступно", "Сервис не работает для вашего региона", Tone.Warning, blocked = false, opens = false)
    ServiceVerdict.Blocked -> when (path.failure) {
        HttpsProbeFailureKind.Reset ->
            DirectVerdict("Заблокировано", "Соединение сбрасывается: похоже на блокировку провайдера", Tone.Bad, blocked = true, opens = false)
        HttpsProbeFailureKind.Timeout ->
            DirectVerdict("Заблокировано", "Нет ответа: похоже на блокировку провайдера", Tone.Bad, blocked = true, opens = false)
        HttpsProbeFailureKind.Tls ->
            DirectVerdict("Заблокировано", "Защищённое соединение не устанавливается: возможна блокировка", Tone.Bad, blocked = true, opens = false)
        else ->
            DirectVerdict("Недоступно", "Адрес сайта не определяется (DNS)", Tone.Bad, blocked = false, opens = false)
    }
    ServiceVerdict.Unreachable -> DirectVerdict(
        "Недоступно",
        if (path.failure == HttpsProbeFailureKind.HttpStatus) "Сервис ответил ошибкой" else "Не удалось подключиться",
        Tone.Bad,
        blocked = false,
        opens = false,
    )
    ServiceVerdict.NotMeasured -> DirectVerdict("Не проверено", null, Tone.Neutral, blocked = false, opens = false)
}

/** «YouTube», «YouTube и Instagram», «YouTube, Instagram и Discord», «YouTube, Instagram, Discord и ещё 2». */
internal fun joinTitles(titles: List<String>, maxNames: Int = 3): String = when {
    titles.size <= 1 -> titles.joinToString()
    titles.size <= maxNames -> titles.dropLast(1).joinToString(", ") + " и " + titles.last()
    else -> titles.take(maxNames).joinToString(", ") + " и ещё ${titles.size - maxNames}"
}

/**
 * Карточка при остановленном VPN. Строки только выбранных сервисов; пока ничего не
 * проверялось, строк нет: показываются приглашение и кнопка «Проверить без VPN».
 */
fun directServicesCardUi(
    state: ServiceAvailabilityState,
    nowMillis: Long,
    services: List<ServiceId>,
): ServicesCardUi {
    val snapshots = services.mapNotNull { state.items[it]?.snapshot() }
    val settled = services.count { state.items[it].let { item -> item is ServiceAvailability.Result || item is ServiceAvailability.Stale } }
    val hasResults = snapshots.isNotEmpty()
    if (state.running) {
        val rows = services.map { id -> directRow(id, state.items[id] ?: ServiceAvailability.NotChecked, nowMillis) }
        return ServicesCardUi(
            summary = "Проверяем без VPN…",
            rows = rows,
            checking = true,
            footnote = "Идёт $settled из ${services.size}",
            hasResults = hasResults,
        )
    }
    if (!hasResults) {
        return if (state.noNetwork) {
            ServicesCardUi("Нет интернета", emptyList(), false, "Проверьте подключение", false, actionLabel = "Проверить снова")
        } else {
            ServicesCardUi(
                summary = "Узнайте, что у вас заблокировано",
                rows = emptyList(),
                checking = false,
                footnote = "Проверим популярные сервисы без VPN. Данные нигде не сохраняются",
                hasResults = false,
                actionLabel = "Проверить без VPN",
            )
        }
    }
    val rows = services.map { id -> directRow(id, state.items[id] ?: ServiceAvailability.NotChecked, nowMillis) }
    val anyStale = rows.any { it.stale }
    val measured = services.mapNotNull { id -> state.items[id]?.snapshot()?.let { id to directVerdict(it.direct) } }
    val failing = measured.filter { !it.second.opens }
    val summary: String
    val advice: String?
    when {
        failing.isEmpty() -> {
            summary = "Всё открывается без VPN"
            advice = "VPN сейчас не нужен для этих сервисов"
        }
        else -> {
            val allBlocked = failing.all { it.second.blocked }
            summary = if (allBlocked) {
                "Заблокировано ${failing.size} из ${measured.size}"
            } else {
                "Не открывается: ${failing.size} из ${measured.size}"
            }
            val names = joinTitles(failing.map { it.first.title })
            val verb = if (failing.size == 1) "не открывается" else "не открываются"
            advice = "$names $verb без VPN. Включите VPN"
        }
    }
    val oldest = snapshots.minOf(ServiceSnapshot::measuredAtEpochMillis)
    val footnote = when {
        state.noNetwork -> "Нет интернета. Проверьте подключение"
        anyStale -> "Данные устарели. Нажмите, чтобы обновить"
        else -> "Проверено ${formatAge(nowMillis - oldest)}. Нажмите, чтобы повторить"
    }
    return ServicesCardUi(summary, rows, false, footnote, true, advice = advice)
}

private fun directRow(id: ServiceId, availability: ServiceAvailability, nowMillis: Long): ServiceRowUi {
    val title = id.title
    return when (availability) {
        ServiceAvailability.NotChecked -> ServiceRowUi(
            id, title, "Не проверено", null, Tone.Neutral, null, null,
            stale = false, checking = false, description = "$title: не проверено",
        )
        is ServiceAvailability.Checking -> ServiceRowUi(
            id, title, "Проверяем…", null, Tone.Neutral, null, null,
            stale = false, checking = true, description = "$title: проверяем",
        )
        is ServiceAvailability.Result -> directResultRow(id, availability.snapshot, nowMillis, stale = false)
        is ServiceAvailability.Stale -> directResultRow(id, availability.snapshot, nowMillis, stale = true)
    }
}

private fun directResultRow(id: ServiceId, snapshot: ServiceSnapshot, nowMillis: Long, stale: Boolean): ServiceRowUi {
    val verdict = directVerdict(snapshot.direct)
    val age = (nowMillis - snapshot.measuredAtEpochMillis).coerceAtLeast(0)
    val old = stale || age >= LATENCY_FRESHNESS_MILLIS
    val latency = if (verdict.opens) snapshot.direct.latencyMillis?.let { formatPing(it.toLong()) } else null
    val ageText = if (old) "устарело" else formatAge(age)
    return ServiceRowUi(
        id = id,
        title = id.title,
        verdict = verdict.label,
        detail = verdict.detail,
        tone = if (old) Tone.Neutral else verdict.tone,
        latency = latency,
        age = ageText,
        stale = old,
        checking = false,
        description = buildString {
            append(id.title).append(": ").append(verdict.label)
            verdict.detail?.let { append(". ").append(it) }
            latency?.let { append(". Отклик ").append(it) }
            append(". ").append(if (old) "Результат устарел" else "Проверено $ageText")
        },
    )
}
