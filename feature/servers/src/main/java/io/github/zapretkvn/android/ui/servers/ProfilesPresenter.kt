package io.github.zapretkvn.android.ui.servers

import androidx.compose.runtime.Immutable
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileServerSummary
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.core.uimodel.formatAge
import java.util.Calendar
import java.util.TimeZone

/** Карточка профиля на экране «Профили»: всё уже превращено в строки. */
@Immutable
data class ProfileCardUi(
    val id: String,
    val name: String,
    val active: Boolean,
    val counts: String?,
    val flags: String?,
    val extraFlags: Int,
    /** «Подписка · обновлено 2 ч назад», «Файл · добавлен 12 мая». */
    val meta: String,
    val refreshable: Boolean,
    val hasServers: Boolean,
    val a11y: String,
)

@Immutable
data class ProfileSectionUi(val title: String, val cards: List<ProfileCardUi>)

fun ProfileSource.label(): String = when (this) {
    ProfileSource.RawJson -> "JSON"
    ProfileSource.File -> "Файл"
    ProfileSource.Clipboard -> "Буфер обмена"
    ProfileSource.Link -> "Ссылка"
    ProfileSource.Qr -> "QR-код"
    ProfileSource.Url, ProfileSource.Subscription -> "Подписка"
}

private fun ProfileSource.isSubscription() = this == ProfileSource.Url || this == ProfileSource.Subscription

private val MONTHS = arrayOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)

/** «12 мая»; если год не текущий — «12 мая 2025». */
fun dayLabel(epochMillis: Long, nowEpochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val date = Calendar.getInstance(zone).apply { timeInMillis = epochMillis }
    val now = Calendar.getInstance(zone).apply { timeInMillis = nowEpochMillis }
    val base = "${date.get(Calendar.DAY_OF_MONTH)} ${MONTHS[date.get(Calendar.MONTH)]}"
    return if (date.get(Calendar.YEAR) == now.get(Calendar.YEAR)) base else "$base ${date.get(Calendar.YEAR)}"
}

fun profileMeta(profile: ProfileMetadata, nowEpochMillis: Long): String {
    val source = profile.source
    return if (source.isSubscription()) {
        "${source.label()} · обновлено ${formatAge(nowEpochMillis - profile.updatedAtEpochMillis)}"
    } else {
        val verb = if (source == ProfileSource.Link) "добавлена" else "добавлен"
        "${source.label()} · $verb ${dayLabel(profile.createdAtEpochMillis, nowEpochMillis)}"
    }
}

/** Секции экрана «Профили» в прежнем порядке: подписки, импортированные, файлы и JSON. */
fun profileSections(
    profiles: List<ProfileMetadata>,
    summaries: Map<String, ProfileServerSummary>,
    activeProfileId: String?,
    refreshableIds: Set<String>,
    nowEpochMillis: Long,
    hostCountries: Map<String, String> = emptyMap(),
): List<ProfileSectionUi> {
    fun card(profile: ProfileMetadata): ProfileCardUi {
        val summary = summaries[profile.id]
        val stats = summary?.let { serverStats(memberTags(it), summaryCountryHints(it, hostCountries)) }
        val active = profile.id == activeProfileId
        val meta = profileMeta(profile, nowEpochMillis)
        val counts = stats?.countsLabel()
        return ProfileCardUi(
            id = profile.id,
            name = profile.name,
            active = active,
            counts = counts,
            flags = stats?.flagStrip(),
            extraFlags = stats?.extraFlags() ?: 0,
            meta = meta,
            refreshable = profile.id in refreshableIds,
            hasServers = summary != null && summary.groups.isNotEmpty(),
            a11y = buildString {
                append(profile.name)
                counts?.let { append(". ").append(it) }
                append(". ").append(meta)
                if (active) append(". Активный профиль")
            },
        )
    }

    fun section(title: String, vararg sources: ProfileSource) = ProfileSectionUi(
        title,
        profiles.filter { it.source in sources }.map(::card),
    )

    return listOf(
        section("Подписки", ProfileSource.Url, ProfileSource.Subscription),
        section("Импортированные", ProfileSource.Clipboard, ProfileSource.Link, ProfileSource.Qr),
        section("Файлы и JSON", ProfileSource.File, ProfileSource.RawJson),
    ).filter { it.cards.isNotEmpty() }
}
