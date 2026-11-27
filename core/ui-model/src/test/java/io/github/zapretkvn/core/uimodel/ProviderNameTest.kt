package io.github.zapretkvn.core.uimodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор имени провайдера совпадает с Windows-версией на настоящих именах владельца (2026-11-27). */
class ProviderNameTest {
    private class Golden(val name: String, val expected: ProviderName)

    private fun golden(): List<Golden> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("provider_names.tsv")) {
            "provider_names.tsv is missing"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return text.split('\n').filter { it.isNotEmpty() }.map { line ->
            // split с limit: пустые хвостовые поля сохраняются
            val cells = line.split('\t', limit = 5)
            assertEquals(line, 5, cells.size)
            Golden(cells[0], ProviderName(flag = cells[1], server = cells[2], variant = cells[3], via = cells[4]))
        }
    }

    @Test
    fun `every real name is read exactly as the Windows parser reads it`() {
        val rows = golden()
        assertTrue("fixture is not empty: ${rows.size}", rows.size > 1500)
        val wrong = rows.mapNotNull { row ->
            val got = ProviderName.parse(row.name)
            if (got == row.expected) null else "${row.name} -> $got, expected ${row.expected}"
        }
        assertEquals("names read differently from Windows: ${wrong.take(8)}", 0, wrong.size)
    }

    @Test
    fun `shapes from real subscriptions`() {
        assertEquals(ProviderName("🇸🇪", "4stok", "VLESS gRPC · TLS · V2", ""), ProviderName.parse("🇸🇪 4stok · VLESS gRPC · TLS · V2"))
        assertEquals("Hiphopgen", ProviderName.parse("🇩🇪 Hiphopgen через Россию").server)
        assertEquals("через Россию", ProviderName.parse("🇩🇪 Hiphopgen через Россию").via)
        assertEquals(ProviderName("🇳🇱", "Нидерланды", "3", ""), ProviderName.parse("🇳🇱 Нидерланды 3"))
        assertEquals(ProviderName("", "plc31", "tcp", ""), ProviderName.parse("plc31-tcp"))
        assertEquals(ProviderName("", "proxy", "", ""), ProviderName.parse("proxy"))
    }

    @Test
    fun `the same server in another case is one server`() {
        assertEquals(ProviderName.parse("Alpha · A").serverKey, ProviderName.parse("ALPHA · B").serverKey)
    }
}
