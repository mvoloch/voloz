package io.github.mvoloch.voloz.android.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The desktop's update badge for a sideloaded app: the newest `android-v*` release on
 * the Voloz repository, when it is newer than this build. One public GitHub request,
 * no data sent; the user downloads and installs it themselves.
 */
object UpdateCheck {
    const val RELEASES_URL = "https://api.github.com/repos/mvoloch/voloz/releases?per_page=30"
    private const val TAG_PREFIX = "android-v"

    data class Release(val version: String, val url: String)

    /** "0.2.10" -> [0, 2, 10]; anything after a "-" (e.g. "-prototype") is ignored. */
    internal fun parts(version: String): List<Int> =
        version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The newest non-draft Android release in a GitHub releases listing newer than [current], if any. */
    internal fun newest(releasesJson: String, current: String): Release? =
        Json.parseToJsonElement(releasesJson).jsonArray
            .map { it.jsonObject }
            .filter { it["draft"]?.jsonPrimitive?.booleanOrNull != true }
            .mapNotNull { r ->
                val tag = r["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!tag.startsWith(TAG_PREFIX)) return@mapNotNull null
                Release(tag.removePrefix(TAG_PREFIX), r["html_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null)
            }
            .filter { isNewer(it.version, current) }
            .maxWithOrNull { a, b -> if (isNewer(a.version, b.version)) 1 else if (isNewer(b.version, a.version)) -1 else 0 }

    /** Blocking (call off the main thread); null on any failure. */
    fun find(current: String, http: OkHttpClient = OkHttpClient()): Release? = runCatching {
        val request = Request.Builder().url(RELEASES_URL).header("Accept", "application/vnd.github+json").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            newest(response.body?.string().orEmpty(), current)
        }
    }.getOrNull()
}
