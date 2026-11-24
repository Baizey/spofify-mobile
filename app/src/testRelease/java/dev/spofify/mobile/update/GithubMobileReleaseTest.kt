package dev.spofify.mobile.update

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal val fixtureApk = "synthetic APK bytes".encodeToByteArray()
internal val fixtureDigest = MessageDigest.getInstance("SHA-256").digest(fixtureApk)
internal val fixtureHex = fixtureDigest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
private const val ASSET_NAME = "spofify-mobile-release.apk"
internal fun fixtureUrl(tag: String) =
    "https://github.com/Baizey/spofify-mobile-releases/releases/download/$tag/$ASSET_NAME"

internal fun metadata(
    tag: String = "mobile-v2",
    size: String = fixtureApk.size.toString(),
    digest: String = "\"sha256:$fixtureHex\"",
    url: String = fixtureUrl(tag),
) = """{
    "tag_name":"$tag", "draft":false, "prerelease":false,
    "body":"Release notes are ignored.", "assets":[{
        "name":"$ASSET_NAME", "state":"uploaded", "size":$size, "digest":$digest,
        "browser_download_url":"$url", "id":1
    }]
}"""

class GithubMobileReleaseTest {
    @Test fun acceptsExactPublicReleaseAndInclusiveLimits() {
        val result = parseMobileRelease(metadata(
            tag = "mobile-v2100000000", size = "157286400", digest = "\"sha256:${fixtureHex.uppercase()}\"",
        ).encodeToByteArray())
        assertEquals(2_100_000_000L, result.versionCode)
        assertEquals(150L * 1024 * 1024, result.size)
        assertArrayEquals(fixtureDigest, result.sha256)
        assertEquals(fixtureUrl("mobile-v2100000000"), result.download.toString())
        assertEquals("https://api.github.com/repos/Baizey/spofify-mobile-releases/releases/latest", LATEST_RELEASE.toString())
    }

    @Test fun rejectsNoncanonicalTagAndSize() {
        listOf("mobile-v0", "mobile-v01", "mobile-v+2", "mobile-v-1", "mobile-v1.0", "v2",
            "mobile-v2100000001", "mobile-v22222222222222222222", "mobile-v２", "mobile-v2 ").forEach {
            rejects(metadata(tag = it))
        }
        listOf("0", "-1", "+1", "01", "1.0", "1e0", "\"1\"", "null", "true",
            "157286401", "99999999999999999999999").forEach { rejects(metadata(size = it)) }
    }

    @Test fun requiresExactlyOneUploadedAssetWithGithubDigest() {
        val valid = metadata()
        listOf(
            valid.replace("\"draft\":false", "\"draft\":true"),
            valid.replace("\"prerelease\":false", "\"prerelease\":\"false\""),
            valid.replace("\"prerelease\":false,", ""),
            valid.replace("\"state\":\"uploaded\"", "\"state\":\"open\""),
            valid.replace("\"name\":\"$ASSET_NAME\"", "\"name\":\"another.apk\""),
            valid.substringBefore("\"assets\"") + "\"assets\":[]}",
            valid.replace("]", ",{\"name\":\"another.apk\"}]"),
            valid.replace("\"digest\":", "\"other_digest\":"),
            metadata(digest = "null"),
            metadata(digest = "\"sha1:$fixtureHex\""),
            metadata(digest = "\"SHA256:$fixtureHex\""),
            metadata(digest = "\"sha256:${fixtureHex.drop(1)}\""),
            metadata(digest = "\"sha256:${fixtureHex.drop(1)}g\""),
        ).forEach(::rejects)
    }

    @Test fun rejectsAlteredDownloadUrisAndAmbiguousOrOversizedJson() {
        val canonical = fixtureUrl("mobile-v2")
        listOf(
            canonical.replace("https", "http"),
            canonical.replace("github.com", "github.com:443"),
            canonical.replace("github.com", "github.com.invalid"),
            canonical.replace("Baizey", "baizey"),
            canonical.replace("spofify-mobile-releases", "spofify-mobile"),
            canonical.replace("mobile-v2", "mobile-v3"),
            "$canonical?query=1",
            "$canonical#fragment",
        ).forEach { rejects(metadata(url = it)) }
        val valid = metadata()
        rejects(valid.replace("\"draft\":false", "\"draft\":true, \"dra\\u0066t\":false"))
        rejects(valid.replace("\"size\":", "\"size\":0, \"\\u0073ize\":"))
        rejects(valid.replace("\"body\":\"Release notes are ignored.\"", "\"body\":" + "[".repeat(33) + "]".repeat(33)))
        rejects(valid.replace("\"Release notes are ignored.\"", "bare_literal"))
        rejects(valid.replace("\"Release notes are ignored.\"", "\"raw\nnewline\""))
        rejects(valid + " ".repeat(MAX_METADATA_BYTES))
        assertThrows(RejectedUpdate::class.java) {
            parseMobileRelease(valid.encodeToByteArray() + byteArrayOf(0xc3.toByte()))
        }
    }

    @Test fun requestHeadersAreAnonymousAndResourceSpecific() {
        val metadataRequest = updateRequest(LATEST_RELEASE, UpdateResource.METADATA)
        assertEquals("GET", metadataRequest.method)
        assertEquals(setOf("Accept", "Accept-Encoding", "User-Agent", "X-GitHub-Api-Version"), metadataRequest.headers.names())
        assertEquals("identity", metadataRequest.header("Accept-Encoding"))
        val assetRequest = updateRequest(java.net.URI(fixtureUrl("mobile-v2")), UpdateResource.APK)
        assertEquals(setOf("Accept", "Accept-Encoding", "User-Agent"), assetRequest.headers.names())
    }

    private fun rejects(value: String) {
        assertThrows(RejectedUpdate::class.java) { parseMobileRelease(value.encodeToByteArray()) }
    }
}
