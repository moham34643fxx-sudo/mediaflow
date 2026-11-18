package com.zdownloader.app.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val repository = "owner/mediaflow"
    private fun manifest(channel: String = "stable", tag: String = "v2") = JSONObject("""
        {"schema":1,"appId":"com.zdownloader.app","channel":"$channel","versionCode":51,
         "versionName":"0.9.18","minSdk":24,"publishedAt":"2026-10-27T12:00:00.1Z",
         "notes":{"ar":"تحسينات","en":"Improvements"},"assets":[
          {"abi":"arm64-v8a","url":"https://github.com/$repository/releases/download/$tag/arm64.apk",
           "sha256":"${"ab".repeat(32)}","bytes":10000},
          {"abi":"universal","url":"https://github.com/$repository/releases/download/$tag/all.apk",
           "sha256":"${"cd".repeat(32)}","bytes":20000}],
         "releaseUrl":"https://github.com/$repository/releases/tag/$tag"}
    """.trimIndent())

    @Test fun selectsNativeAssetBeforeUniversalAndUsesNumericVersion() {
        val release = UpdateManifest.parse(manifest(), repository, "stable", "com.zdownloader.app")
        assertEquals(51L, release.versionCode)
        assertEquals("arm64-v8a", release.selectAsset(listOf("arm64-v8a", "armeabi-v7a"))!!.abi)
        assertEquals("universal", release.selectAsset(listOf("x86_64"))!!.abi)
        assertNull(release.selectAsset(listOf("armeabi-v7a")))
        val roundTrip = UpdateManifest.parse(release.toJson(), repository, "stable", "com.zdownloader.app")
        assertEquals(release, roundTrip)
    }

    @Test fun strictIntegersBoundsAndUniqueAbis() {
        rejects(UpdateError.METADATA) { parse(manifest().put("versionCode", 51.5)) }
        rejects(UpdateError.METADATA) { parse(manifest().put("versionCode", "51")) }
        rejects(UpdateError.METADATA) { parse(manifest().put("versionCode", 0)) }
        rejects(UpdateError.METADATA) { parse(manifest().put("versionCode", 2147483648L)) }
        rejects(UpdateError.METADATA) { parse(manifest().also { it.getJSONArray("assets").getJSONObject(0).put("bytes", UpdateManifest.MAX_APK_BYTES + 1) }) }
        rejects(UpdateError.METADATA) { parse(manifest().also { it.getJSONArray("assets").getJSONObject(1).put("abi", "arm64-v8a") }) }
        rejects(UpdateError.METADATA) { parse(manifest().put("publishedAt", "2026-02-30T00:00:00Z")) }
        rejects(UpdateError.METADATA) { parse(manifest().put("appId", "other.app")) }
    }

    @Test fun repositoryTagAndHttpsOwnershipAreMandatory() {
        for (url in listOf(
            "http://github.com/owner/mediaflow/releases/download/v2/app.apk",
            "https://github.com/attacker/mediaflow/releases/download/v2/app.apk",
            "https://github.com/owner/mediaflow/releases/download/v2/app.apk?token=secret",
            "https://github.com/owner/mediaflow/releases/download/v2/app.apk#fragment",
            "https://github.com/owner/mediaflow/releases/download/v2%2Fescape/app.apk",
            "https://feed.example/app.apk", "https://github.com.evil.test/owner/mediaflow/releases/download/v2/app.apk"
        )) rejects(UpdateError.UNTRUSTED_URL) { parse(manifest().also { it.getJSONArray("assets").getJSONObject(0).put("url", url) }) }
        rejects(UpdateError.METADATA) { parse(manifest("qa", "qa-v2")) }
        rejects(UpdateError.METADATA) { UpdateManifest.parse(manifest("qa", "v2"), repository, "qa", "com.zdownloader.app") }
        assertEquals("qa", UpdateManifest.parse(manifest("qa", "QA-v2"), repository, "qa", "com.zdownloader.app").channel)
    }

    @Test fun publishedStableAndQaAreIsolatedAndEmptyIsNoRelease() {
        val rows = JSONArray().put(release("v1", false, false, "2026-10-25T00:00:00Z"))
            .put(release("QA-v2", true, false, "2026-10-26T00:00:00Z"))
            .put(release("v3", false, true, "2026-10-27T00:00:00Z"))
            .put(release("beta-v4", true, false, "2026-10-28T00:00:00Z"))
            .put(release("qa-wrongly-stable", false, false, "2026-10-29T00:00:00Z"))
        assertEquals("v1", UpdateDiscovery.selectRelease(rows, "stable")!!.getString("tag_name"))
        assertEquals("QA-v2", UpdateDiscovery.selectRelease(rows, "qa")!!.getString("tag_name"))
        assertNull(UpdateDiscovery.selectRelease(JSONArray(), "stable"))
        assertNull(UpdateDiscovery.selectRelease(JSONArray().put(release("v1", false, false, null)), "stable"))
    }

    @Test fun rangeRequiresExactOffsetEndTotalAndBodyLength() {
        assertEquals(60L, UpdateRange.validate("bytes 40-99/100", 40, 100, 60))
        for (header in listOf("bytes 0-99/100", "bytes 40-98/100", "bytes 40-99/*", "bytes 40-99/101", "bytes 40-100/100", "bytes 40-99/99999999999999999999999")) {
            rejects(UpdateError.RANGE) { UpdateRange.validate(header, 40, 100, 60) }
        }
        rejects(UpdateError.RANGE) { UpdateRange.validate("bytes 40-99/100", 40, 100, 59) }
        assertNull(UpdateRange.strongEtag("W/\"weak\""))
        assertEquals("\"strong\"", UpdateRange.strongEtag("\"strong\""))
    }

    private fun parse(json: JSONObject) = UpdateManifest.parse(json, repository, "stable", "com.zdownloader.app")
    private fun release(tag: String, prerelease: Boolean, draft: Boolean, time: String?) = JSONObject()
        .put("tag_name", tag).put("prerelease", prerelease).put("draft", draft).put("published_at", time)
        .put("assets", JSONArray().put(JSONObject().put("name", "mediaflow-update.json")))

    private fun rejects(error: UpdateError, block: () -> Unit) {
        try { block(); fail("Expected $error") } catch (e: UpdateException) { assertEquals(error, e.code) }
    }
}
