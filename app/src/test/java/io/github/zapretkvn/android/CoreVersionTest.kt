package io.github.zapretkvn.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreVersionTest {
    @Test
    fun pinnedCoreIdentityIsComplete() {
        // Тег ядра меняет резолвер при сборке (scripts/resolve_core_version.py),
        // поэтому проверяется форма стабильного тега, а не конкретная версия.
        assertTrue(BuildConfig.CORE_TAG.matches(Regex("v\\d+\\.\\d+\\.\\d+-extended-\\d+\\.\\d+\\.\\d+")))
        assertEquals(40, BuildConfig.CORE_COMMIT.length)
        assertTrue(BuildConfig.CORE_COMMIT.matches(Regex("[0-9a-f]{40}")))
        assertEquals(64, BuildConfig.CORE_PATCH_SHA256.length)
        assertTrue(BuildConfig.CORE_PATCH_SHA256.matches(Regex("[0-9a-f]{64}")))
    }
}
