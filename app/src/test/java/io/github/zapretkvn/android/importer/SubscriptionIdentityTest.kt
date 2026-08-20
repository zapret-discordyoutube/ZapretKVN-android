package io.github.zapretkvn.android.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionIdentityTest {

    private val device = DeviceIdentity(
        os = "Android",
        osVersion = "14",
        model = "Pixel 8",
        locale = "ru-RU",
    )

    @Test
    fun `hwid headers are sent only when enabled`() {
        val source = SubscriptionSource("https://sub.example/profile")

        val headers = SubscriptionIdentity.requestHeaders(source, device, APP_VERSION)

        assertFalse(headers.containsKey("X-HWID"))
        assertFalse(headers.containsKey("X-Device-OS"))
        assertEquals("Zapret-KVN-Android", headers["User-Agent"])
    }

    @Test
    fun `hwid headers describe the device`() {
        val source = SubscriptionSource(
            url = "https://sub.example/profile",
            sendHwid = true,
            hwid = "6F1B0A2C-1111-4222-8333-444455556666",
        )

        val headers = SubscriptionIdentity.requestHeaders(source, device, APP_VERSION)

        assertEquals("6F1B0A2C-1111-4222-8333-444455556666", headers["X-HWID"])
        assertEquals("Android", headers["X-Device-OS"])
        assertEquals("14", headers["X-Ver-OS"])
        assertEquals("Pixel 8", headers["X-Device-Model"])
    }

    @Test
    fun `emulated client profiles carry their own user agent and version`() {
        val happ = SubscriptionIdentity.requestHeaders(
            SubscriptionSource(
                url = "https://sub.example/profile",
                clientProfile = SubscriptionClientProfile.Happ,
                sendHwid = true,
                hwid = "device-1",
            ),
            device,
            APP_VERSION,
        )
        assertEquals("Happ/3.13.0", happ["User-Agent"])
        assertEquals("3.13.0", happ["X-App-Version"])
        assertEquals("*/*", happ["Accept"])
        assertEquals("ru-RU", happ["X-Device-Locale"])
        assertNull(happ["X-Device-ID"])

        val incy = SubscriptionIdentity.requestHeaders(
            SubscriptionSource(
                url = "https://sub.example/profile",
                clientProfile = SubscriptionClientProfile.Incy,
                sendHwid = true,
                hwid = "device-1",
            ),
            device,
            APP_VERSION,
        )
        assertEquals("INCY/1.0/Android", incy["User-Agent"])
        assertEquals("INCY", incy["X-Client"])
        assertEquals("device-1", incy["X-Device-ID"])

        val v2rayTun = SubscriptionIdentity.requestHeaders(
            SubscriptionSource(
                url = "https://sub.example/profile",
                clientProfile = SubscriptionClientProfile.V2RayTun,
            ),
            device,
            APP_VERSION,
        )
        assertEquals("v2rayTun/2.3.5", v2rayTun["User-Agent"])
    }

    @Test
    fun `custom user agent overrides the profile default`() {
        val headers = SubscriptionIdentity.requestHeaders(
            SubscriptionSource(
                url = "https://sub.example/profile",
                clientProfile = SubscriptionClientProfile.Custom,
                userAgent = "MyClient/9.9",
            ),
            device,
            APP_VERSION,
        )

        assertEquals("MyClient/9.9", headers["User-Agent"])
    }

    @Test
    fun `invalid hwid is rejected`() {
        assertThrows(ImportException::class.java) { SubscriptionIdentity.validateHwid("  ") }
        assertThrows(ImportException::class.java) {
            SubscriptionIdentity.validateHwid("device\r\nX-Injected: 1")
        }
        assertThrows(ImportException::class.java) {
            SubscriptionIdentity.validateHwid("x".repeat(SubscriptionIdentity.MAX_HWID_LENGTH + 1))
        }
        assertEquals("device-1", SubscriptionIdentity.validateHwid(" device-1 "))
    }

    @Test
    fun `device limit responses are explained`() {
        assertTrue(
            SubscriptionIdentity.describeHttpFailure(404, listOf("X-Hwid-Max-Devices-Reached"))
                .contains("лимит устройств"),
        )
        assertTrue(
            SubscriptionIdentity.describeHttpFailure(404, listOf("x-hwid-limit"))
                .contains("лимит устройств"),
        )
        assertTrue(
            SubscriptionIdentity.describeHttpFailure(400, listOf("x-hwid-not-supported"))
                .contains("HWID"),
        )
        assertEquals(
            "Сервер подписки вернул HTTP 500.",
            SubscriptionIdentity.describeHttpFailure(500, listOf(null, "Content-Type")),
        )
    }

    @Test
    fun `plain http sources keep their url and have no profile hint`() {
        val resolved = SubscriptionIdentity.resolveSource(
            "https://sub.example/profile?token=secret#label",
        )

        assertEquals("https://sub.example/profile?token=secret", resolved.url)
        assertNull(resolved.profileHint)
    }

    @Test
    fun `open deep links unwrap to the subscription url`() {
        val plain = SubscriptionIdentity.resolveSource(
            "happ://add/https%3A%2F%2Fsub.example%2Fprofile%3Ftoken%3Dsecret",
        )
        assertEquals("https://sub.example/profile?token=secret", plain.url)
        assertEquals(SubscriptionClientProfile.Happ, plain.profileHint)

        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("https://sub.example/profile".toByteArray())
        val wrapped = SubscriptionIdentity.resolveSource("v2raytun://import/$encoded")
        assertEquals("https://sub.example/profile", wrapped.url)
        assertEquals(SubscriptionClientProfile.V2RayTun, wrapped.profileHint)

        val query = SubscriptionIdentity.resolveSource(
            "incy://import?url=https%3A%2F%2Fsub.example%2Fprofile",
        )
        assertEquals("https://sub.example/profile", query.url)
        assertEquals(SubscriptionClientProfile.Incy, query.profileHint)
    }

    @Test
    fun `encrypted happ links resolve through the crypt decoder`() {
        val resolved = SubscriptionIdentity.resolveSource(CRYPT4_LINK)

        assertEquals("https://premiumt.shop/sub/5ESXeShpoSc_mbKK", resolved.url)
        assertEquals(SubscriptionClientProfile.Happ, resolved.profileHint)
    }

    @Test
    fun `unsupported sources are rejected`() {
        assertThrows(ImportException::class.java) {
            SubscriptionIdentity.resolveSource("file:///tmp/profile.json")
        }
        assertThrows(ImportException::class.java) {
            SubscriptionIdentity.resolveSource("happ://add/not-a-url")
        }
    }

    private companion object {
        const val APP_VERSION = "0.2.18"

        /** Вектор `crypt4-happwn-vector` из happ_vectors.json. */
        val CRYPT4_LINK: String = HappVectors.link("crypt4-happwn-vector")
    }
}
