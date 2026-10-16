package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.importer.SubscriptionSource
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileListAndCatalogControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val state = MutableStateFlow(ProfilesUiState())
    private val vpn = FakeVpnPort()
    private val settings = FakeSettingsPort()
    private val operations = ProfilesOperations(state, scope)
    private val renamed = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<String>()
    private val removedBootstrap = mutableListOf<String>()
    private val seenProfiles = mutableListOf<List<ProfileMetadata>>()
    private lateinit var store: ProfileStore
    private lateinit var sources: SubscriptionSourceStore
    private lateinit var list: ProfileListController
    private lateinit var catalog: ServerCatalogController

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun setUp() = runBlocking {
        store = ProfileStore(temporaryFolder.newFolder("profiles"), JsonSyntaxValidator, JvmAtomicProfileWriter())
        sources = SubscriptionSourceStore(temporaryFolder.newFolder("subs"), JvmAtomicProfileWriter())
        catalog = ServerCatalogController(state, operations, scope, store, vpn)
        list = ProfileListController(
            state, operations, scope, store, settings, vpn, sources,
            removeBootstrap = { removedBootstrap += it },
            onProfilesChanged = { seenProfiles += it },
            onProfileRenamed = { id, name -> renamed += id to name },
            onProfileDeleted = { deleted += it },
        )
        store.initialize()
    }

    private suspend fun idle() = awaitCondition { !state.value.busy }

    private suspend fun create(name: String, json: String = SELECTOR_JSON) =
        store.create(name, json, ProfileSource.Link)

    @Test
    fun `start publishes profiles and settings and reports initialization`() = runBlocking {
        setUp()
        val profile = create("Один")

        list.start()
        awaitCondition { state.value.initialized }

        assertEquals(listOf(profile.id), state.value.profiles.map { it.id })
        awaitCondition { seenProfiles.isNotEmpty() }
    }

    @Test
    fun `start clears an active profile that no longer exists`() = runBlocking {
        setUp()
        settings.setActiveProfile("ghost")

        list.start()
        awaitCondition { settings.settings.value.activeProfileId == null }
    }

    @Test
    fun `select validates the id then switches the running vpn`() = runBlocking {
        setUp()
        val profile = create("Один")
        list.start()
        awaitCondition { state.value.profiles.isNotEmpty() }

        list.select("ghost")
        awaitCondition { state.value.message == "Профиль не найден." }

        list.select(profile.id)
        awaitCondition { settings.settings.value.activeProfileId == profile.id }
        idle()
        assertEquals("Активный профиль выбран.", state.value.message)

        vpn.connected = "other"
        list.select(profile.id)
        awaitCondition { state.value.message == "Активный профиль выбран; VPN переключается." }
    }

    @Test
    fun `rename and delete notify the editor and clean related state`() = runBlocking {
        setUp()
        val first = create("Один")
        val second = create("Два")
        sources.put(first.id, SubscriptionSource("https://sub.example/list"))
        list.start()
        awaitCondition { state.value.profiles.size == 2 }
        settings.setActiveProfile(first.id)
        awaitCondition { state.value.settings.activeProfileId == first.id }

        list.rename(second.id, "Новое")
        awaitCondition { state.value.message == "Профиль переименован." }
        assertEquals(listOf(second.id to "Новое"), renamed)

        list.delete(first.id)
        awaitCondition { state.value.message == "Профиль удалён." }
        idle()
        assertEquals(listOf(first.id), deleted)
        assertEquals(listOf(first.id), removedBootstrap)
        assertNull(sources.binding(first.id))
        awaitCondition { settings.settings.value.activeProfileId == second.id }
    }

    @Test
    fun `summaries are computed once per content change and latency cleanup runs`() = runBlocking {
        setUp()
        val profile = create("Один")

        catalog.refreshSummaries(store.profiles.value)
        awaitCondition { state.value.serverSummaries.containsKey(profile.id) }

        val summary = state.value.serverSummaries.getValue(profile.id)
        assertEquals(2, summary.serverCount)
        assertEquals("a", summary.selectedLabel)
    }

    @Test
    fun `picker rejects profiles without a selector`() = runBlocking {
        setUp()
        val profile = create("Пустой", """{"outbounds":[{"type":"direct","tag":"direct"}]}""")

        catalog.openPicker(profile.id)
        awaitCondition { state.value.message != null }

        assertEquals("В профиле нет группы серверов sing-box (selector).", state.value.message)
        assertNull(state.value.serverPicker)
    }

    @Test
    fun `offline server choice is written to the profile`() = runBlocking {
        setUp()
        val profile = create("Один")
        catalog.openPicker(profile.id)
        awaitCondition { state.value.serverPicker != null }
        idle()
        assertFalse(checkNotNull(state.value.serverPicker).liveSwitch)

        catalog.selectServer("zapret-proxy", "b")
        awaitCondition { state.value.message == "Сервер b выбран. VPN не запускался." }

        assertEquals("b", ConfigAnalyzer.selectorGroups(store.read(profile.id).json).single().default)
        assertEquals("b", state.value.serverPicker?.groups?.single()?.selected)
        assertEquals("b", state.value.serverSummaries.getValue(profile.id).selectedLabel)
        assertTrue(vpn.selected.isEmpty())
    }

    @Test
    fun `live server choice goes through the vpn without touching the file`() = runBlocking {
        setUp()
        val profile = create("Один")
        vpn.connected = profile.id
        catalog.openPicker(profile.id)
        awaitCondition { state.value.serverPicker != null }
        idle()
        assertTrue(checkNotNull(state.value.serverPicker).liveSwitch)

        catalog.selectServer("zapret-proxy", "b")
        awaitCondition { state.value.message == "Сервер b переключается без перезапуска VPN." }

        assertEquals(listOf(Triple(profile.id, "zapret-proxy", "b")), vpn.selected)
        assertEquals("a", ConfigAnalyzer.selectorGroups(store.read(profile.id).json).single().default)
    }

    @Test
    fun `hosts and fingerprints are derived from summaries`() {
        val summary = ProfileServerSummary(
            listOf(
                ProfileServerGroup(
                    "g", "a",
                    listOf(
                        ProfileServerOption("a", "vless", "a.example:443"),
                        ProfileServerOption("b", "vless", null),
                    ),
                ),
            ),
        )

        assertEquals(setOf("a.example"), ServerCatalogController.hostKeys(summary))
        assertTrue(ServerCatalogController.hostKeys(null).isEmpty())
        assertEquals(setOf("a", "b"), ServerCatalogController.fingerprintsOf(summary).keys)
    }

    @Test
    fun `country lookup resolves only selected names and every ip literal is offline`() {
        val summary = ProfileServerSummary(
            listOf(
                ProfileServerGroup(
                    "g", "b",
                    listOf(
                        ProfileServerOption("a", "vless", "a.example:443"),
                        ProfileServerOption("b", "vless", "b.example:443"),
                        ProfileServerOption("c", "vless", "203.0.113.7:443"),
                    ),
                ),
                ProfileServerGroup("h", null, listOf(ProfileServerOption("d", "trojan", "d.example:443"))),
            ),
        )

        assertEquals(setOf("b.example", "d.example"), ServerCatalogController.selectedHostKeys(summary))
        assertEquals(
            setOf("203.0.113.7"),
            ServerCatalogController.hostKeys(summary).filter(ServerCatalogController::isIpLiteral).toSet(),
        )
    }

    private companion object {
        const val SELECTOR_JSON = """{"outbounds":[
            {"type":"vless","tag":"a","server":"a.example","server_port":443},
            {"type":"vless","tag":"b","server":"b.example","server_port":443},
            {"type":"selector","tag":"zapret-proxy","outbounds":["a","b"],"default":"a"}]}"""
    }
}
