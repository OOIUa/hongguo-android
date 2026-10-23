package com.hongguo.android

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class UpdateSourcesTest {
    private val apk = "https://github.com/OOIUa/hongguo-android/releases/download/v0.4.0/hongguo-android-0.4.0.apk"
    private val manifest = "https://github.com/OOIUa/hongguo-android/releases/latest/download/update.json"

    @Test fun automaticOrderAndManualSelectionRemainExplicit() {
        val routes = UpdateSources.routes(apk, UpdateSource.AUTO)
        assertEquals(listOf(UpdateSource.DIRECT, UpdateSource.PROXY_PRIMARY, UpdateSource.PROXY_BACKUP), routes.map { it.source })
        assertEquals(apk, routes.first().url)
        assertEquals("https://gh-proxy.com/$apk", routes[1].url)
        assertEquals("https://ghfast.top/$apk", routes[2].url)
        for (source in UpdateSource.entries.filter { it != UpdateSource.AUTO }) {
            assertEquals(1, UpdateSources.routes(manifest, source).size)
            assertEquals(source, UpdateSources.routes(manifest, source).single().source)
        }
    }

    @Test fun canonicalAndProxyReleaseUrlsAndSignedGithubAssetsAreAccepted() {
        for (url in listOf(apk, manifest, "https://gh-proxy.com/$apk", "https://ghfast.top/$manifest",
            "https://release-assets.githubusercontent.com/asset/file?sig=public-test", "https://objects.githubusercontent.com/asset?download=1")) {
            assertEquals(url, UpdateSources.checkedTransportUri(url).toString())
        }
    }

    @Test fun wrappingRejectsAnythingOtherThanPublicReleaseResource() {
        for (url in listOf("http://github.com/O/R/releases/latest/download/update.json", "https://example.com/a",
            "https://github.com/O/R/blob/main/update.json", "https://github.com/O/R/releases/download/v1/../a.apk",
            "https://github.com/O/R/releases/download/v1/a%2fb.apk", "$apk?secret=fixture", "$apk#part")) {
            reject { UpdateSources.routes(url, UpdateSource.AUTO) }
        }
    }

    @Test fun proxyCannotBeUsedAsGenericForwarderOrRedirectEscape() {
        for (url in listOf("https://gh-proxy.com/https://evil.example/file.apk", "https://ghfast.top/https://127.0.0.1/update.json",
            "https://gh-proxy.com/https://ghfast.top/$apk", "https://ghfast.top/$apk?query=fixture",
            "https://gh-proxy.com.attacker.example/$apk", "https://user:fixture@gh-proxy.com/$apk",
            "https://gh-proxy.com:444/$apk", "https://ghfast.top/https://github.com/O/R/releases/download/v1/a%252f.apk",
            "https://gh-proxy.com/", "https://raw.githubusercontent.com/O/R/main/update.json", "http://release-assets.githubusercontent.com/a")) {
            reject { UpdateSources.checkedTransportUri(url) }
        }
    }

    @Test fun relativeProxyRedirectPreservesEmbeddedHttpsSlashes() {
        val initial = URI("https://ghfast.top/$manifest")
        val tagged = "/https://github.com/OOIUa/hongguo-android/releases/download/v0.4.0/update.json"
        assertEquals("https://ghfast.top$tagged", UpdateSources.resolveRedirect(initial, tagged).toString())
        assertEquals("https://ghfast.top$tagged", UpdateSources.resolveRedirect(initial,
            "https://ghfast.top$tagged").toString())
        reject { UpdateSources.resolveRedirect(initial, "//attacker.example/file.apk") }
        reject { UpdateSources.resolveRedirect(initial, "/https://attacker.example/file.apk") }
    }

    private fun reject(action: () -> Unit) {
        try { action(); fail("Unsafe update route accepted") }
        catch (expected: IllegalArgumentException) { assertEquals("UPDATE_SOURCE_URL_INVALID", expected.message) }
    }
}
