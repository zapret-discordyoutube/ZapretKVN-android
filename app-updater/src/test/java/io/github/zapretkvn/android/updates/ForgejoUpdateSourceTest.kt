package io.github.zapretkvn.android.updates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ForgejoUpdateSourceTest {
    @Test
    fun `stable release requires metadata apk and two agreeing digests`() {
        val http = FakeHttp()
        http.text[LIST] = "[${release(prerelease = false)}]"
        http.text[METADATA_URL] = metadata(SHA)
        http.text[CHECKSUM_URL] = "$SHA  $APK"

        val candidate = ForgejoUpdateSource(REPOSITORY, APPLICATION_ID, http, listOf("arm64-v8a"))
            .latest(UpdateChannel.Stable)

        assertEquals(102_003_099, candidate.metadata.versionCode)
        assertEquals(APK, candidate.apkAsset.name)
    }

    @Test
    fun `beta selects prerelease and ignores normal releases`() {
        val betaHttp = FakeHttp().apply {
            text[LIST] = "[${release(prerelease = false).replace("v1.2.3", "v9.9.9")},${release(prerelease = true)}]"
            text[METADATA_URL] = metadata(SHA)
            text[CHECKSUM_URL] = "$SHA  $APK"
        }
        assertEquals(
            "v1.2.3",
            ForgejoUpdateSource(REPOSITORY, APPLICATION_ID, betaHttp, listOf("arm64-v8a"))
                .latest(UpdateChannel.Beta).release.tag,
        )
        betaHttp.text[CHECKSUM_URL] = "${"f".repeat(64)}  $APK"
        assertThrows(UpdateException::class.java) {
            ForgejoUpdateSource(REPOSITORY, APPLICATION_ID, betaHttp, listOf("arm64-v8a"))
                .latest(UpdateChannel.Beta)
        }
    }

    @Test
    fun `beta selects newest publication when API order is stale`() {
        val http = FakeHttp().apply {
            text[LIST] = "[" +
                release(
                    prerelease = true,
                    title = "Older SemVer-first prerelease",
                    publishedAt = "2026-07-22T20:02:37Z",
                ) + "," +
                release(
                    prerelease = true,
                    title = "Newest published prerelease",
                    publishedAt = "2026-07-22T20:23:04Z",
                ) +
                "]"
            text[METADATA_URL] = metadata(SHA)
            text[CHECKSUM_URL] = "$SHA  $APK"
        }

        val candidate = ForgejoUpdateSource(REPOSITORY, APPLICATION_ID, http, listOf("arm64-v8a"))
            .latest(UpdateChannel.Beta)

        assertEquals("Newest published prerelease", candidate.release.title)
        assertEquals("2026-07-22T20:23:04Z", candidate.release.publishedAt)
    }

    @Test
    fun `channels fail when only the opposite release kind exists`() {
        val onlyStable = FakeHttp().apply {
            text[LIST] = "[${release(prerelease = false)}]"
        }
        val onlyBeta = FakeHttp().apply {
            text[LIST] = "[${release(prerelease = true)}]"
        }

        assertThrows(UpdateException::class.java) {
            ForgejoUpdateSource(REPOSITORY, APPLICATION_ID, onlyStable, listOf("arm64-v8a"))
                .latest(UpdateChannel.Beta)
        }
        assertThrows(UpdateException::class.java) {
            ForgejoUpdateSource(REPOSITORY, APPLICATION_ID, onlyBeta, listOf("arm64-v8a"))
                .latest(UpdateChannel.Stable)
        }
    }

    @Test
    fun `matrix metadata selects the first device-supported ABI only`() {
        val http = FakeHttp().apply {
            text[LIST] = "[${matrixRelease()}]"
            text[MATRIX_METADATA_URL] = matrixMetadata()
            text[V7_CHECKSUM_URL] = "$SHA  $V7_APK"
        }

        val candidate = ForgejoUpdateSource(
            REPOSITORY,
            APPLICATION_ID,
            http,
            listOf("armeabi-v7a"),
        ).latest(UpdateChannel.Stable)

        assertEquals(listOf("armeabi-v7a"), candidate.metadata.abi)
        assertEquals(V7_APK, candidate.apkAsset.name)
    }

    @Test
    fun `only fixed Forgejo https hosts are accepted`() {
        assertEquals(
            "https://git.zapret.moe/zapretdiscordyoutube/ZapretKVN-android/releases/download/v1/file",
            ForgejoHttpsClient.validatedUrl(
                "https://git.zapret.moe/zapretdiscordyoutube/ZapretKVN-android/releases/download/v1/file",
            ),
        )
        assertThrows(UpdateException::class.java) { ForgejoHttpsClient.validatedUrl("http://git.zapret.moe/file") }
        assertThrows(UpdateException::class.java) { ForgejoHttpsClient.validatedUrl("https://example.com/file") }
        assertThrows(UpdateException::class.java) { ForgejoHttpsClient.validatedUrl("https://git.zapret.moe@evil.example/file") }
    }

    private class FakeHttp : UpdateHttpClient {
        val text = mutableMapOf<String, String>()
        override fun readText(url: String, maxBytes: Int): String = text[url] ?: error("missing $url")
        override fun download(url: String, target: File, expectedBytes: Long, onProgress: (Long) -> Unit) =
            error("not used")
    }

    private companion object {
        const val REPOSITORY = "zapretdiscordyoutube/ZapretKVN-android"
        const val APPLICATION_ID = "io.github.zapretkvn.android"
        const val LIST = "https://git.zapret.moe/api/v1/repos/$REPOSITORY/releases?limit=20"
        const val METADATA_URL = "https://git.zapret.moe/$REPOSITORY/releases/download/v1.2.3/release-metadata.json"
        const val CHECKSUM_URL = "https://git.zapret.moe/$REPOSITORY/releases/download/v1.2.3/app.sha256"
        const val APK_URL = "https://git.zapret.moe/$REPOSITORY/releases/download/v1.2.3/app"
        const val APK = "Zapret-KVN-v1.2.3-arm64-v8a.apk"
        const val V7_APK = "Zapret-KVN-v1.2.3-armeabi-v7a.apk"
        const val X64_APK = "Zapret-KVN-v1.2.3-x86_64.apk"
        const val MATRIX_METADATA_URL = "https://git.zapret.moe/$REPOSITORY/releases/download/v1.2.3/release-metadata-v2.json"
        const val V7_CHECKSUM_URL = "https://git.zapret.moe/$REPOSITORY/releases/download/v1.2.3/v7.sha256"
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        fun release(
            prerelease: Boolean,
            title: String = "1.2.3",
            publishedAt: String? = null,
        ) = """
            {
              "tag_name":"v1.2.3","name":"$title","html_url":"https://git.zapret.moe/$REPOSITORY/releases/tag/v1.2.3",
              ${publishedAt?.let { "\"published_at\":\"$it\"," }.orEmpty()}
              "draft":false,"prerelease":$prerelease,
              "assets":[
                {"name":"release-metadata.json","browser_download_url":"$METADATA_URL","size":500},
                {"name":"$APK","browser_download_url":"$APK_URL","size":1234,"digest":"sha256:$SHA"},
                {"name":"$APK.sha256","browser_download_url":"$CHECKSUM_URL","size":100}
              ]
            }
        """

        fun metadata(sha: String) = """
            {"schema":1,"version_name":"1.2.3","version_code":102003099,
             "application_id":"$APPLICATION_ID","core_tag":"v1.13.14-extended-2.5.2",
             "core_commit":"ff11f007ec798136a5de258f947a4f34011a37ea","abi":["arm64-v8a"],
             "apk_file":"$APK","apk_sha256":"$sha","apk_size":1234}
        """

        fun matrixRelease() = """
            {
              "tag_name":"v1.2.3","name":"1.2.3","html_url":"https://git.zapret.moe/$REPOSITORY/releases/tag/v1.2.3",
              "draft":false,"prerelease":false,
              "assets":[
                {"name":"release-metadata-v2.json","browser_download_url":"$MATRIX_METADATA_URL","size":1000},
                {"name":"$APK","browser_download_url":"$APK_URL-arm64","size":1234,"digest":"sha256:$SHA"},
                {"name":"$APK.sha256","browser_download_url":"$CHECKSUM_URL-arm64","size":100},
                {"name":"$V7_APK","browser_download_url":"$APK_URL-v7","size":1234,"digest":"sha256:$SHA"},
                {"name":"$V7_APK.sha256","browser_download_url":"$V7_CHECKSUM_URL","size":100},
                {"name":"$X64_APK","browser_download_url":"$APK_URL-x64","size":1234,"digest":"sha256:$SHA"},
                {"name":"$X64_APK.sha256","browser_download_url":"$CHECKSUM_URL-x64","size":100}
              ]
            }
        """

        fun matrixMetadata() = """
            {"schema":2,"version_name":"1.2.3","version_code":102003099,
             "application_id":"$APPLICATION_ID","core_tag":"v1.13.14-extended-2.5.2",
             "core_commit":"ff11f007ec798136a5de258f947a4f34011a37ea","artifacts":[
               {"abi":"arm64-v8a","apk_file":"$APK","apk_sha256":"$SHA","apk_size":1234},
               {"abi":"armeabi-v7a","apk_file":"$V7_APK","apk_sha256":"$SHA","apk_size":1234},
               {"abi":"x86_64","apk_file":"$X64_APK","apk_sha256":"$SHA","apk_size":1234}
             ]}
        """
    }
}
