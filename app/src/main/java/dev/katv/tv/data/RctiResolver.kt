package dev.katv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Resolver untuk channel RCTI+ (toutatis / zeus ... /api/vN/player?permalink=...).
 * Endpoint itu BUKAN media — dia balik JSON berisi URL .m3u8/.mpd asli.
 * Scraper feed cuma nyimpan URL player + header, jadi tanpa resolver ini channel
 * RCTI+ selalu 401 / hitam.
 *
 * Pola (dari website rctiplus.com sendiri):
 *   1. GET halaman livetv -> ekstrak `var visitorsToken = "<JWT>"` (token anonymous)
 *   2. GET API dengan header: apikey (public, hardcode di halaman), Authorization=visitorsToken,
 *      drm=widevine, X-Device-Name, Referer, Origin
 *   3. data.player.url = stream asli (.m3u8 atau .mpd), possibly drm=true
 */
object RctiResolver {
    private const val API_KEY = "k1DzR0yYWIyZgvTvixiDHnb4Nl08wSU0"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:160.0) Gecko/20100101 Firefox/160.0"
    private val TOKEN_RE = Pattern.compile("var\\s+visitorsToken\\s*=\\s*\"([^\"]+)\"")
    private val PAGE_RE = Pattern.compile("permalink=([^&]+)")

    fun isRctiPlayer(url: String): Boolean =
        url.contains("rctiplus.com", true) && url.contains("/player", true)

    data class Result(val url: String, val drm: Boolean, val headers: Map<String, String>,
                      val licenseUrl: String = "", val licenseHeaders: Map<String, String> = emptyMap())

    suspend fun resolve(playerUrl: String): Result? = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("RctiResolver", "resolve START: ${playerUrl.take(90)}")
            // permalink di feed sudah URL-encoded; decode untuk dapet halaman asli
            val m = PAGE_RE.matcher(playerUrl)
            val encoded = if (m.find()) m.group(1) else return@withContext null
            val page = java.net.URLDecoder.decode(encoded, "UTF-8")
            android.util.Log.d("RctiResolver", "page=$page")
            if (!page.startsWith("http")) return@withContext null
            // Paksa HTTPS — feed nyimpan http:// yang 301 di emulator (HttpURLConnection bisa hang).
            val httpsPage = page.replaceFirst("http://", "https://")
            val host = try { URL(httpsPage).host } catch (e: Exception) { "www.rctiplus.com" }

            // 1. token anonymous dari halaman (via OkHttp — handle redirect + TLS lebih aman)
            val client = okhttp3.OkHttpClient.Builder()
                .followRedirects(true).followSslRedirects(true)
                .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()
            android.util.Log.d("RctiResolver", "fetching page $httpsPage")
            val req = okhttp3.Request.Builder().url(httpsPage).header("User-Agent", UA).build()
            val resp = client.newCall(req).execute()
            val pageHtml = resp.use { it.body?.string().orEmpty() }
            android.util.Log.d("RctiResolver", "page fetched len=${pageHtml.length}")
            val tm = TOKEN_RE.matcher(pageHtml)
            if (!tm.find()) { android.util.Log.e("RctiResolver", "no token in page"); return@withContext null }
            val token = tm.group(1)

            // 2. panggil API dengan permalink ter-encode (RFC3986)
            val permalink = URLEncoder.encode(page, "UTF-8")
            val api = playerUrl.substringBefore("?permalink=") + "?permalink=" + permalink
            val hdrs = mapOf(
                "User-Agent" to UA,
                "apikey" to API_KEY,
                "Authorization" to token,
                "drm" to "widevine",
                "X-Device-Name" to "PC - Windows Firefox",
                "Referer" to "https://$host/",
                "Origin" to "https://$host",
                "Accept" to "application/json"
            )
            val apiReqBuilder = okhttp3.Request.Builder().url(api)
            hdrs.forEach { (k, v) -> apiReqBuilder.header(k, v) }
            val json = JSONObject(client.newCall(apiReqBuilder.build()).execute().use { it.body?.string().orEmpty() })
            val player = json.optJSONObject("data")?.optJSONObject("player") ?: run {
                android.util.Log.e("RctiResolver", "no player object: ${json.optJSONObject("status")?.opt("message")}")
                return@withContext null
            }
            val stream = player.optString("url", "")
            val drm = player.optBoolean("drm", false)
            val licenseUrl = player.optString("drm_license_url", "")
            android.util.Log.d("RctiResolver", "resolved=$stream drm=$drm lic=${licenseUrl.take(60)}")
            if (!stream.startsWith("http")) return@withContext null
            Result(stream, drm, hdrs, licenseUrl, hdrs)
        } catch (e: Exception) {
            android.util.Log.e("RctiResolver", "resolve failed: ${e.message}")
            null
        }
    }

    private fun fetch(url: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
