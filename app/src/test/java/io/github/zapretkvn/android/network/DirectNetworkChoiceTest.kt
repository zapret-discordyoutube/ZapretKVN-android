package io.github.zapretkvn.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectNetworkChoiceTest {
    private fun net(
        id: Long,
        rank: Int,
        validated: Boolean = true,
        captive: Boolean = false,
        suspended: Boolean = false,
        notMetered: Boolean = rank >= 2,
    ) = DirectNetworkFacts(id, validated, captive, suspended, rank, notMetered)

    private val cellular = net(101, rank = 1)
    private val wifi = net(104, rank = 2)

    @Test
    fun `the network the vpn runs over wins`() {
        assertEquals(101L, chooseDirectNetwork(listOf(wifi, cellular), preferred = listOf(101L, 104L)))
    }

    @Test
    fun `without hints wifi beats cellular whatever order android lists them in`() {
        // Раньше бралась первая проверенная в списке allNetworks: на эмуляторе — мобильная без DNS.
        assertEquals(104L, chooseDirectNetwork(listOf(cellular, wifi), preferred = emptyList()))
        assertEquals(104L, chooseDirectNetwork(listOf(wifi, cellular), preferred = emptyList()))
    }

    @Test
    fun `a preferred network that android did not validate is skipped`() {
        val brokenWifi = net(104, rank = 2, validated = false)
        assertEquals(101L, chooseDirectNetwork(listOf(brokenWifi, cellular), preferred = listOf(104L)))
    }

    @Test
    fun `captive portal and suspended networks come last`() {
        val portal = net(7, rank = 3, captive = true)
        val suspendedWifi = net(8, rank = 2, suspended = true)
        assertEquals(101L, chooseDirectNetwork(listOf(portal, suspendedWifi, cellular), preferred = emptyList()))
    }

    @Test
    fun `nothing validated still gives the best guess and nothing at all gives null`() {
        val a = net(1, rank = 1, validated = false)
        val b = net(2, rank = 2, validated = false)
        assertEquals(2L, chooseDirectNetwork(listOf(a, b), preferred = emptyList()))
        assertNull(chooseDirectNetwork(emptyList(), preferred = listOf(1L)))
    }
}
