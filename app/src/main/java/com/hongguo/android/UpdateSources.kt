package com.hongguo.android

import java.net.URI
import java.util.Locale

enum class UpdateSource(val label: String) {
    AUTO("自动切换"),
    DIRECT("GitHub 直连"),
    PROXY_PRIMARY("加速线路 1"),
    PROXY_BACKUP("加速线路 2"),
}

data class UpdateRoute(val source: UpdateSource, val url: String)

/** Only public GitHub release resources can be wrapped; manifest URLs always remain canonical. */
object UpdateSources {
    private val proxyHosts = mapOf(
        UpdateSource.PROXY_PRIMARY to "gh-proxy.com",
        UpdateSource.PROXY_BACKUP to "ghfast.top",
    )
    private val assetHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")

    fun routes(canonicalUrl: String, source: UpdateSource): List<UpdateRoute> {
        canonicalUri(canonicalUrl)
        val sources = if (source == UpdateSource.AUTO)
            listOf(UpdateSource.DIRECT, UpdateSource.PROXY_PRIMARY, UpdateSource.PROXY_BACKUP) else listOf(source)
        return sources.map { choice ->
            UpdateRoute(choice, proxyHosts[choice]?.let { "https://$it/$canonicalUrl" } ?: canonicalUrl)
        }
    }

    fun checkedTransportUri(raw: String): URI {
        val uri = secureUri(raw)
        val host = uri.host.lowercase(Locale.ROOT)
        when {
            host == "github.com" -> canonicalUri(raw)
            host in assetHosts -> Unit // GitHub asset redirects contain an expiring signed query.
            host in proxyHosts.values -> {
                require(uri.rawQuery == null) { "UPDATE_SOURCE_URL_INVALID" }
                canonicalUri(uri.rawPath.removePrefix("/"))
            }
            else -> throw IllegalArgumentException("UPDATE_SOURCE_URL_INVALID")
        }
        return uri
    }

    /** Android URI.resolve can collapse the embedded https:// path used by public proxy redirects. */
    fun resolveRedirect(current: URI, location: String): URI {
        val target = try {
            when {
                location.startsWith("//") -> "${current.scheme}:$location"
                location.startsWith('/') -> "${current.scheme}://${current.rawAuthority}$location"
                else -> java.net.URL(current.toURL(), location).toString()
            }
        } catch (_: Exception) { throw IllegalArgumentException("UPDATE_SOURCE_URL_INVALID") }
        return checkedTransportUri(target)
    }

    private fun canonicalUri(raw: String): URI {
        val uri = secureUri(raw)
        require(uri.host.equals("github.com", true) && uri.rawQuery == null) { "UPDATE_SOURCE_URL_INVALID" }
        val path = uri.path.orEmpty()
        require(path.startsWith('/') && path.none { it.isISOControl() || it == '\\' }) { "UPDATE_SOURCE_URL_INVALID" }
        val parts = path.removePrefix("/").split('/')
        require(parts.size == 6 && parts.none { it.isBlank() || it == "." || it == ".." }
            && parts[0].matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))
            && parts[1].matches(Regex("[A-Za-z0-9_.-]{1,100}")) && parts[2] == "releases"
            && ((parts[3] == "latest" && parts[4] == "download") || parts[3] == "download")) {
            "UPDATE_SOURCE_URL_INVALID"
        }
        // Encoded slashes/dots cannot alter the interpretation of the nested GitHub URL.
        require(!uri.rawPath.orEmpty().contains(Regex("%(2f|5c|25)", RegexOption.IGNORE_CASE))) { "UPDATE_SOURCE_URL_INVALID" }
        return uri
    }

    private fun secureUri(raw: String): URI {
        val uri = try { URI(raw) } catch (_: Exception) { throw IllegalArgumentException("UPDATE_SOURCE_URL_INVALID") }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
            && uri.rawFragment == null && !uri.isOpaque && (uri.port == -1 || uri.port == 443)) {
            "UPDATE_SOURCE_URL_INVALID"
        }
        return uri
    }
}
