package io.github.zapretkvn.android.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeyapps.barcodescanner.ScanContract
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.LibboxConfigValidator
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.profiles.ProfileStore
import io.github.zapretkvn.android.profiles.ProfilesUiState
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.android.profiles.ProtocolOutboundBuilders
import io.github.zapretkvn.android.profiles.SubscriptionIdentityInput
import io.github.zapretkvn.android.profiles.TlsSettings
import io.github.zapretkvn.android.profiles.TransportSettings
import io.github.zapretkvn.android.routing.RoutingConfigEditor
import io.github.zapretkvn.android.routing.RoutingPreset
import io.github.zapretkvn.android.vpn.BootstrapCache
import io.github.zapretkvn.android.vpn.VpnController
import io.nekohasekai.libbox.Libbox
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun filePickerUsesSystemOpenDocumentContract() {
        val intent = ActivityResultContracts.OpenDocument().createIntent(
            context,
            arrayOf("application/json", "text/plain"),
        )

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.contains("application/json") == true)
    }

    @Test
    fun selectedDocumentIsReadThroughContentResolver() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val resourceId = testContext.resources.getIdentifier(
            "import_profile",
            "raw",
            testContext.packageName,
        )
        val uri = Uri.parse("android.resource://${testContext.packageName}/$resourceId")

        val raw = AndroidImportReader(testContext).readDocument(uri)

        assertTrue(ImportParser.parse(raw, ProfileSource.File) is ImportCandidate.RawJson)
    }

    @Test
    fun wireguardConfOpenedWithViewIntentReachesValidatedPreview() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val resourceId = testContext.resources.getIdentifier(
            "wireguard_profile",
            "raw",
            testContext.packageName,
        )
        val uri = Uri.parse("android.resource://${testContext.packageName}/$resourceId")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(context, MainActivity::class.java)
            setDataAndType(uri, "application/x-wireguard-profile")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        var viewModel: ProfilesViewModel? = null

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[ProfilesViewModel::class.java]
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (viewModel?.state?.value?.importPreview == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(25)
            }
            val preview = checkNotNull(viewModel?.state?.value?.importPreview)
            assertEquals(1, preview.serverCount)
            assertTrue(preview.candidate is ImportCandidate.WireGuard)
        }
    }

    @Test
    fun wireguardConfSharedAsTelegramStyleStreamReachesValidatedPreview() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val resourceId = testContext.resources.getIdentifier(
            "wireguard_profile",
            "raw",
            testContext.packageName,
        )
        val uri = Uri.parse("android.resource://${testContext.packageName}/$resourceId")
        val intent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        var viewModel: ProfilesViewModel? = null

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[ProfilesViewModel::class.java]
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (viewModel?.state?.value?.importPreview == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(25)
            }
            val preview = checkNotNull(viewModel?.state?.value?.importPreview)
            assertEquals(1, preview.serverCount)
            assertTrue(preview.candidate is ImportCandidate.WireGuard)
        }
    }

    @Test
    fun clipboardIsReadOnlyAfterExplicitReaderCall() {
        var imported = ""
        val canary = "clipboard-r8-${UUID.randomUUID()}"
        val clipboardValue = """{"x-clipboard-canary":"$canary"}"""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val focusDeadline = SystemClock.uptimeMillis() + 10_000
            var hasWindowFocus = false
            while (!hasWindowFocus && SystemClock.uptimeMillis() < focusDeadline) {
                scenario.onActivity { activity ->
                    hasWindowFocus = activity.hasWindowFocus()
                }
                if (!hasWindowFocus) SystemClock.sleep(50)
            }
            assertTrue("MainActivity never received window focus", hasWindowFocus)
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("profile", clipboardValue))
                imported = AndroidImportReader(activity).readClipboardAfterUserAction()
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }

        assertEquals(clipboardValue, imported)
        assertTrue(ImportParser.parse(imported, ProfileSource.Clipboard) is ImportCandidate.RawJson)
        val privateRoots = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir)
        val persisted = privateRoots.asSequence()
            .flatMap { root -> root.walkTopDown().asSequence() }
            .filter { file -> file.isFile && file.length() <= 2L * 1024L * 1024L }
            .any { file -> runCatching { canary in file.readText() }.getOrDefault(false) }
        assertTrue("Clipboard reader must not create app-private history", !persisted)
    }

    @Test
    fun clipboardReaderCombinesEveryTextItem() {
        val clip = ClipData.newPlainText(
            "profiles",
            "vless://11111111-1111-4111-8111-111111111111@one.example:443#One",
        ).apply {
            addItem(ClipData.Item("trojan://secret@two.example:443#Two"))
        }
        var imported = ""

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val focusDeadline = SystemClock.uptimeMillis() + 10_000
            var hasWindowFocus = false
            while (!hasWindowFocus && SystemClock.uptimeMillis() < focusDeadline) {
                scenario.onActivity { activity ->
                    hasWindowFocus = activity.hasWindowFocus()
                }
                if (!hasWindowFocus) SystemClock.sleep(50)
            }
            assertTrue("MainActivity never received window focus", hasWindowFocus)
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(clip)
                imported = AndroidImportReader(activity).readClipboardAfterUserAction()
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
        val candidate = ImportParser.parse(imported, ProfileSource.Clipboard) as ImportCandidate.Managed

        assertEquals(2, candidate.servers.size)
    }

    @Test
    fun managedSingleAndSubscriptionPassLibboxCheckConfig() {
        val one = vless("One", "one.example")
        val two = vless("Two", "two.example")

        Libbox.checkConfig(ManagedProfileFactory.single(one))
        Libbox.checkConfig(ManagedProfileFactory.subscription(listOf(one, two)))
    }

    @Test
    fun newManagedProfilePreviewDefaultsToRussiaDirect() = runBlocking {
        val root = File(context.cacheDir, "default-routing-${System.nanoTime()}")
        val testViewModelStore = ViewModelStore()
        try {
            val application = context.applicationContext as ZapretApplication
            val profileStore = ProfileStore(File(root, "profiles"), LibboxConfigValidator())
            profileStore.initialize()
            val viewModel = ViewModelProvider(
                object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore = testViewModelStore
                },
                ProfilesViewModel.Factory(
                    store = profileStore,
                    settingsStore = application.container.uiSettingsStore,
                    validator = LibboxConfigValidator(),
                    importReader = AndroidImportReader(context),
                    subscriptionFetcher = SubscriptionFetcher { error("Unexpected fetch") },
                    subscriptionSourceStore = SubscriptionSourceStore(File(root, "subscriptions")),
                    vpnController = VpnController(context),
                    bootstrapCache = BootstrapCache(File(root, "network")),
                    ruleSetAssets = application.container.ruleSetAssetManager,
                ),
            )[ProfilesViewModel::class.java]

            viewModel.importQr(
                "vless://11111111-1111-4111-8111-111111111111@one.example:443?security=tls#One",
            )
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (
                viewModel.state.value.importPreview == null &&
                viewModel.state.value.message == null &&
                SystemClock.uptimeMillis() < deadline
            ) {
                SystemClock.sleep(25)
            }

            val json = checkNotNull(viewModel.state.value.importPreview).preparedJson
            assertEquals(RoutingPreset.RussiaDirect, RoutingConfigEditor.inspect(json).preset)
            assertTrue(json.contains("zapret-ru-domains"))
            assertTrue(json.contains("zapret-ru-ip"))
            Libbox.checkConfig(json)
        } finally {
            testViewModelStore.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun urlImportDropsFragmentAndPreviewsSupportedServersWithSchemeWarning() = runBlocking {
        val root = File(context.cacheDir, "partial-subscription-${System.nanoTime()}")
        val testViewModelStore = ViewModelStore()
        var fetchedUrl: String? = null
        try {
            val application = context.applicationContext as ZapretApplication
            val profileStore = ProfileStore(File(root, "profiles"), LibboxConfigValidator())
            profileStore.initialize()
            val viewModel = ViewModelProvider(
                object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore = testViewModelStore
                },
                ProfilesViewModel.Factory(
                    store = profileStore,
                    settingsStore = application.container.uiSettingsStore,
                    validator = LibboxConfigValidator(),
                    importReader = AndroidImportReader(context),
                    subscriptionFetcher = SubscriptionFetcher { source ->
                        fetchedUrl = source.url
                        """
                            vless://11111111-1111-4111-8111-111111111111@one.example:443?security=tls#One
                            ssh://user:password@ssh.example:22
                        """.trimIndent()
                    },
                    subscriptionSourceStore = SubscriptionSourceStore(File(root, "subscriptions")),
                    vpnController = VpnController(context),
                    bootstrapCache = BootstrapCache(File(root, "network")),
                    ruleSetAssets = application.container.ruleSetAssetManager,
                ),
            )[ProfilesViewModel::class.java]

            viewModel.importUrl("https://subscription.example/profile?token=secret#client-label")
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (
                viewModel.state.value.importPreview == null &&
                viewModel.state.value.message == null &&
                SystemClock.uptimeMillis() < deadline
            ) {
                SystemClock.sleep(25)
            }

            val preview = checkNotNull(viewModel.state.value.importPreview)
            assertEquals("https://subscription.example/profile?token=secret", fetchedUrl)
            assertEquals(1, preview.serverCount)
            assertEquals(
                listOf("Пропущены неподдерживаемые схемы: ${SecretRedactor.MASK}"),
                preview.importWarnings,
            )
            Libbox.checkConfig(preview.preparedJson)
        } finally {
            testViewModelStore.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun qrSubscriptionOpensIdentityFormBeforeAnyNetworkRequest() = runBlocking {
        val root = File(context.cacheDir, "qr-subscription-${System.nanoTime()}")
        val testViewModelStore = ViewModelStore()
        var fetchCount = 0
        try {
            val application = context.applicationContext as ZapretApplication
            val profileStore = ProfileStore(File(root, "profiles"), LibboxConfigValidator())
            profileStore.initialize()
            val viewModel = ViewModelProvider(
                object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore = testViewModelStore
                },
                ProfilesViewModel.Factory(
                    store = profileStore,
                    settingsStore = application.container.uiSettingsStore,
                    validator = LibboxConfigValidator(),
                    importReader = AndroidImportReader(context),
                    subscriptionFetcher = SubscriptionFetcher {
                        fetchCount++
                        "vless://11111111-1111-4111-8111-111111111111@one.example:443?security=tls#One"
                    },
                    subscriptionSourceStore = SubscriptionSourceStore(File(root, "subscriptions")),
                    vpnController = VpnController(context),
                    bootstrapCache = BootstrapCache(File(root, "network")),
                    ruleSetAssets = application.container.ruleSetAssetManager,
                ),
            )[ProfilesViewModel::class.java]

            val source = "https://subscription.example/profile?token=synthetic"
            viewModel.importQr(source)
            val formDeadline = SystemClock.uptimeMillis() + 10_000
            while (viewModel.state.value.qrSubscriptionUrl == null && SystemClock.uptimeMillis() < formDeadline) {
                SystemClock.sleep(25)
            }
            assertEquals(source, viewModel.state.value.qrSubscriptionUrl)
            assertEquals(0, fetchCount)
            assertEquals(null, viewModel.state.value.importPreview)

            viewModel.importQrSubscription(source, SubscriptionIdentityInput())
            val previewDeadline = SystemClock.uptimeMillis() + 10_000
            while (viewModel.state.value.importPreview == null && SystemClock.uptimeMillis() < previewDeadline) {
                SystemClock.sleep(25)
            }
            assertEquals(1, fetchCount)
            assertEquals(1, checkNotNull(viewModel.state.value.importPreview).serverCount)
        } finally {
            testViewModelStore.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun commonProtocolBuildersPassLibboxCheckConfig() {
        val servers = listOf(
            ProtocolOutboundBuilders.vless(
                displayName = "VLESS WS",
                server = "vless.example",
                serverPort = 443,
                uuid = "11111111-1111-4111-8111-111111111111",
                tls = TlsSettings(enabled = true, serverName = "vless.example"),
                transport = TransportSettings(type = "ws", path = "/vpn", host = "vless.example"),
            ),
            ProtocolOutboundBuilders.vmess(
                displayName = "VMess",
                server = "vmess.example",
                serverPort = 443,
                uuid = "22222222-2222-4222-8222-222222222222",
                tls = TlsSettings(enabled = true, serverName = "vmess.example"),
            ),
            ProtocolOutboundBuilders.trojan(
                displayName = "Trojan",
                server = "trojan.example",
                serverPort = 443,
                password = "test-password",
            ),
            ProtocolOutboundBuilders.shadowsocks(
                displayName = "Shadowsocks",
                server = "ss.example",
                serverPort = 8388,
                method = "aes-128-gcm",
                password = "test-password",
            ),
            ProtocolOutboundBuilders.hysteria2(
                displayName = "Hysteria2",
                server = "hy.example",
                serverPort = 443,
                password = "test-password",
                obfsType = "gecko",
                obfsPassword = "synthetic-cover",
                serverPorts = listOf("443", "20000-20002"),
                hopInterval = "15s",
                certificateSha256 = "00".repeat(32),
            ),
            ProtocolOutboundBuilders.tuic(
                displayName = "TUIC",
                server = "tuic.example",
                serverPort = 443,
                uuid = "33333333-3333-4333-8333-333333333333",
                password = "test-password",
                congestionControl = "bbr",
            ),
        )

        Libbox.checkConfig(ManagedProfileFactory.subscription(servers))
    }

    @Test
    fun allShareLinkProtocolsParseToConfigAcceptedByPinnedLibbox() {
        val links = listOf(
            "vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls#VLESS",
            "trojan://secret@trojan.example:443?sni=trojan.example#Trojan",
            "hy2://secret@hy.example:443,20000-20002?sni=hy.example" +
                "&obfs=gecko&obfs-password=synthetic&pinSHA256=${"00".repeat(32)}#HY2",
            "tuic://33333333-3333-4333-8333-333333333333:secret@tuic.example:443?sni=tuic.example#TUIC",
        ).joinToString("\n")
        val candidate = ImportParser.parse(links, ProfileSource.Clipboard) as ImportCandidate.Managed

        Libbox.checkConfig(candidate.buildJson())
    }

    @Test
    fun xrayUdp443FlowAliasPassesPinnedLibboxCheck() {
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@vision.example:443" +
                "?security=reality&flow=xtls-rprx-vision-udp443" +
                "&sni=cdn.example&fp=chrome" +
                "&pbk=nDCKIlAlRIBhaDNs04SMghv0qbjQhfQrXyocJriGRg4&sid=abcd#Vision",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed

        Libbox.checkConfig(candidate.buildJson())
    }

    @Test
    fun completeXhttpObjectPassesPinnedLibboxCheck() {
        val completeXhttp = Uri.encode(
            """
                {
                  "mode":"packet-up",
                  "host":"download.example",
                  "path":"/complete",
                  "extra":{
                    "headers":{"User-Agent":"Zapret/XHTTP"},
                    "domainStrategy":"prefer_ipv4",
                    "sessionIDTable":"0123456789abcdef",
                    "sessionIDLength":"12-16",
                    "congestionController":"bbr2",
                    "cwnd":64,
                    "xmux":{
                      "maxConcurrency":"8-16",
                      "cMaxReuseTimes":"32-64",
                      "hMaxReusableSecs":"1800-3000"
                    }
                  }
                }
            """.trimIndent(),
        )
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@xhttp.example:443" +
                "?security=tls&type=xhttp&extra=$completeXhttp#Complete+XHTTP",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed

        Libbox.checkConfig(candidate.buildJson())
    }

    @Test
    fun wireguardAndAmneziaWg2ConfigsPassPinnedLibboxCheck() {
        val wireGuard = ImportParser.parse(WIREGUARD_CONF, ProfileSource.File) as ImportCandidate.WireGuard
        val amnezia = ImportParser.parse(AWG2_CONF, ProfileSource.File) as ImportCandidate.WireGuard

        Libbox.checkConfig(wireGuard.json)
        Libbox.checkConfig(amnezia.json)
    }

    @Test
    fun qrContractIsExplicitAndCameraPermissionIsDeclared() {
        val intent = ScanContract().createIntent(
            context,
            qrImportScanOptions(),
        )
        val permissions = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty()

        assertEquals(QrCaptureActivity::class.java.name, intent.component?.className)
        assertTrue(Manifest.permission.CAMERA in permissions)
    }

    @Test
    fun galleryQrDecoderRequiresAndReturnsOneQrPayload() {
        val value = "hysteria2://synthetic@192.0.2.10:443?obfs=gecko&obfs-password=cover#Gallery"
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        for (y in 0 until 512) {
            for (x in 0 until 512) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        val file = File(context.cacheDir, "diagnostics/qr-${System.nanoTime()}.png")
        file.parentFile?.mkdirs()
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            assertEquals(value, AndroidImportReader(context).readSingleQrImage(uri))
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun profilePersistsAcrossStoreRecreation() = runBlocking {
        val root = File(context.filesDir, "profiles-instrumented-${System.nanoTime()}")
        try {
            val validator = LibboxConfigValidator()
            val first = ProfileStore(root, validator)
            first.initialize()
            val metadata = first.create("Persisted", VALID_DIRECT, ProfileSource.File)

            val reopened = ProfileStore(root, validator)
            reopened.initialize()
            assertEquals(VALID_DIRECT, reopened.read(metadata.id).json)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun subscriptionIdentitySettingsSurviveSaveAndReload() = runBlocking {
        val root = File(context.cacheDir, "identity-instrumented-${System.nanoTime()}")
        val testViewModelStore = ViewModelStore()
        try {
            val profileStore = ProfileStore(File(root, "profiles"), LibboxConfigValidator())
            profileStore.initialize()
            val metadata = profileStore.create("Subscribed", VALID_DIRECT, ProfileSource.Subscription)
            val sourceStore = SubscriptionSourceStore(File(root, "subscriptions"))
            sourceStore.put(
                metadata.id,
                SubscriptionSource("https://subscription.example/profile?token=secret"),
            )
            val application = context.applicationContext as ZapretApplication
            val viewModel = ViewModelProvider(
                object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore = testViewModelStore
                },
                ProfilesViewModel.Factory(
                    store = profileStore,
                    settingsStore = application.container.uiSettingsStore,
                    validator = LibboxConfigValidator(),
                    importReader = AndroidImportReader(context),
                    subscriptionFetcher = SubscriptionFetcher { error("Unexpected fetch") },
                    subscriptionSourceStore = sourceStore,
                    vpnController = VpnController(context),
                    bootstrapCache = BootstrapCache(File(root, "network")),
                    ruleSetAssets = application.container.ruleSetAssetManager,
                ),
            )[ProfilesViewModel::class.java]

            fun awaitState(predicate: (ProfilesUiState) -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (!predicate(viewModel.state.value) && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(25)
                }
            }

            viewModel.openSubscriptionSettings(metadata.id)
            awaitState { it.subscriptionSettings != null || it.message != null }
            val opened = checkNotNull(viewModel.state.value.subscriptionSettings)
            assertEquals("https://subscription.example/profile?token=secret", opened.url)
            assertTrue(opened.installationHwid.isNotBlank())

            // Пустой HWID означает постоянный идентификатор установки, а не отключение заголовка.
            viewModel.saveSubscriptionSettings(
                SubscriptionIdentityInput(SubscriptionClientProfile.Happ, sendHwid = true, hwid = ""),
            )
            awaitState { it.subscriptionSettings == null }

            val stored = checkNotNull(sourceStore.get(metadata.id))
            assertEquals(SubscriptionClientProfile.Happ, stored.clientProfile)
            assertTrue(stored.sendHwid)
            assertEquals(opened.installationHwid, stored.hwid)
            assertEquals("https://subscription.example/profile?token=secret", stored.url)
        } finally {
            testViewModelStore.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedManualRefreshNeverChangesStoredProfile() = runBlocking {
        val root = File(context.cacheDir, "refresh-instrumented-${System.nanoTime()}")
        val testViewModelStore = ViewModelStore()
        try {
            val profileStore = ProfileStore(File(root, "profiles"), LibboxConfigValidator())
            profileStore.initialize()
            val metadata = profileStore.create("Existing", VALID_DIRECT, ProfileSource.Subscription)
            val sourceStore = SubscriptionSourceStore(File(root, "subscriptions"))
            sourceStore.put(
                metadata.id,
                SubscriptionSource("https://subscription.example/profile?token=secret"),
            )
            val application = context.applicationContext as ZapretApplication
            val owner = object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = testViewModelStore
            }
            val viewModel = ViewModelProvider(
                owner,
                ProfilesViewModel.Factory(
                    store = profileStore,
                    settingsStore = application.container.uiSettingsStore,
                    validator = LibboxConfigValidator(),
                    importReader = AndroidImportReader(context),
                    subscriptionFetcher = SubscriptionFetcher { "{broken" },
                    subscriptionSourceStore = sourceStore,
                    vpnController = VpnController(context),
                    bootstrapCache = BootstrapCache(File(root, "network")),
                    ruleSetAssets = application.container.ruleSetAssetManager,
                ),
            )[ProfilesViewModel::class.java]

            viewModel.refreshSubscription(metadata.id)
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (viewModel.state.value.message == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(25)
            }

            assertTrue(viewModel.state.value.message != null)
            assertEquals(VALID_DIRECT, profileStore.read(metadata.id).json)
        } finally {
            testViewModelStore.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun splitSubscriptionRefreshRotatesCredentialsAndReconcilesMembers() = runBlocking {
        val root = File(context.cacheDir, "split-refresh-${System.nanoTime()}")
        val testViewModelStore = ViewModelStore()
        try {
            val profileStore = ProfileStore(File(root, "profiles"), LibboxConfigValidator())
            profileStore.initialize()
            val oldOne = vless("One", "one.example")
            val oldTwo = vless("Two", "two.example")
            val first = profileStore.create("One", ManagedProfileFactory.single(oldOne), ProfileSource.Link)
            val second = profileStore.create("Two", ManagedProfileFactory.single(oldTwo), ProfileSource.Link)
            val source = SubscriptionSource("https://subscription.example/profile?token=secret")
            val sourceStore = SubscriptionSourceStore(File(root, "subscriptions"))
            val keys = ManagedProfileFactory.stableMemberKeys(listOf(oldOne, oldTwo))
            val known = keys.toSet()
            sourceStore.replaceSplitGroup(
                "group-1",
                mapOf(
                    first.id to SubscriptionBinding(source, "group-1", keys[0], "Servers", known),
                    second.id to SubscriptionBinding(source, "group-1", keys[1], "Servers", known),
                ),
            )
            val refreshedLinks = listOf(
                "vless://22222222-2222-4222-8222-222222222222@one.example:443?security=tls#One",
                "vless://33333333-3333-4333-8333-333333333333@three.example:443?security=tls#Three",
            ).joinToString("\n")
            val application = context.applicationContext as ZapretApplication
            val viewModel = ViewModelProvider(
                object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore = testViewModelStore
                },
                ProfilesViewModel.Factory(
                    store = profileStore,
                    settingsStore = application.container.uiSettingsStore,
                    validator = LibboxConfigValidator(),
                    importReader = AndroidImportReader(context),
                    subscriptionFetcher = SubscriptionFetcher { refreshedLinks },
                    subscriptionSourceStore = sourceStore,
                    vpnController = VpnController(context),
                    bootstrapCache = BootstrapCache(File(root, "network")),
                    ruleSetAssets = application.container.ruleSetAssetManager,
                ),
            )[ProfilesViewModel::class.java]

            fun awaitState(predicate: (ProfilesUiState) -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (!predicate(viewModel.state.value) && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(25)
                }
            }

            awaitState(ProfilesUiState::initialized)
            viewModel.refreshSubscription(first.id)
            awaitState { it.importPreview?.splitRefreshSummary != null || it.message != null }
            val summary = checkNotNull(viewModel.state.value.importPreview?.splitRefreshSummary)
            assertEquals(1, summary.updated)
            assertEquals(1, summary.added)
            assertEquals(1, summary.removed)

            viewModel.confirmRefresh(restartConnected = false)
            awaitState { !it.busy && it.importPreview == null }

            val profiles = profileStore.profiles.value
            assertEquals(2, profiles.size)
            assertTrue(profiles.any { it.id == first.id })
            assertFalse(profiles.any { it.id == second.id })
            assertTrue(profileStore.read(first.id).json.contains("22222222-2222-4222-8222-222222222222"))
            assertEquals(2, sourceStore.splitGroup(first.id).size)
        } finally {
            testViewModelStore.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun splitHysteriaPublicationRefreshMigratesLegacyIdentityAndPreservesProfileId() = runBlocking {
        val root = File(context.cacheDir, "split-hysteria-refresh-${System.nanoTime()}")
        val testViewModelStore = ViewModelStore()
        val credential = "stable-synthetic-auth"
        val oldPin = "00".repeat(32)
        val freshPin = "11".repeat(32)
        try {
            val profileStore = ProfileStore(File(root, "profiles"), LibboxConfigValidator())
            profileStore.initialize()
            val oldLink = "hy2://$credential@old.example:443?insecure=1&pinSHA256=$oldPin&" +
                "sni=old-tls.example&obfs=gecko&obfs-password=cover#Hysteria"
            val freshLink = "hysteria2://$credential@192.0.2.44:8443?insecure=1&pinSHA256=$freshPin&" +
                "sni=fresh-tls.example&obfs=gecko&obfs-password=cover#Hysteria"
            val oldServer = (ImportParser.parse(oldLink, ProfileSource.Subscription) as ImportCandidate.Managed)
                .servers.single()
            val peer = vless("Peer", "peer.example")
            val first = profileStore.create(
                "Hysteria",
                ManagedProfileFactory.single(oldServer),
                ProfileSource.Link,
            )
            val second = profileStore.create(
                "Peer",
                ManagedProfileFactory.single(peer),
                ProfileSource.Link,
            )
            val source = SubscriptionSource("https://subscription.example/profile?token=secret")
            val sourceStore = SubscriptionSourceStore(File(root, "subscriptions"))
            val peerKey = ManagedProfileFactory.stableMemberKeys(listOf(peer)).single()
            val legacyHysteriaKey = MessageDigest.getInstance("SHA-256")
                .digest(oldServer.identityKey.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val known = setOf(legacyHysteriaKey, peerKey)
            sourceStore.replaceSplitGroup(
                "group-hysteria",
                mapOf(
                    first.id to SubscriptionBinding(
                        source,
                        "group-hysteria",
                        legacyHysteriaKey,
                        "Servers",
                        known,
                    ),
                    second.id to SubscriptionBinding(
                        source,
                        "group-hysteria",
                        peerKey,
                        "Servers",
                        known,
                    ),
                ),
            )
            val refreshedLinks = listOf(
                freshLink,
                "vless://22222222-2222-4222-8222-222222222222@peer.example:443?security=tls#Peer",
            ).joinToString("\n")
            val application = context.applicationContext as ZapretApplication
            val viewModel = ViewModelProvider(
                object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore = testViewModelStore
                },
                ProfilesViewModel.Factory(
                    store = profileStore,
                    settingsStore = application.container.uiSettingsStore,
                    validator = LibboxConfigValidator(),
                    importReader = AndroidImportReader(context),
                    subscriptionFetcher = SubscriptionFetcher { refreshedLinks },
                    subscriptionSourceStore = sourceStore,
                    vpnController = VpnController(context),
                    bootstrapCache = BootstrapCache(File(root, "network")),
                    ruleSetAssets = application.container.ruleSetAssetManager,
                ),
            )[ProfilesViewModel::class.java]

            fun awaitState(predicate: (ProfilesUiState) -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (!predicate(viewModel.state.value) && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(25)
                }
            }

            awaitState(ProfilesUiState::initialized)
            viewModel.refreshSubscription(first.id)
            awaitState { it.importPreview?.splitRefreshSummary != null || it.message != null }
            val summary = checkNotNull(viewModel.state.value.importPreview?.splitRefreshSummary)
            assertEquals(2, summary.updated)
            assertEquals(0, summary.added)
            assertEquals(0, summary.removed)

            viewModel.confirmRefresh(restartConnected = false)
            awaitState { !it.busy && it.importPreview == null }

            assertEquals(setOf(first.id, second.id), profileStore.profiles.value.map { it.id }.toSet())
            val refreshed = profileStore.read(first.id).json
            assertTrue(refreshed.contains(freshPin))
            assertFalse(refreshed.contains(oldPin))
            assertTrue(refreshed.contains("fresh-tls.example"))
            assertTrue(refreshed.contains("\"insecure\": true"))
            assertTrue(refreshed.contains("\"type\": \"gecko\""))
            val expectedKey = ManagedProfileFactory.stableMemberKeys(
                (ImportParser.parse(freshLink, ProfileSource.Subscription) as ImportCandidate.Managed).servers,
            ).single()
            assertEquals(expectedKey, sourceStore.binding(first.id)?.splitMemberKey)
        } finally {
            testViewModelStore.clear()
            root.deleteRecursively()
        }
    }

    private fun vless(name: String, host: String) = ProtocolOutboundBuilders.vless(
        displayName = name,
        server = host,
        serverPort = 443,
        uuid = "11111111-1111-4111-8111-111111111111",
        tls = TlsSettings(enabled = true, serverName = host),
    )

    private companion object {
        const val WIREGUARD_CONF = """
            [Interface]
            PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
            Address = 192.0.2.2/32
            DNS = 192.0.2.53, 198.51.100.53
            [Peer]
            PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
            Endpoint = 192.0.2.1:51820
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
        """

        const val AWG2_CONF = """
            [Interface]
            PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
            Address = 10.8.1.4/32
            Jc = 4
            Jmin = 10
            Jmax = 50
            S1 = 142
            S2 = 41
            S3 = 56
            S4 = 11
            H1 = 684141592-1751861769
            H2 = 1957920865-2010016669
            H3 = 2043550980-2107134838
            H4 = 2127672251-2132651859
            I1 = <r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001>
            I2 =
            I3 =
            I4 =
            I5 =
            [Peer]
            PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 192.0.2.1:51820
            PersistentKeepalive = 25
        """

        const val VALID_DIRECT =
            """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}"""
    }
}
