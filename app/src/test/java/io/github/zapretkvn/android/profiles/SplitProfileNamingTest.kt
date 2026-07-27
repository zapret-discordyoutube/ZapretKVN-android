package io.github.zapretkvn.android.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SplitProfileNamingTest {
    @Test
    fun `server names become profile names`() {
        val names = SplitProfileNaming.names(
            listOf("🇳🇱 Нидерланды", "Германия"),
            baseName = "Подписка",
        )

        assertEquals(listOf("🇳🇱 Нидерланды", "Германия"), names)
    }

    @Test
    fun `repeated server names are numbered`() {
        val names = SplitProfileNaming.names(
            listOf("Fast", "fast", "Fast"),
            baseName = "Подписка",
        )

        assertEquals(listOf("Fast", "fast (2)", "Fast (3)"), names)
        assertEquals(3, names.distinct().size)
    }

    @Test
    fun `blank server name falls back to the base name with its position`() {
        val names = SplitProfileNaming.names(listOf("   ", "Node"), baseName = " all_keys ")

        assertEquals(listOf("all_keys 1", "Node"), names)
    }

    @Test
    fun `blank base name still produces a usable profile name`() {
        assertEquals(listOf("Профиль 1"), SplitProfileNaming.names(listOf(""), baseName = ""))
    }

    @Test
    fun `credential-shaped server name never reaches the profile name`() {
        val uuid = "11111111-1111-4111-8111-111111111111"

        val name = SplitProfileNaming.names(listOf(uuid), baseName = "Подписка").single()

        assertFalse(uuid in name)
    }
}
