package io.github.zapretkvn.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateJsonTest {
    @Test
    fun `partial matrix metadata selects device ABI and matching checksum strictly`() {
        val metadata = UpdateJson.metadata(PARTIAL_METADATA, listOf("armeabi-v7a", "arm64-v8a"))

        assertEquals("1.2.3", metadata.versionName)
        assertEquals(102_003_099, metadata.versionCode)
        assertEquals(listOf("armeabi-v7a"), metadata.abi)
        assertEquals(SHA, UpdateJson.checksum("$SHA  Zapret-KVN-v1.2.3-armeabi-v7a.apk\n", metadata.apkFile))
    }

    @Test
    fun `matrix metadata rejects duplicate ABI and missing device ABI`() {
        val duplicate = PARTIAL_METADATA.replace(
            """{"abi":"armeabi-v7a"""",
            """{"abi":"armeabi-v7a","apk_file":"duplicate.apk","apk_sha256":"$SHA","apk_size":1234},{"abi":"armeabi-v7a"""",
        )

        assertThrows(UpdateException::class.java) {
            UpdateJson.metadata(duplicate, listOf("armeabi-v7a"))
        }
        assertThrows(UpdateException::class.java) {
            UpdateJson.metadata(PARTIAL_METADATA, listOf("x86_64"))
        }
    }

    @Test
    fun `checksum for another file and unsafe apk name are rejected`() {
        assertThrows(UpdateException::class.java) {
            UpdateJson.checksum("$SHA  another.apk", "Zapret-KVN-v1.2.3-arm64-v8a.apk")
        }
        assertThrows(UpdateException::class.java) {
            UpdateJson.metadata(
                METADATA.replace("Zapret-KVN-v1.2.3-armeabi-v7a.apk", "../update.apk"),
                listOf("armeabi-v7a"),
            )
        }
    }

    @Test
    fun `legacy arm64 metadata remains accepted during updater transition`() {
        val metadata = UpdateJson.metadata(LEGACY_METADATA, listOf("arm64-v8a"))

        assertEquals(listOf("arm64-v8a"), metadata.abi)
        assertEquals("Zapret-KVN-v1.2.3-arm64-v8a.apk", metadata.apkFile)
    }

    @Test
    fun `github release parser keeps draft prerelease and digest`() {
        val release = UpdateJson.releases(RELEASE).single()

        assertEquals("v1.2.3", release.tag)
        assertEquals("Fixed updater", release.body)
        assertEquals("2026-07-22T20:23:04Z", release.publishedAt)
        assertEquals(false, release.draft)
        assertEquals(true, release.prerelease)
        assertEquals("sha256:$SHA", release.assets[1].digest)
    }

    @Test
    fun `blocked html response permits one vpn retry`() {
        val error = assertThrows(UpdateException::class.java) {
            UpdateJson.releases("<html>blocked</html>")
        }

        assertTrue(error.retryViaVpn)
    }

    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val METADATA = """
            {
              "schema":2,
              "version_name":"1.2.3",
              "version_code":102003099,
              "application_id":"io.github.zapretkvn.android",
              "core_tag":"v1.13.14-extended-2.5.2",
              "core_commit":"ff11f007ec798136a5de258f947a4f34011a37ea",
              "artifacts":[
                {"abi":"arm64-v8a","apk_file":"Zapret-KVN-v1.2.3-arm64-v8a.apk","apk_sha256":"$SHA","apk_size":1234},
                {"abi":"armeabi-v7a","apk_file":"Zapret-KVN-v1.2.3-armeabi-v7a.apk","apk_sha256":"$SHA","apk_size":1234},
                {"abi":"x86_64","apk_file":"Zapret-KVN-v1.2.3-x86_64.apk","apk_sha256":"$SHA","apk_size":1234}
              ]
            }
        """
        const val PARTIAL_METADATA = """
            {
              "schema":2,
              "version_name":"1.2.3",
              "version_code":102003099,
              "application_id":"io.github.zapretkvn.android",
              "core_tag":"v1.13.14-extended-2.5.2",
              "core_commit":"ff11f007ec798136a5de258f947a4f34011a37ea",
              "artifacts":[
                {"abi":"armeabi-v7a","apk_file":"Zapret-KVN-v1.2.3-armeabi-v7a.apk","apk_sha256":"$SHA","apk_size":1234}
              ]
            }
        """
        const val LEGACY_METADATA = """
            {
              "schema":1,"version_name":"1.2.3","version_code":102003099,
              "application_id":"io.github.zapretkvn.android","core_tag":"v1.13.14-extended-2.5.2",
              "core_commit":"ff11f007ec798136a5de258f947a4f34011a37ea","abi":["arm64-v8a"],
              "apk_file":"Zapret-KVN-v1.2.3-arm64-v8a.apk","apk_sha256":"$SHA","apk_size":1234
            }
        """
        const val RELEASE = """
            {
              "tag_name":"v1.2.3",
              "name":"Beta 1.2.3",
              "body":"Fixed updater",
              "html_url":"https://git.zapret.moe/zapretkvn/ZapretKVN-android/releases/tag/v1.2.3",
              "published_at":"2026-07-22T20:23:04Z",
              "draft":false,
              "prerelease":true,
              "assets":[
                {"name":"release-metadata.json","browser_download_url":"https://github.com/a","size":10},
                {"name":"app.apk","browser_download_url":"https://github.com/b","size":1234,"digest":"sha256:$SHA"}
              ]
            }
        """
    }
}
