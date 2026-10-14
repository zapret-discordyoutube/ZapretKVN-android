package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.network.probes.services.Headline
import io.github.zapretkvn.android.network.probes.services.ServiceAvailability
import io.github.zapretkvn.android.network.probes.services.ServiceAvailabilityState
import io.github.zapretkvn.android.network.probes.services.ServiceCatalog
import io.github.zapretkvn.android.network.probes.services.ServiceId
import io.github.zapretkvn.android.network.probes.services.ServiceSnapshot
import io.github.zapretkvn.android.network.probes.services.ServiceVerdict
import io.github.zapretkvn.android.vpn.LATENCY_FRESHNESS_MILLIS
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.uimodel.formatAge
import io.github.zapretkvn.core.uimodel.formatPing

/** Одна строка карточки «Доступность сервисов»: всё уже готово к показу. */
data class ServiceRowUi(
    val id: ServiceId,
    val title: String,
    /** Короткий вердикт: «Через VPN», «Напрямую», «Недоступно»… */
    val verdict: String,
    /** Пояснение под вердиктом; переносится, не обрезается. */
    val detail: String?,
    val tone: Tone,
    /** Задержка основного запроса, «84 мс». */
    val latency: String?,
    /** «2 мин назад» или «устарело»; пусто, пока не проверено. */
    val age: String?,
    val stale: Boolean,
    val checking: Boolean,
    val description: String,
)

data class ServicesCardUi(
    val summary: String,
    val rows: List<ServiceRowUi>,
    val checking: Boolean,
    /** Подсказка под списком: когда проверено или что нажать. */
    val footnote: String,
    val hasResults: Boolean,
    /** Совет под списком (проверка без VPN): что делать с результатом; null — нечего советовать. */
    val advice: String? = null,
    /** Подпись кнопки на карточке, пока проверять нечего показывать («Проверить без VPN»); иначе null. */
    val actionLabel: String? = null,
)

fun headlineLabel(headline: Headline): String = when (headline) {
    Headline.ViaVpn -> "Через VPN"
    Headline.Direct -> "Напрямую"
    Headline.Both -> "Открывается"
    Headline.Partial -> "Частично"
    Headline.GeoRefused -> "Сервис не работает из региона сервера"
    Headline.Unavailable -> "Недоступно"
    Headline.NotChecked -> "Не проверено"
}

fun headlineTone(headline: Headline): Tone = when (headline) {
    Headline.ViaVpn, Headline.Both -> Tone.Good
    Headline.Direct -> Tone.Accent
    Headline.Partial, Headline.GeoRefused -> Tone.Warning
    Headline.Unavailable -> Tone.Bad
    Headline.NotChecked -> Tone.Neutral
}

/** Что именно не так или хорошо; без адресов и без вины провайдера там, где мы её не знаем. */
fun headlineDetail(snapshot: ServiceSnapshot): String? = when (snapshot.headline) {
    Headline.ViaVpn -> when (snapshot.direct.verdict) {
        ServiceVerdict.Blocked, ServiceVerdict.Unreachable -> "Без VPN не открывается"
        else -> null
    }
    Headline.Direct -> "Идёт мимо VPN по правилам маршрутов"
    Headline.Both -> "Открывается и с VPN, и без него"
    Headline.Partial -> "Сайт открывается, часть ресурсов нет"
    Headline.GeoRefused -> "Смените сервер: сервис отказал этому выходу"
    Headline.Unavailable -> when (snapshot.tunnel.verdict) {
        ServiceVerdict.Blocked, ServiceVerdict.Unreachable -> "Не открывается через сервер VPN"
        else -> "Не открывается"
    }
    Headline.NotChecked -> null
}

private fun latencyText(millis: Int?): String? = millis?.let { formatPing(it.toLong()) }

/**
 * Карточка целиком из состояния проверки. Давность считается от [nowMillis]:
 * результат старше пяти минут показывается как устаревший, даже если сеть и
 * сервер не менялись.
 */
fun servicesCardUi(
    state: ServiceAvailabilityState,
    nowMillis: Long,
    services: List<ServiceId> = ServiceCatalog.defaults.map { it.id },
): ServicesCardUi {
    val rows = services.map { id -> row(id, state.items[id] ?: ServiceAvailability.NotChecked, nowMillis) }
    val snapshots = services.mapNotNull { state.items[it]?.let(::snapshotOf) }
    val checking = state.running
    val anyStale = rows.any { it.stale }
    val opened = snapshots.count { it.headline in OPEN_HEADLINES }
    val summary = when {
        checking -> "Проверяем сервисы…"
        snapshots.isEmpty() -> "Пока не проверено"
        opened == services.size -> "Все ${services.size} открываются"
        else -> "Открывается: $opened из ${services.size}"
    }
    val oldest = snapshots.minOfOrNull { it.measuredAtEpochMillis }
    val footnote = when {
        checking -> "Проверка идёт, это несколько секунд"
        oldest == null -> "Нажмите, чтобы проверить"
        anyStale -> "Данные устарели. Нажмите, чтобы обновить"
        else -> "Проверено ${formatAge(nowMillis - oldest)}. Нажмите, чтобы повторить"
    }
    return ServicesCardUi(summary, rows, checking, footnote, hasResults = snapshots.isNotEmpty())
}

private val OPEN_HEADLINES = setOf(Headline.ViaVpn, Headline.Direct, Headline.Both)

private fun snapshotOf(availability: ServiceAvailability): ServiceSnapshot? = when (availability) {
    ServiceAvailability.NotChecked -> null
    is ServiceAvailability.Checking -> availability.previous
    is ServiceAvailability.Result -> availability.snapshot
    is ServiceAvailability.Stale -> availability.snapshot
}

private fun row(id: ServiceId, availability: ServiceAvailability, nowMillis: Long): ServiceRowUi {
    val title = id.title
    return when (availability) {
        ServiceAvailability.NotChecked -> ServiceRowUi(
            id, title, headlineLabel(Headline.NotChecked), null, Tone.Neutral, null, null,
            stale = false, checking = false, description = "$title: не проверено",
        )
        is ServiceAvailability.Checking -> ServiceRowUi(
            id, title, "Проверяем…", null, Tone.Neutral, null, null,
            stale = false, checking = true, description = "$title: проверяем",
        )
        is ServiceAvailability.Result -> resultRow(id, availability.snapshot, nowMillis, stale = false)
        is ServiceAvailability.Stale -> resultRow(id, availability.snapshot, nowMillis, stale = true)
    }
}

private fun resultRow(id: ServiceId, snapshot: ServiceSnapshot, nowMillis: Long, stale: Boolean): ServiceRowUi {
    val age = (nowMillis - snapshot.measuredAtEpochMillis).coerceAtLeast(0)
    val old = stale || age >= LATENCY_FRESHNESS_MILLIS
    val verdict = headlineLabel(snapshot.headline)
    val latency = latencyText(snapshot.latencyMillis)
    val ageText = if (old) "устарело" else formatAge(age)
    val detail = headlineDetail(snapshot)
    return ServiceRowUi(
        id = id,
        title = id.title,
        verdict = verdict,
        detail = detail,
        tone = if (old) Tone.Neutral else headlineTone(snapshot.headline),
        latency = latency,
        age = ageText,
        stale = old,
        checking = false,
        description = buildString {
            append(id.title).append(": ").append(verdict)
            detail?.let { append(". ").append(it) }
            latency?.let { append(". Отклик ").append(it) }
            append(". ").append(if (old) "Результат устарел" else "Проверено $ageText")
        },
    )
}
