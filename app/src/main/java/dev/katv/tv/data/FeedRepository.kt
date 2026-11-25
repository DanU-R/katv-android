package dev.katv.tv.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Feed terbaru didekrip LANGSUNG DI DEVICE dari payload sumber — tanpa VM, tanpa Frida, tanpa cron.
 *
 * Chain (reverse-engineered dari libffmpeg.so + x8.a1.m):
 *   A1.m(raw) = base64decode(raw[::-1])[::-1]  (brute-force skip 0..8 utk padding)
 *   -> cari run base64 panjang (>=500 char) = ciphertext
 *   -> AES-128-CBC decrypt (KEY/IV statis) -> 16 byte garbage + JSON + PKCS#7
 */
object FeedRepository {
    /** Sync cukup sekali per app process — jangan ulang tiap balik dari fullscreen. */
    @Volatile
    private var synced = false

    /** Reset hanya dipakai saat pull-to-refresh manual. */
    fun resetSync() { synced = false }
    fun isSynced(): Boolean = synced
    private const val SRC = "https://raw.githubusercontent.com/movietrailersxxi-pixel/newalpha/main/dt/v218_alpha/%s.json"
    // Source alternatif untuk EV: repo duktek2 punya lebih banyak event (18 vs 1).
    // Tetap dinamis — di-fetch tiap sync, bukan hardcode.
    private const val SRC_EV_ALT = "https://raw.githubusercontent.com/movietrailersxxi-pixel/duktek2/main/dt/v218_alpha/EV.json"
    // Source jadwal DINAMIS (data segar, tanpa enkripsi): Nobaryu/esportex.
    // API ini di-update tiap ~200 detik & punya event hari ini (22 Nov dst).
    private const val EVENTS_API = "https://api.esportex.site/api/streams"
    // Source PPW (api.ppv.st) — 176 event, 11 kategori, starts_at/ends_at unix epoch.
    private const val PPW_API = "https://api.ppv.st/api/streams"
    private val KEY = hex("99ca59b508fda1da71e41bb78f1e7bca")
    private val IV = hex("c6611a2a4f843de1c65e32eab81b325f")
    private val REGIONS = listOf(
        "EV","ID","SP","AR","AU","BE","BR","CA","CN","KD","FR","IN","IT","JP","KR","KW",
        "MY","MX","PH","PT","RU","SA","SG","SK","VN","TH","GB","US","UY","RI","LO"
    ) + (0..9).map { "C$it" }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    private fun b64decode(s: String): ByteArray? = try {
        Base64.decode(s.trim(), Base64.DEFAULT)
    } catch (e: Exception) { null }

    /** A1.m(raw[skip:]) dengan brute-force skip. */
    private fun a1m(raw: String): String? {
        val r = raw.trim()
        for (skip in 0..8) {
            try {
                val sub = r.substring(skip)
                val dec = b64decode(sub.reversed()) ?: continue
                val s = String(dec, Charsets.ISO_8859_1).reversed()
                if (s.length > 100) return s
            } catch (e: Exception) { /* coba skip berikutnya */ }
        }
        return null
    }

    private fun decryptBlock(ct: ByteArray): String? {
        if (ct.size < 32 || ct.size % 16 != 0) return null
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        val out = cipher.doFinal(ct)
        // 16 byte garbage di depan
        val body = out.copyOfRange(16, out.size)
        val probe = String(body, 0, minOf(120, body.size), Charsets.ISO_8859_1)
        val prefixOk = body.size >= 2 && body[0] == '{'.toByte() && body[1] == '"'.toByte()
        if (!prefixOk && !probe.contains("\"token\"")) return null
        // buang PKCS#7
        val pad = body.last().toInt() and 0xff
        val clean = if (pad in 1..16 && pad <= body.size && body.takeLast(pad).all { it == pad.toByte() })
            body.copyOfRange(0, body.size - pad) else body
        return String(clean, Charsets.UTF_8)
    }

    /** Dekrip satu payload region -> JSON string, atau null. */
    fun decryptPayload(raw: String): String? {
        val m = a1m(raw) ?: return null
        val regex = Regex("[A-Za-z0-9+/]{500,}")
        for (match in regex.findAll(m)) {
            val ct = b64decode(match.value) ?: continue
            val json = decryptBlock(ct) ?: continue
            if (json.contains("\"token\"")) return json
        }
        return null
    }

    private fun fetchUrl(url: String): String = URL(url).openConnection().let { c ->
        c.connectTimeout = 4000
        c.readTimeout = 6000
        c.getInputStream().bufferedReader().use { it.readText() }
    }
    private fun fetch(code: String): String = fetchUrl(SRC.format(code))

    /** "2026-11-22 18:15" (WIB/UTC+7) -> epoch detik. Dipakai biar status
     *  LIVE/UPCOMING/SELESAI dihitung dari clock dengan benar. */
    private fun kickoffToEpoch(s: String): String {
        if (s.isBlank()) return ""
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
            val ms = fmt.parse(s.trim())?.time ?: return ""
            (ms / 1000).toString()
        } catch (e: Exception) { "" }
    }

    /**
     * Tambahkan server alternatif SamuraiTV ke event SEGAR (PPW/EsportEx).
     * Cocokkan liga dari nama event; fallback = server beIN global (masih live).
     * Event Samurai-nya sendiri BASI dan sengaja tidak dipakai sebagai feed.
     */
    private var samuraiCache: Map<String, List<String>>? = null
    private fun samuraiServers(context: Context): Map<String, List<String>> {
        samuraiCache?.let { return it }
        val map = mutableMapOf<String, List<String>>()
        try {
            val raw = org.json.JSONObject(
                context.assets.open("samurai_servers.json").bufferedReader().use { it.readText() }
            )
            val byLeague = raw.optJSONObject("by_league")
            if (byLeague != null) {
                val keys = byLeague.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = byLeague.optJSONArray(k) ?: continue
                    val urls = (0 until arr.length()).map { arr.getString(it) }
                    if (urls.isNotEmpty()) map[k.lowercase()] = urls
                }
            }
        } catch (e: Exception) { /* asset gagal -> kosong */ }
        samuraiCache = map
        return map
    }

    /** Gabungkan server Samurai (match liga) ke array servers event yang sudah ada. */
    private fun augmentServers(context: Context, row: org.json.JSONObject, eventName: String) {
        try {
            val cur = org.json.JSONArray(row.optString("servers", "[]"))
            val have = HashSet<String>()
            for (i in 0 until cur.length()) have.add(cur.getJSONObject(i).optString("url", ""))
            val pool = samuraiServers(context)
            var added = 0
            for ((league, urls) in pool) {
                if (league.isBlank()) continue
                if (!eventName.lowercase().contains(league) && !league.contains(eventName.lowercase())) continue
                for (u in urls) {
                    if (u in have || u.isBlank()) continue
                    val o = org.json.JSONObject()
                    o.put("name", "Samurai")
                    o.put("url", u)
                    cur.put(o); have.add(u); added++
                    if (added >= 4) break
                }
                if (added >= 4) break
            }
            row.put("servers", cur.toString())
        } catch (e: Exception) { /* jangan gagalkan event */ }
    }

    /**
     * Bangun array JSON event dari API jadwal dinamis (PPW + EsportEx).
     * Dedup by nama+jam biar event yang sama gak dobel. Return null kalau dua-duanya gagal.
     */
    private fun buildEventsJson(context: Context): String? {
        val out = org.json.JSONArray()
        val seen = mutableSetOf<String>()
        fun add(row: org.json.JSONObject) {
            // Normalisasi agresif buat dedup: "Barcelona vs. Villarreal" ==
            // "Villarreal vs Barcelona" == "Barcelona vs Villarreal".
            var n = row.optString("name").lowercase()
            n = n.replace(Regex("[.,'-]"), " ").replace(Regex("\\s+"), " ").trim()
            val tm = n.split(Regex("\\s+vs\\.?\\s+|\\s+at\\s+")).map { it.trim() }
                .filter { it.isNotBlank() }.sorted()
            val key = tm.joinToString("|")
            val ts = row.optString("t_stamp")
            val full = if (tm.size >= 2) key + "|" + ts.substring(0, minOf(10, ts.length)) else n + "|" + ts
            if (full.isNotBlank() && !seen.add(full)) return
            out.put(row)
        }

        // --- 3. SamuraiTV: JANGAN dijadikan event feed. Situsnya statis & basi
        //     (HTML tidak berubah berhari-hari), jadi 69 event-nya hampir semua
        //     SELESAI — bakal memenuhi tab EV dengan kartu mati. Yang dipakai
        //     hanyalah SERVER-nya sebagai failover (lihat samurai_servers.json →
        //     PlayerVm.advanceServer lewat field "servers" pada event PPW/EsportEx).
        //     Contoh: beIN via cp11.adabmedia.com/hls2/bein1.m3u8 masih live. ---
        // --- 1. PPW (api.ppv.st): streams[].streams[] dengan starts_at/ends_at epoch ---
        try {
            val ppw = org.json.JSONObject(fetchUrl(PPW_API))
            val cats = ppw.optJSONArray("streams")
            if (cats != null) for (i in 0 until cats.length()) {
                val cat = cats.getJSONObject(i)
                val catName = cat.optString("category", "")
                val arr = cat.optJSONArray("streams") ?: continue
                for (j in 0 until arr.length()) {
                    val s = arr.getJSONObject(j)
                    val name = s.optString("name", "")
                    val tag = s.optString("tag", "")
                    if (name.isBlank()) continue
                    val start = s.optLong("starts_at", 0)
                    val end = s.optLong("ends_at", 0)
                    val isAlways = s.optInt("always_live", 0) == 1
                    // Buang event basi: starts_at lama (>7 hari) dan bukan always_live.
                    val nowSec = System.currentTimeMillis() / 1000
                    if (!isAlways && start > 0 && start < nowSec - 7 * 86400) continue
                    if (!isAlways && start == 0L && end > 0 && end < nowSec - 7 * 86400) continue
                    val poster = s.optString("poster", "")
                    val iframe = s.optString("iframe", "")
                    val srcTag = s.optString("source_tag", "")
                    // Semua server: substreams (jika ada) + stream utama.
                    val servers = org.json.JSONArray()
                    if (iframe.isNotBlank()) {
                        val o = org.json.JSONObject(); o.put("name", srcTag.ifBlank { "HD" }); o.put("url", iframe); servers.put(o)
                    }
                    val subs = s.optJSONArray("substreams")
                    if (subs != null) for (k in 0 until subs.length()) {
                        val sub = subs.getJSONObject(k)
                        val u = sub.optString("iframe", "")
                        if (u.isBlank() || u == iframe) continue
                        val o = org.json.JSONObject(); o.put("name", sub.optString("source_tag", "SD")); o.put("url", u); servers.put(o)
                    }
                    val tStr = if (start > 0 && !isAlways) (start).toString() else ""
                    val sStr = if (end > 0 && !isAlways) (end).toString() else ""
                    // tagline = "<jam WIB> - <nama>". always_live pakai label
                    // "LIVE 24/7" (starts_at mereka bisa basi/Januari tapi tetap 24/7).
                    val jam = when {
                        isAlways -> "LIVE 24/7"
                        start > 0 -> epochToWib(start)
                        else -> "LIVE"
                    }
                    val row = org.json.JSONObject()
                    row.put("region", "EV")
                    row.put("region_name", "Events")
                    row.put("id", "ppw_" + s.optInt("id", j) + "_" + i)
                    row.put("name", name)
                    row.put("tagline", "$jam - $name")
                    row.put("hls", iframe)
                    row.put("servers", servers.toString())
                    row.put("logo", poster)
                    row.put("jenis", "hls")
                    row.put("namespace", if (tag.isNotBlank()) tag else catName)
                    row.put("subtitle", srcTag)
                    row.put("t_stamp", tStr)
                    row.put("s_stamp", sStr)
                    augmentServers(context, row, name + " " + tag + " " + catName)
                    add(row)
                }
            }
        } catch (e: Exception) { /* PPW gagal -> coba esportex */ }

        // --- 2. EsportEx (api.esportex.site): per kategori, kickoff string WIB ---
        try {
            val api = org.json.JSONObject(fetchUrl(EVENTS_API))
            val cats = api.keys()
            while (cats.hasNext()) {
                val cat = cats.next()
                if (cat == "success" || cat == "timestamp" || cat == "READ_ME") continue
                val arr = api.optJSONArray(cat) ?: continue
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val tag = e.optString("tag", "")
                    val kickoff = e.optString("kickoff", "")
                    if (tag.isBlank() || kickoff.isBlank()) continue
                    val iframes = e.optJSONArray("iframes")
                    var stream = ""
                    val servers = org.json.JSONArray()
                    if (iframes != null) for (k in 0 until iframes.length()) {
                        val fr = iframes.getJSONObject(k)
                        val u = fr.optString("url", "")
                        if (u.isBlank()) continue
                        if (k == 0) stream = u
                        val o = org.json.JSONObject(); o.put("name", fr.optString("server", "Server ${k + 1}")); o.put("url", u); servers.put(o)
                    }
                    val row = org.json.JSONObject()
                    row.put("region", "EV")
                    row.put("region_name", "Events")
                    row.put("id", e.optString("slug", "esx_$i"))
                    row.put("name", tag)
                    row.put("tagline", "$kickoff WIB - $tag")
                    row.put("hls", stream)
                    row.put("servers", servers.toString())
                    row.put("logo", e.optString("poster", ""))
                    row.put("jenis", "hls")
                    row.put("namespace", e.optString("league", cat))
                    row.put("t_stamp", kickoffToEpoch(kickoff))
                    row.put("s_stamp", kickoffToEpoch(e.optString("endTime", "")))
                    augmentServers(context, row, tag + " " + e.optString("league", "") + " " + cat)
                    add(row)
                }
            }
        } catch (e: Exception) { /* esportex gagal */ }

        return if (out.length() > 0) out.toString() else null
    }

    /** epoch detik -> "22 Nov 21:00 WIB" */
    private fun epochToWib(epochSec: Long): String = try {
        java.text.SimpleDateFormat("d MMM HH:mm 'WIB'", java.util.Locale("id", "ID"))
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta") }
            .format(java.util.Date(epochSec * 1000))
    } catch (e: Exception) { "LIVE" }

    /**
     * Fetch + dekrip semua region, tulis channels.json ke cache app.
     * @return jumlah channel, atau -1 kalau gagal total.
     */
    suspend fun syncFeed(context: Context): Int = withContext(Dispatchers.IO) {
        if (synced) return@withContext -2  // sudah sync di process ini, skip
        val rows = StringBuilder("[")
        var count = 0
        val fields = arrayOf("region","region_name","id","name","tagline","hls","logo","jenis",
            "is_live","is_movie","premium","subtitle","namespace","alpha_2_code","country_name",
            "t_stamp","s_stamp","header_iptv","header_license","url_license","fake_event","servers")
        // EVENTS (EV): SamuraiTV + 2 API jadwal dinamis (PPW + EsportEx), dedup.
        // BitTV EV.json sudah basi -> dipakai hanya sbg fallback terakhir.
        val eventsJson = buildEventsJson(context)
        if (eventsJson != null) {
            val arr = org.json.JSONArray(eventsJson)
            for (i in 0 until arr.length()) {
                if (count > 0) rows.append(',')
                rows.append(arr.getJSONObject(i).toString())
                count++
            }
        } else {
            // Fallback: source BitTV lama (newalpha + duktek2)
            val payloads = mutableListOf<String>()
            decryptPayload(fetch("EV"))?.let { payloads.add(it) }
            try { decryptPayload(fetchUrl(SRC_EV_ALT))?.let { payloads.add(it) } } catch (e: Exception) {}
            val seenIds = mutableSetOf<String>()
            for (json in payloads) {
                val obj = org.json.JSONObject(json)
                val arr = obj.optJSONArray("info") ?: continue
                val regionName = obj.optString("country_name", "")
                for (i in 0 until arr.length()) {
                    val ch = arr.getJSONObject(i)
                    val chId = ch.optString("id", "")
                    if (chId.isNotBlank() && !seenIds.add(chId)) continue
                    if (count > 0) rows.append(',')
                    val sb = StringBuilder("{")
                    fields.forEachIndexed { fi, f ->
                        if (fi > 0) sb.append(',')
                        val v = when (f) {
                            "region" -> "EV"
                            "region_name" -> regionName
                            "logo" -> ch.optString("image", "")
                            else -> ch.optString(f, "")
                        }
                        sb.append(org.json.JSONObject.quote(f)).append(':')
                        sb.append(org.json.JSONObject.quote(v))
                    }
                    sb.append('}')
                    rows.append(sb)
                    count++
                }
            }
        }

        for (code in REGIONS) {
            if (code == "EV") continue  // EV sudah ditangani di atas
            try {
                val json = decryptPayload(fetch(code)) ?: continue
                val obj = org.json.JSONObject(json)
                val arr = obj.optJSONArray("info") ?: continue
                val regionName = obj.optString("country_name", "")
                for (i in 0 until arr.length()) {
                    val ch = arr.getJSONObject(i)
                    if (count > 0) rows.append(',')
                    val sb = StringBuilder("{")
                    fields.forEachIndexed { fi, f ->
                        if (fi > 0) sb.append(',')
                        val v = when (f) {
                            "region" -> code
                            "region_name" -> regionName
                            "logo" -> ch.optString("image", "")
                            else -> ch.optString(f, "")
                        }
                        sb.append(org.json.JSONObject.quote(f)).append(':')
                        sb.append(org.json.JSONObject.quote(v))
                    }
                    sb.append('}')
                    rows.append(sb)
                    count++
                }
            } catch (e: Exception) { /* region gagal -> lewati */ }
        }
        rows.append(']')
        if (count > 100) {
            File(context.filesDir, "channels.json").writeText(rows.toString())
            synced = true
            count
        } else -1
    }
}
