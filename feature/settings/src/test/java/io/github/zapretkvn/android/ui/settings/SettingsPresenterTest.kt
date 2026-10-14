package io.github.zapretkvn.android.ui.settings

import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.hardening.TunMtuMode
import io.github.zapretkvn.android.hardening.VpnHidingOptions
import io.github.zapretkvn.android.network.NetworkAutomationSettings
import io.github.zapretkvn.android.ui.ThemeMode
import io.github.zapretkvn.android.ui.UiSettings
import io.github.zapretkvn.android.updates.ForgejoAsset
import io.github.zapretkvn.android.updates.ForgejoRelease
import io.github.zapretkvn.android.updates.ReleaseMetadata
import io.github.zapretkvn.android.updates.UpdateCandidate
import io.github.zapretkvn.android.updates.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal fun testCandidate(version: String = "1.5.0", notes: String = "### Что нового\n\n- быстрее") =
    UpdateCandidate(
        release = ForgejoRelease(
            tag = "v$version",
            title = version,
            body = notes,
            pageUrl = "https://example.test/release",
            draft = false,
            prerelease = false,
            assets = emptyList(),
        ),
        metadata = ReleaseMetadata(
            versionName = version,
            versionCode = 150,
            applicationId = "io.github.zapretkvn.android",
            coreTag = "v1.14.1",
            coreCommit = "0".repeat(40),
            abi = listOf("arm64-v8a"),
            apkFile = "update.apk",
            apkSha256 = "0".repeat(64),
            apkSize = 2L * 1024 * 1024,
        ),
        apkAsset = ForgejoAsset("update.apk", "https://example.test/apk", 1, null),
        checksumAsset = ForgejoAsset("update.apk.sha256", "https://example.test/sha", 1, null),
    )

class SettingsPresenterTest {
    @Test
    fun `page ids round trip and unknown id is rejected`() {
        SettingsPage.entries.forEach { assertEquals(it, SettingsPage.fromId(it.id)) }
        assertNull(SettingsPage.fromId("nope"))
        assertEquals(SettingsPage.entries.size, SettingsPage.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `dns summary names the mode and the ip family`() {
        assertEquals("Автоматически · только IPv4", dnsSummary(DnsMode.Automatic, ipv4Only = true))
        assertEquals("DNS Android · IPv4 и IPv6", dnsSummary(DnsMode.Android, ipv4Only = false))
        // В режиме «Из JSON» переключатель IPv4 не действует, в сводке его нет.
        assertEquals("Из JSON", dnsSummary(DnsMode.FromJson, ipv4Only = true))
    }

    @Test
    fun `dns mode labels keep the texts used by tests`() {
        assertEquals(
            listOf("Автоматически", "DNS Android", "DNS узла через VPN", "Из JSON"),
            DnsMode.entries.map { it.title() },
        )
    }

    @Test
    fun `automation summary covers off on and trusted wifi`() {
        assertEquals("Выключена", automationSummary(NetworkAutomationSettings()))
        assertEquals(
            "VPN: Wi‑Fi, мобильная, Ethernet, другие",
            automationSummary(NetworkAutomationSettings(enabled = true)),
        )
        assertEquals(
            "VPN везде на паузе",
            automationSummary(
                NetworkAutomationSettings(
                    enabled = true,
                    useVpnOnWifi = false,
                    useVpnOnCellular = false,
                    useVpnOnEthernet = false,
                    useVpnOnOther = false,
                ),
            ),
        )
        assertEquals(
            "VPN: Wi‑Fi · доверенных Wi‑Fi: 2",
            automationSummary(
                NetworkAutomationSettings(
                    enabled = true,
                    useVpnOnCellular = false,
                    useVpnOnEthernet = false,
                    useVpnOnOther = false,
                    trustedWifiSsids = setOf("Home", "Office"),
                ),
            ),
        )
        // Доверенные сети не упоминаются, когда пауза по ним выключена.
        assertEquals(
            "VPN: Wi‑Fi",
            automationSummary(
                NetworkAutomationSettings(
                    enabled = true,
                    useVpnOnCellular = false,
                    useVpnOnEthernet = false,
                    useVpnOnOther = false,
                    pauseOnTrustedWifi = false,
                    trustedWifiSsids = setOf("Home"),
                ),
            ),
        )
    }

    @Test
    fun `update summary follows the update state`() {
        val candidate = testCandidate()
        assertEquals("Версия 1.4.2", updateSummary(UpdateState.Idle, "1.4.2"))
        assertEquals("Версия 1.4.2 · актуальна", updateSummary(UpdateState.UpToDate("v1.4.2", "1.4.2"), "1.4.2"))
        assertEquals("Доступна версия 1.5.0", updateSummary(UpdateState.Available(candidate), "1.4.2"))
        assertEquals(
            "Загружается версия 1.5.0",
            updateSummary(UpdateState.Downloading(candidate, 10, 100), "1.4.2"),
        )
        assertEquals("Версия 1.5.0 готова к установке", updateSummary(UpdateState.Ready(candidate), "1.4.2"))
        assertEquals(
            "Устанавливается версия 1.5.0",
            updateSummary(UpdateState.Installing(candidate), "1.4.2"),
        )
        assertEquals("Версия 1.4.2 · проверяем…", updateSummary(UpdateState.Checking("Stable"), "1.4.2"))
        assertEquals("Версия 1.4.2 · не удалось проверить", updateSummary(UpdateState.Failure("x"), "1.4.2"))
    }

    @Test
    fun `main list model carries current values as subtitles`() {
        val ui = buildSettingsMain(
            UiSettings(
                themeMode = ThemeMode.Amoled,
                dnsMode = DnsMode.Secure,
                proxyIpv4Only = false,
                hideServerAddresses = false,
                slowServerSwitch = false,
            ),
            updateSummary = "Версия 1.4.2 · актуальна",
            appVersion = "1.4.2",
        )
        assertEquals("Тема: AMOLED (чёрная)", ui.themeSummary)
        assertEquals("DNS узла через VPN · IPv4 и IPv6", ui.dnsSummary)
        assertEquals("Zapret KVN 1.4.2", ui.aboutSummary)
        assertEquals(false, ui.hideServerAddresses)
        assertEquals(false, ui.slowServerSwitch)
        assertEquals("Выключена", ui.automationSummary)
    }

    @Test
    fun `theme has four distinct titles`() {
        assertEquals(4, ThemeMode.entries.map { it.title() }.toSet().size)
        assertEquals("Как в системе", ThemeMode.System.title())
    }

    @Test
    fun `vpn hiding summary lists protection mtu and name`() {
        assertEquals(
            "Локальные порты закрыты · MTU 1500",
            vpnHidingSummary(VpnHidingOptions()),
        )
        assertEquals(
            "Локальные порты открыты · MTU по профилю · нейтральное имя",
            vpnHidingSummary(
                VpnHidingOptions(
                    blockLocalEndpoints = false,
                    neutralSessionName = true,
                    tunMtuMode = TunMtuMode.CoreDefault,
                ),
            ),
        )
    }

    @Test
    fun `wifi messages carry their kind instead of relying on text`() {
        assertEquals(WifiMessageKind.Success, WifiMessage.added("Home").kind)
        assertEquals(WifiMessageKind.Error, WifiMessage.nameUnavailable().kind)
        assertEquals(WifiMessageKind.Error, WifiMessage.permissionDenied().kind)
    }

    @Test
    fun `dns override hint depends on the mode`() {
        assertEquals(
            "Сохранено, но не применяется в режиме «Из JSON».",
            dnsOverrideHint(DnsMode.FromJson),
        )
        assertEquals(
            true,
            dnsOverrideHint(DnsMode.Automatic).startsWith("Применяется только после автоматического"),
        )
    }
}
