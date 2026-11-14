package io.github.zapretkvn.android.ui.services

import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileServerGroup
import io.github.zapretkvn.android.profiles.ProfileServerOption
import io.github.zapretkvn.android.profiles.ProfileServerSummary
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.slots.ServerRef
import io.github.zapretkvn.android.vpn.SlotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicesPresenterTest {
    private val mainId = "a".repeat(32)
    private val workId = "b".repeat(32)

    private fun profile(id: String, name: String) = ProfileMetadata(id, name, ProfileSource.Subscription, 1L, 1L)

    private fun summary(vararg servers: Pair<String, String>) = ProfileServerSummary(
        listOf(
            ProfileServerGroup(
                tag = "zapret-proxy",
                selected = servers.first().first,
                options = servers.map { (tag, type) -> ProfileServerOption(tag, type, "203.0.113.1:443") },
            ),
        ),
    )

    private val profiles = listOf(profile(workId, "Work"), profile(mainId, "Main"))
    private val summaries = mapOf(
        mainId to summary("🇫🇷 Paris" to "hysteria2", "🇩🇪 Berlin" to "vless", "Direct tunnel" to "wireguard"),
        workId to summary("🇳🇴 Oslo" to "vless"),
    )

    private fun ui(
        slots: List<SlotState> = emptyList(),
        pins: Map<String, String> = emptyMap(),
        connected: Boolean = false,
        hide: Boolean = false,
    ) = buildServicesUi(
        enabled = true,
        connected = connected,
        slots = slots,
        profiles = profiles,
        summaries = summaries,
        activeProfileId = mainId,
        pins = pins,
        hide = hide,
        mainServerTitle = null,
    )

    @Test
    fun `the three services come in a fixed order and the active profile is listed first`() {
        val services = ui().services
        assertEquals(listOf("youtube", "telegram", "ai"), services.map { it.id })
        assertEquals(listOf("YouTube", "Telegram", "ИИ-приложения"), services.map { it.title })
        assertEquals(listOf("Main", "Work"), services.first().profiles.map { it.name })
        assertTrue(services.first().profiles.first().active)
    }

    @Test
    fun `only plain servers are offered and titles lose the flag`() {
        val main = ui().services.first().profiles.first()
        assertEquals(listOf("Paris", "Berlin"), main.servers.map { it.title })
        assertEquals(listOf("🇫🇷", "🇩🇪"), main.servers.map { it.flag })
    }

    @Test
    fun `a service without a session shows no server and no pin means auto`() {
        val youtube = ui().services.first()
        assertNull(youtube.serverTitle)
        assertEquals("Авто", youtube.modeLabel)
        assertNull(youtube.pinnedRef)
    }

    @Test
    fun `with the vpn off a pinned service names its server and lists it first`() {
        val ref = ServerRef(mainId, "🇩🇪 Berlin").encode()
        val youtube = ui(pins = mapOf("youtube" to ref)).services.first()
        assertEquals("Berlin", youtube.serverTitle)
        assertEquals("🇩🇪", youtube.flag)
        assertNull(youtube.profileName)
        assertEquals(listOf("Berlin", "Paris"), youtube.profiles.first().servers.map { it.title })

        val foreign = ui(pins = mapOf("youtube" to ServerRef(workId, "🇳🇴 Oslo").encode())).services.first()
        assertEquals("Oslo", foreign.serverTitle)
        assertEquals("Work", foreign.profileName)
    }

    @Test
    fun `the current server of a service on another profile carries that profile's name`() {
        val ref = ServerRef(workId, "🇳🇴 Oslo").encode()
        val state = SlotState("telegram", "Telegram", "🇳🇴 Oslo@bbbbbbbb", serverName = "🇳🇴 Oslo", profileName = "Work", serverRef = ref)
        val telegram = ui(slots = listOf(state), connected = true).services.first { it.id == "telegram" }
        assertEquals("Oslo", telegram.serverTitle)
        assertEquals("Work", telegram.profileName)
        assertTrue(telegram.profiles.single { it.profileId == workId }.servers.single().current)
    }

    @Test
    fun `a pin is marked, an older unqualified pin means the active profile, a vanished one is auto`() {
        val pinned = ui(pins = mapOf("youtube" to ServerRef(workId, "🇳🇴 Oslo").encode())).services.first()
        assertEquals("Закреплён", pinned.modeLabel)
        assertTrue(pinned.profiles.single { it.profileId == workId }.servers.single().pinned)

        val legacy = ui(pins = mapOf("youtube" to "🇩🇪 Berlin")).services.first()
        assertEquals(ServerRef(mainId, "🇩🇪 Berlin").encode(), legacy.pinnedRef)

        val gone = ui(pins = mapOf("youtube" to ServerRef("c".repeat(32), "Deleted").encode())).services.first()
        assertEquals("Авто", gone.modeLabel)
        assertNull(gone.pinnedRef)
    }

    @Test
    fun `a pinned service that sits elsewhere says the pinned server did not answer`() {
        val parisRef = ServerRef(mainId, "🇫🇷 Paris").encode()
        val berlinRef = ServerRef(mainId, "🇩🇪 Berlin").encode()
        val state = SlotState("youtube", "YouTube", "🇩🇪 Berlin", serverRef = berlinRef)
        val youtube = ui(slots = listOf(state), pins = mapOf("youtube" to parisRef), connected = true).services.first()
        assertEquals("Закреплённый сервер не отвечал", youtube.modeLabel)
    }

    @Test
    fun `search keeps matching servers and drops empty profiles`() {
        val all = ui().services.first().profiles
        assertEquals(listOf("Oslo"), filterServiceServers(all, "  osl ").flatMap { p -> p.servers.map { it.title } })
        assertEquals(listOf("Work"), filterServiceServers(all, "OSLO").map { it.name })
        assertEquals(all, filterServiceServers(all, ""))
        assertTrue(filterServiceServers(all, "nothing").isEmpty())
    }
}
