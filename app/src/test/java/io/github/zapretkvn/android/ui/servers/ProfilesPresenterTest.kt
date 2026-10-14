package io.github.zapretkvn.android.ui.servers

import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileServerGroup
import io.github.zapretkvn.android.profiles.ProfileServerOption
import io.github.zapretkvn.android.profiles.ProfileServerSummary
import io.github.zapretkvn.android.profiles.ProfileSource
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesPresenterTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(utc).apply { clear(); set(year, month, day, 12, 0) }.timeInMillis

    private val now = at(2026, Calendar.OCTOBER, 14)

    private fun profile(id: String, source: ProfileSource, created: Long = now, updated: Long = now) =
        ProfileMetadata(id, "name-$id", source, created, updated)

    @Test
    fun dayLabelOmitsCurrentYearOnly() {
        assertEquals("12 мая", dayLabel(at(2026, Calendar.MAY, 12), now, utc))
        assertEquals("1 января 2025", dayLabel(at(2025, Calendar.JANUARY, 1), now, utc))
    }

    @Test
    fun metaDiffersForSubscriptionsAndFiles() {
        val twoHours = 2 * 3_600_000L
        assertEquals(
            "Подписка · обновлено 2 ч назад",
            profileMeta(profile("a", ProfileSource.Url, updated = now - twoHours), now),
        )
        assertEquals(
            "Файл · добавлен ${dayLabel(at(2026, Calendar.MAY, 12), now)}",
            profileMeta(profile("b", ProfileSource.File, created = at(2026, Calendar.MAY, 12)), now),
        )
        assertTrue(profileMeta(profile("c", ProfileSource.Clipboard), now).startsWith("Буфер обмена · добавлен"))
        assertTrue(profileMeta(profile("d", ProfileSource.Link), now).startsWith("Ссылка · добавлена"))
    }

    @Test
    fun sectionsKeepOldGroupingAndOrderAndDropEmptyOnes() {
        val profiles = listOf(
            profile("file", ProfileSource.File),
            profile("sub", ProfileSource.Subscription),
            profile("url", ProfileSource.Url),
            profile("clip", ProfileSource.Clipboard),
            profile("json", ProfileSource.RawJson),
        )
        val sections = profileSections(profiles, emptyMap(), "url", setOf("url", "sub"), now)
        assertEquals(listOf("Подписки", "Импортированные", "Файлы и JSON"), sections.map { it.title })
        assertEquals(listOf("sub", "url"), sections[0].cards.map { it.id })
        assertEquals(listOf("file", "json"), sections[2].cards.map { it.id })

        val onlyFiles = profileSections(listOf(profiles[0]), emptyMap(), null, emptySet(), now)
        assertEquals(listOf("Файлы и JSON"), onlyFiles.map { it.title })
    }

    @Test
    fun cardCarriesActiveRefreshableAndServerInfo() {
        val nl = "🇳🇱"
        val summary = ProfileServerSummary(
            listOf(
                ProfileServerGroup(
                    "zapret-proxy", "a",
                    listOf(ProfileServerOption("$nl a", "vless", null), ProfileServerOption("b", "vless", null)),
                ),
            ),
        )
        val sections = profileSections(
            listOf(profile("url", ProfileSource.Url), profile("raw", ProfileSource.RawJson)),
            mapOf("url" to summary), "url", setOf("url"), now,
        )
        val url = sections.flatMap { it.cards }.first { it.id == "url" }
        assertTrue(url.active)
        assertTrue(url.refreshable)
        assertTrue(url.hasServers)
        assertEquals("2 сервера · 1 страна", url.counts)
        assertEquals(nl, url.flags)
        assertTrue(url.a11y.endsWith("Активный профиль"))

        val raw = sections.flatMap { it.cards }.first { it.id == "raw" }
        assertFalse(raw.active)
        assertFalse(raw.refreshable)
        assertFalse(raw.hasServers)
        assertNull(raw.counts)
    }
}
