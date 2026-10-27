package io.github.zapretkvn.android.profiles

import android.net.Uri
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.importer.SubscriptionFetcher
import io.github.zapretkvn.android.importer.SubscriptionSource
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import io.github.zapretkvn.android.routing.InstalledRuleSets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val state = MutableStateFlow(ProfilesUiState())
    private val vpn = FakeVpnPort()
    private val settings = FakeSettingsPort()
    private var feed = ""
    private lateinit var store: ProfileStore
    private lateinit var sources: SubscriptionSourceStore
    private lateinit var controller: ImportController
    private lateinit var service: ImportService

    private val one = vlessLink("one.example", "One", '1')
    private val two = vlessLink("two.example", "Two", '2')

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun setUp() = runBlocking {
        store = ProfileStore(temporaryFolder.newFolder("profiles"), JsonSyntaxValidator, JvmAtomicProfileWriter())
        store.initialize()
        sources = SubscriptionSourceStore(temporaryFolder.newFolder("subs"), JvmAtomicProfileWriter())
        val installed = InstalledRuleSets(
            1,
            mapOf("zapret-ru-domains" to "/rs/domains.srs", "zapret-ru-ip" to "/rs/ip.srs"),
        )
        val install: suspend () -> InstalledRuleSets = { installed }
        val operations = ProfilesOperations(state, scope)
        service = ImportService(
            store = store,
            validator = JsonSyntaxValidator,
            input = object : ImportInputPort {
                override suspend fun readDocument(uri: Uri): DocumentContent = error("не используется")
                override fun readClipboard(): String = feed
                override suspend fun readQrImage(uri: Uri): String = error("не используется")
            },
            subscriptionFetcher = SubscriptionFetcher { feed },
            subscriptionSourceStore = sources,
            settings = settings,
            vpn = vpn,
            installRuleSets = install,
        )
        controller = ImportController(
            state = state,
            operations = operations,
            service = service,
            committer = ImportCommitter(store, sources, settings, install),
            splitCommitter = SplitRefreshCommitter(store, sources, {}, ::newProfileId),
            store = store,
            vpn = vpn,
            settings = settings,
        )
    }

    private suspend fun idle() {
        awaitCondition { !state.value.busy }
    }

    @Test
    fun `server link in a qr code opens a preview with default routing`() = runBlocking {
        setUp()

        controller.importQr(one)
        awaitCondition { state.value.importPreview != null }
        idle()

        val preview = checkNotNull(state.value.importPreview)
        assertEquals(1, preview.serverCount)
        assertTrue(preview.isSingleManaged)
        assertFalse(preview.isRefresh)
        assertEquals("QR-код", preview.sourceDescription)
        assertTrue(preview.preparedJson.contains("zapret-ru-domains"))
        assertNull(state.value.message)
    }

    @Test
    fun `subscription link in a qr code asks for identity instead of previewing`() = runBlocking {
        setUp()

        controller.importQr("  https://sub.example/list?token=abc  ")
        awaitCondition { state.value.subscriptionLinkPrompt != null }

        assertEquals("https://sub.example/list?token=abc", state.value.subscriptionLinkPrompt?.url)
        assertEquals(SubscriptionLinkOrigin.Qr, state.value.subscriptionLinkPrompt?.origin)
        assertNull(state.value.importPreview)
        controller.closeSubscriptionLinkPrompt()
        assertNull(state.value.subscriptionLinkPrompt)
    }

    @Test
    fun `core rejection of the prepared json is reported and no preview is shown`() = runBlocking {
        setUp()
        feed = "{broken"

        controller.importClipboard()
        awaitCondition { state.value.message != null }
        idle()

        assertNull(state.value.importPreview)
        assertFalse(state.value.busy)
    }

    @Test
    fun `confirming creates the profile makes it active on first import and closes the preview`() = runBlocking {
        setUp()
        controller.importQr(one)
        awaitCondition { state.value.importPreview != null }
        idle()

        controller.confirmImport("Мой профиль")
        awaitCondition { state.value.importCompletion != null }
        idle()

        val completion = checkNotNull(state.value.importCompletion)
        assertEquals("Мой профиль", completion.profileName)
        assertNull(state.value.importPreview)
        assertEquals(completion.profileId, settings.settings.value.activeProfileId)
        assertEquals(listOf(DnsMode.Automatic), settings.dnsModes)
        assertEquals("Профиль сохранён. Подключение не запускалось.", state.value.message)
        assertEquals(1, store.profiles.value.size)
        controller.consumeCompletion()
        assertNull(state.value.importCompletion)
    }

    @Test
    fun `second import keeps the active profile`() = runBlocking {
        setUp()
        state.value = state.value.copy(settings = state.value.settings.copy(activeProfileId = "existing"))
        controller.importQr(one)
        awaitCondition { state.value.importPreview != null }
        controller.confirmImport("Второй")
        awaitCondition { state.value.importCompletion != null }

        assertNull(settings.settings.value.activeProfileId)
        assertTrue(settings.dnsModes.isEmpty())
    }

    @Test
    fun `confirm without preview fails with a clear message`() = runBlocking {
        setUp()

        controller.confirmImport("x")
        awaitCondition { state.value.message != null }

        assertEquals("Предпросмотр импорта уже закрыт.", state.value.message)
    }

    @Test
    fun `subscription is split into one profile per server`() = runBlocking {
        setUp()
        feed = "$one\n$two"
        controller.importUrl("https://sub.example/list")
        awaitCondition { state.value.importPreview != null }
        idle()
        val preview = checkNotNull(state.value.importPreview)
        assertTrue(preview.splitSupported)
        assertTrue(preview.hasSubscriptionUrl)

        controller.confirmImportPerServer("Группа")
        awaitCondition { state.value.importCompletion != null }
        idle()

        assertEquals(2, store.profiles.value.size)
        assertEquals("Создано профилей: 2. Подключение не запускалось.", state.value.message)
        assertEquals(2, state.value.refreshableProfileIds.size)
        val bindings = store.profiles.value.map { checkNotNull(sources.binding(it.id)) }
        assertEquals(1, bindings.map { it.splitGroupId }.distinct().size)
        assertTrue(bindings.all { it.isSplit })
    }

    @Test
    fun `single server cannot be split`() = runBlocking {
        setUp()
        controller.importQr(one)
        awaitCondition { state.value.importPreview != null }
        idle()

        controller.confirmImportPerServer("x")
        awaitCondition { state.value.message != null }

        assertEquals("Разделять можно только подписку с несколькими серверами.", state.value.message)
        assertTrue(store.profiles.value.isEmpty())
    }

    @Test
    fun `single server is appended to a managed profile`() = runBlocking {
        setUp()
        feed = one
        val base = store.create("База", ManagedProfileFactory.single(managedServers(one).single()), ProfileSource.Link)
        state.value = state.value.copy(profiles = store.profiles.value)
        controller.importQr(two)
        awaitCondition { state.value.importPreview != null }
        idle()
        assertEquals(listOf(base.id), state.value.importPreview?.appendTargets?.map { it.id })

        controller.confirmAppend(base.id)
        awaitCondition { state.value.importPreview == null }
        idle()

        assertEquals("Сервер добавлен в База. Работающий VPN не изменён.", state.value.message)
        assertEquals(2, ConfigAnalyzer.selectorGroups(store.read(base.id).json).single().outbounds.size)
    }

    @Test
    fun `manual refresh previews and applies new servers without restarting an idle vpn`() = runBlocking {
        setUp()
        val profile = store.create("Подписка", ManagedProfileFactory.single(managedServers(one).single()), ProfileSource.Url)
        sources.put(profile.id, SubscriptionSource("https://sub.example/list"))
        state.value = state.value.copy(profiles = store.profiles.value)
        feed = "$one\n$two"

        controller.refreshSubscription(profile.id)
        awaitCondition { state.value.importPreview != null }
        idle()
        val preview = checkNotNull(state.value.importPreview)
        assertTrue(preview.isRefresh)
        assertEquals(profile.id, preview.refreshProfileId)
        assertEquals(2, preview.serverCount)

        controller.confirmRefresh(restartConnected = false)
        awaitCondition { state.value.importPreview == null }
        idle()

        assertEquals("Подписка обновлена. Работающий VPN не изменён.", state.value.message)
        assertEquals(2, ConfigAnalyzer.selectorGroups(store.read(profile.id).json).single().outbounds.size)
        assertTrue(vpn.restarts.isEmpty())
    }

    @Test
    fun `refresh does not bring back the server chosen before the selection changed`() = runBlocking {
        setUp()
        val profile = store.create("Подписка", ManagedProfileFactory.subscription(managedServers(one, two)), ProfileSource.Url)
        sources.put(profile.id, SubscriptionSource("https://sub.example/list"))
        state.value = state.value.copy(profiles = store.profiles.value)
        feed = "$one\n$two"

        controller.refreshSubscription(profile.id)
        awaitCondition { state.value.importPreview != null }
        idle()
        // Пока открыт предпросмотр, пользователь выбрал другой сервер.
        val stored = store.read(profile.id).json
        val group = ConfigAnalyzer.selectorGroups(stored).single()
        val other = group.outbounds.first { it != group.default }
        store.update(profile.id, ConfigAnalyzer.selectServer(stored, group.tag, other))

        controller.confirmRefresh(restartConnected = false)
        awaitCondition { state.value.importPreview == null }
        idle()

        assertEquals(other, ConfigAnalyzer.selectorGroups(store.read(profile.id).json).single().default)
    }

    @Test
    fun `subscription link in the clipboard opens the url form instead of a format error`() = runBlocking {
        setUp()
        feed = "https://sub.example/list?token=abc\n"

        controller.importClipboard()
        awaitCondition { state.value.subscriptionLinkPrompt != null }
        idle()

        assertEquals("https://sub.example/list?token=abc", state.value.subscriptionLinkPrompt?.url)
        assertEquals(SubscriptionLinkOrigin.Clipboard, state.value.subscriptionLinkPrompt?.origin)
        assertNull(state.value.importPreview)
        assertNull(state.value.message)
    }

    @Test
    fun `server link in the clipboard still opens a preview`() = runBlocking {
        setUp()
        feed = one

        controller.importClipboard()
        awaitCondition { state.value.importPreview != null }

        assertNull(state.value.subscriptionLinkPrompt)
    }

    @Test
    fun `background refresh applies new servers without restarting the vpn`() = runBlocking {
        setUp()
        val profile = store.create("Подписка", ManagedProfileFactory.single(managedServers(one).single()), ProfileSource.Url)
        sources.put(profile.id, SubscriptionSource("https://sub.example/list"))
        vpn.connected = profile.id
        feed = "$one\n$two"

        val report = SubscriptionAutoRefresher(service, store, sources).refreshAll()

        assertEquals(SubscriptionAutoRefreshReport(updated = 1, skipped = 0), report)
        assertEquals(2, ConfigAnalyzer.selectorGroups(store.read(profile.id).json).single().outbounds.size)
        assertTrue(vpn.restarts.isEmpty())
    }

    @Test
    fun `background refresh waits for the chosen interval and does nothing when manual`() = runBlocking {
        setUp()
        val profile = store.create("Подписка", ManagedProfileFactory.single(managedServers(one).single()), ProfileSource.Url)
        sources.put(profile.id, SubscriptionSource("https://sub.example/list"))
        feed = "$one\n$two"
        val created = store.profiles.value.single { it.id == profile.id }.updatedAtEpochMillis
        val hour = 60L * 60 * 1000
        var clock = created + 5 * hour

        val refresher = SubscriptionAutoRefresher(service, store, sources, now = { clock })
        // Список свежее шести часов: подписку не трогаем.
        assertEquals(SubscriptionAutoRefreshReport(0, 0), refresher.refreshDue(6))
        assertEquals(1, ConfigAnalyzer.selectorGroups(store.read(profile.id).json).single().outbounds.size)
        // «Только вручную»: не трогаем никогда.
        clock = created + 100 * hour
        assertEquals(SubscriptionAutoRefreshReport(0, 0), refresher.refreshDue(0))
        // Срок вышел: обновляем.
        assertEquals(SubscriptionAutoRefreshReport(1, 0), refresher.refreshDue(6))
        assertEquals(2, ConfigAnalyzer.selectorGroups(store.read(profile.id).json).single().outbounds.size)
    }

    @Test
    fun `background refresh keeps the profile when the subscription answers garbage`() = runBlocking {
        setUp()
        val profile = store.create("Подписка", ManagedProfileFactory.single(managedServers(one).single()), ProfileSource.Url)
        sources.put(profile.id, SubscriptionSource("https://sub.example/list"))
        val before = store.read(profile.id).json
        feed = "<html>temporarily unavailable</html>"

        val report = SubscriptionAutoRefresher(service, store, sources).refreshAll()

        assertEquals(SubscriptionAutoRefreshReport(updated = 0, skipped = 1), report)
        assertEquals(before, store.read(profile.id).json)
    }

    @Test
    fun `refresh of a connected profile restarts the vpn only with consent`() = runBlocking {
        setUp()
        val profile = store.create("Подписка", ManagedProfileFactory.single(managedServers(one).single()), ProfileSource.Url)
        sources.put(profile.id, SubscriptionSource("https://sub.example/list"))
        vpn.connected = profile.id
        feed = "$one\n$two"
        controller.refreshSubscription(profile.id)
        awaitCondition { state.value.importPreview != null }
        assertTrue(checkNotNull(state.value.importPreview).activeRefresh)

        controller.confirmRefresh(restartConnected = true)
        awaitCondition { vpn.restarts.isNotEmpty() }

        assertEquals(listOf("Подписка обновлена пользователем"), vpn.restarts)
    }

    @Test
    fun `refresh without a stored url fails`() = runBlocking {
        setUp()

        controller.refreshSubscription("missing")
        awaitCondition { state.value.message != null }

        assertEquals("Для этого профиля не сохранён URL ручного обновления.", state.value.message)
        assertNotNull(state.value)
    }
}
