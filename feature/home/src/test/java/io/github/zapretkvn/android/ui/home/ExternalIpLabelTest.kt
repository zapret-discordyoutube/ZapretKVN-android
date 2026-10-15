package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.vpn.ExternalIpStatus
import io.github.zapretkvn.android.vpn.VpnSessionStats
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalIpLabelTest {
    @Test
    fun `address is not shown until the user asks for it`() {
        assertEquals("Показать IP", externalIpLabel(VpnSessionStats()))
    }

    @Test
    fun `request progress failure and result have their own captions`() {
        assertEquals(
            "определяем…",
            externalIpLabel(VpnSessionStats(externalIpStatus = ExternalIpStatus.Loading)),
        )
        assertEquals(
            "Не удалось · нажмите ещё раз",
            externalIpLabel(VpnSessionStats(externalIpStatus = ExternalIpStatus.Failed)),
        )
        assertEquals("203.0.113.9", externalIpLabel(VpnSessionStats(externalIp = "203.0.113.9")))
    }

    @Test
    fun `a refresh in progress or failed keeps the known address visible`() {
        val known = VpnSessionStats(externalIp = "203.0.113.9", externalIpStatus = ExternalIpStatus.Failed)
        assertEquals("203.0.113.9", externalIpLabel(known))
        assertEquals("определяем…", externalIpLabel(known.copy(externalIpStatus = ExternalIpStatus.Loading)))
    }
}
