package io.github.zapretkvn.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticDownloadsSaverTest {
    @Test
    fun `file name has a fixed prefix, UTC time and zip extension`() {
        // 2026-11-14 10:50:00 UTC
        assertEquals("zapret-kvn-diagnostic-20261114-105000Z.zip", DiagnosticDownloadsSaver.fileName(1_794_653_400_000L))
    }

    @Test
    fun `different seconds give different names so a second save never overwrites the first`() {
        val a = DiagnosticDownloadsSaver.fileName(1_794_653_400_000L)
        val b = DiagnosticDownloadsSaver.fileName(1_794_653_401_000L)
        assertTrue(a != b)
        assertTrue(a.startsWith(DiagnosticDownloadsSaver.NAME_PREFIX))
    }

    @Test
    fun `the extra key of the exporter and of the button is the same string`() {
        assertEquals("io.github.zapretkvn.android.extra.CHOOSER_ACTIONS", DiagnosticExporter.EXTRA_SAVE_ACTIONS)
    }
}
