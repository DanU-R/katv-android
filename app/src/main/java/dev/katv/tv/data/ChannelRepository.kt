package dev.katv.tv.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Raw channel row — every field is a string. */
@Serializable
data class ChannelDto(
    val region: String = "",
    @SerialName("region_name") val regionName: String = "",
    val id: String = "",
    val name: String = "",
    val tagline: String = "",
    val hls: String = "",
    val logo: String = "",
    val jenis: String = "",
    @SerialName("is_live") val isLive: String = "",
    @SerialName("is_movie") val isMovie: String = "",
    val premium: String = "",
    val subtitle: String = "",
    val namespace: String = "",
    @SerialName("alpha_2_code") val alpha2: String = "",
    @SerialName("country_name") val countryName: String = "",
    @SerialName("t_stamp") val tStamp: String = "",
    @SerialName("s_stamp") val sStamp: String = "",
    @SerialName("header_iptv") val headerIptv: String = "",
    @SerialName("header_license") val headerLicense: String = "",
    @SerialName("url_license") val urlLicense: String = "",
    @SerialName("fake_event") val fakeEvent: String = "",
    @SerialName("servers") val servers: String = "",  // JSON [{name,url}, ...] — semua server/multi-stream event
    @SerialName("clearkeys") val clearkeys: String = ""  // JSON {kid:key} — ClearKey DRM dari player page
)

/** Region/kategori — dipakai sebagai tab dinamis. */
@Serializable
data class TabInfo(
    val code: String = "",
    val name: String = "",
    val kind: String = ""
)

/** A card in the 3-column grid + the hero it drives. */
data class ChannelCard(
    val number: String,          // "01" … "06" — fake editorial channel number
    val channelName: String,     // bold card name (real channel name)
    val programTitle: String,    // event name, e.g. "International Friendly [CH7]"
    val matchInfo: String,       // teams/arena from tagline, e.g. "Argentina vs Costa Rica"
    val subtitle: String,        // mono program metadata line (jam WIB + tanggal)
    val logoUrl: String,
    val heroImageUrl: String,
    val progress: Float,         // 0.72 … exact % from design reference
    val isLive: Boolean,
    val eventStatus: String = "",// "LIVE" | "UPCOMING" | "SELESAI" | "" (non-event)
    val channel: ChannelDto
)

object ChannelRepository {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    /** Decode 1.3MB JSON cukup sekali per process — balik dari player & background
     *  feed refresh gak boleh nge-freeze UI dengan decode ulang. */
    @Volatile private var allCache: List<ChannelDto>? = null

    /** Dipanggil setelah feed refresh supaya grid rebuild dari data segar. */
    fun clearCache() { allCache = null }

    private fun readSource(context: Context, name: String): String {
        val cached = java.io.File(context.filesDir, name)
        return when {
            cached.exists() && cached.length() > 50 -> cached.readText()
            else -> context.assets.open(name).bufferedReader().use { it.readText() }
        }
    }

    /** Tab dinamis: Events -> kategori (urutan numeric) -> negara. */
    fun loadTabs(context: Context): List<TabInfo> {
        val raw = readSource(context, "tabs.json")
        return json.decodeFromString<List<TabInfo>>(raw)
    }

    /** All 1177 rows, preserved in asset order. Decode once, reuse from memory. */
    fun loadAll(context: Context): List<ChannelDto> {
        allCache?.let { return it }
        val raw = readSource(context, "channels.json")
        val decoded = json.decodeFromString<List<ChannelDto>>(raw)
        allCache = decoded
        return decoded
    }

    /**
     * Real data only: program title = channel name, subtitle = tagline,
     * hero artwork = channel logo. No mockup placeholders.
     */
    /**
     * 6 channels for a given tab. ALL = first 6 live events (mockup default).
     * Other tabs pick 6 real channels whose region/name matches the category.
     */
    fun loadGrid(context: Context, tabCode: String = "EV"): List<ChannelCard> {
        val all = loadAll(context)
        fun playable(c: ChannelDto) =
            c.jenis.equals("hls", true) || c.jenis.equals("ts", true)

        // EVENTS (EV): jadwal DINAMIS dari payload EV.json yang di-fetch FeedRepository.
        // JANGAN fallback ke C5/SP — itu workaround lama yang membuang jadwal asli
        // ("Tim A vs Tim B jam sekian") dan menggantinya channel olahraga acak.
        // Event tetap ditampilkan walau DRM/non-playable: informasinya tetap berguna,
        // beda dengan channel biasa yang memang harus playable.
        if (tabCode.equals("EV", true)) {
            val nowSec = System.currentTimeMillis() / 1000
            // Event tab = OLAHRAGA saja. Buang 24/7 non-sport (kartun, COWS, dll).
            // Caranya: 24/7 dipertahankan HANYA kalau namanya sport; event "A vs B"
            // atau bertema pertandingan dianggap sport (judul tidak selalu memuat kata olahraga).
            val sportWords = listOf(
                "nfl","nba","nhl","mlb","soccer","football","basketball","tennis","golf",
                "rugby","cricket","motogp","formula","ufc","boxing","sports","league",
                "liga","premier","serie a","laliga","bundesliga","champions","cup",
                "fc ","cf ","if ","ifk ","bk ","sports racing","fox footy","rally",
                // tambahan: event non-sepakbola yang sering muncul di Samurai
                "badminton","volleyball","basket","f1","motorsport","athletics","swimming",
                "wrestling","mma","esport","darts","snooker","bowling","cycling"
            )
            fun isSport(ch: ChannelDto): Boolean {
                val n = ch.name.lowercase()
                val tag = ch.tagline.lowercase()
                if (sportWords.any { n.contains(it) || tag.contains(it) }) return true
                // format pertandingan "X vs Y" / "X at Y" → olahraga
                if (n.contains(" vs ") || n.contains(" vs. ") || n.contains(" at ")) return true
                // Event berjadwal yang punya tagline "<liga> | <nama>" juga dianggap sport.
                if (ch.tStamp.toLongOrNull()?.let { it > 0 } == true) return true
                return false
            }
            val events = all.filter { it.region.equals("EV", true) }
                .filter { isSport(it) }
                .filter { ch ->
                    val t = ch.tStamp.toLongOrNull() ?: 0L
                    // Buang event tanpa jadwal (t=0) kecuali benar-benar always_live
                    // dan buang event yang sudah lewat > 2 hari (asset basi Januari).
                    when {
                        t <= 0L -> ch.tagline.contains("LIVE 24/7", true)
                        ch.tagline.trim().startsWith("LIVE 24/7", true) -> true
                        else -> {
                            val s = ch.sStamp.toLongOrNull() ?: 0L
                            // s kosong/0 → jangan buang! Pakai t_stamp sebagai acuan
                            // (anggap durasi 4 jam) supaya event valid tidak hilang hanya
                            // karena sumber tidak mengirim s_stamp.
                            if (s <= 0L) t >= nowSec - 12 * 3600
                            else s >= nowSec - 6 * 3600
                        }
                    }
                }
                .distinctBy { it.id }
                .sortedWith(compareBy<ChannelDto> { ch ->
                    val t = ch.tStamp.toLongOrNull() ?: 0L
                    val s = ch.sStamp.toLongOrNull() ?: 0L
                    val always = t <= 0L || ch.tagline.trim().startsWith("LIVE 24/7", true)
                    val live = !always && t > 0 && s > 0 && t <= nowSec && nowSec < s
                    when {
                        always -> 0L          // 24/7 paling stabil → depan
                        live -> 1L            // event sedang LIVE
                        else -> 2L            // UPCOMING / baru selesai
                    }
                }.thenBy { ch ->
                    val t = ch.tStamp.toLongOrNull() ?: 0L
                    if (t <= 0L) 0L else t   // dalam grup, urut berdasarkan waktu
                }.thenBy { ch ->
                    // Prioritas 24/7: NFL Network dulu (paling stabil & populer), baru sisanya.
                    val n = ch.name.lowercase()
                    when {
                        n.contains("nfl network") -> 0
                        n.contains("basketball") || n.contains("nba") -> 1
                        n.contains("footy") || n.contains("football") -> 2
                        n.contains("cricket") -> 3
                        n.contains("golf") -> 4
                        n.contains("tennis") -> 5
                        else -> 6
                    }
                }.thenBy { it.name })
            return events.mapIndexed { index, ch ->
                val (teams, whenWib, status) = parseEventTagline(ch.tagline, ch.tStamp, ch.sStamp)
                ChannelCard(
                    number = "%02d".format(index + 1),
                    channelName = teams.ifBlank { ch.name },
                    programTitle = ch.name,
                    matchInfo = teams,
                    subtitle = whenWib,
                    logoUrl = ch.logo,
                    heroImageUrl = ch.logo,
                    progress = progressOf(ch.tStamp, ch.sStamp),
                    isLive = status == "LIVE",
                    eventStatus = status,
                    channel = ch
                )
            }
        }

        var pool = all.filter { it.region.equals(tabCode, true) }
        // Fallback: tab EV bisa kosong kalau semua event sudah lewat / feed belum sync.
        // Jangan biarkan user dapat layar kosong — ambil channel playable dari region lain.
        if (pool.isEmpty() && tabCode.equals("EV", true)) {
            pool = all.filter { !it.fakeEvent.equals("t", true) && playable(it) }
        }
        // Ranking: channel yang BISA diputar & asli (fake_event=f) di depan.
        // Buang DRM (dash-clearkey/widevine/drm_rcti_plus) — player gak support & bikin hitam.
        val asli = pool.filter { !it.fakeEvent.equals("t", true) && playable(it) }
        val other = pool.filter { !playable(it) }
        val ordered = asli + other
        // Dedup by id for ALL tabs (not only EV fallback). Tanpa ini, duplicate channel
        // di pool region bikin cards.indexOf(current) di Home mengembalikan index pertama,
        // bukan yang ditap user → fullscreen memutar channel lain yang tampak mirip.
        // Grid home & fullscreen jadi identik dan index-stable.
        // EV: tampilkan SEMUA event (bukan 40) — event hari ini bisa >40 dan user perlu
        // lihat semuanya. Tab channel biasa tetap dibatasi 40 demi performa grid.
        val limit = if (tabCode.equals("EV", true)) 200 else 40
        val picked = ordered.distinctBy { it.id }.take(limit)
        return picked.mapIndexed { index, ch ->
            ChannelCard(
                number = "%02d".format(index + 1),
                channelName = ch.name,
                programTitle = ch.name,
                matchInfo = matchName(ch.tagline, ""),
                subtitle = airTime(ch.tStamp),
                logoUrl = ch.logo,
                heroImageUrl = ch.logo,
                progress = progressOf(ch.tStamp, ch.sStamp),
                isLive = true,
                channel = ch
            )
        }
    }

    /**
     * Parse tagline event DINAMIS: "18 Nov 06:00 WIB - Argentina vs Costa Rica"
     * -> (teams="Argentina vs Costa Rica", when="18 Nov 06:00 WIB", status dari clock).
     * Kalau format berubah (source update), tetap ambil bagian setelah " - " sebagai
     * teams dan bagian sebelum nya sebagai waktu — ga hardcode tim/jam tertentu.
     */
    private fun parseEventTagline(
        tagline: String,
        tStamp: String,
        sStamp: String
    ): Triple<String, String, String> {
        val raw = tagline.trim()
        val parts = raw.split(" - ", limit = 2)
        val whenText = parts[0].trim()
        val teams = if (parts.size > 1) parts[1].trim() else raw
        val status = eventStatusOf(tStamp, sStamp)
        // whenText sudah jadi "23 Nov 01:00 WIB" dari FeedRepository — pakai apa adanya.
        // Kalau masih format panjang "2026-11-22 18:15 WIB", rapikan ke "22 Nov 18:15 WIB".
        val displayWhen = when {
            whenText.startsWith("LIVE", true) -> whenText
            whenText.matches(Regex("\\d{4}-\\d{2}-\\d{2} .*")) -> try {
                val fmtIn = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'WIB'", java.util.Locale("id", "ID"))
                fmtIn.timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
                java.text.SimpleDateFormat("d MMM HH:mm 'WIB'", java.util.Locale("id", "ID"))
                    .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta") }
                    .format(fmtIn.parse(whenText))
            } catch (e: Exception) { whenText }
            else -> whenText.ifBlank { airTime(tStamp) }
        }
        return Triple(teams, displayWhen, status)
    }

    /** Status event dari WAKTU SEKARANG (bukan flag statis source). */
    private fun eventStatusOf(start: String, end: String): String {
        val s = start.toLongOrNull() ?: return "LIVE"
        val e = end.toLongOrNull() ?: return "LIVE"
        val now = System.currentTimeMillis() / 1000
        return when {
            now < s -> "UPCOMING"
            now > e -> "SELESAI"
            else -> "LIVE"
        }
    }

    /**
     * Nama pertandingan / arena dari tagline: "18 Nov 06:00 WIB - Argentina vs Costa Rica"
     * -> "Argentina vs Costa Rica". Fallback ke nama channel bila tak ada pemisah.
     */
    private fun matchName(tagline: String, fallback: String): String {
        val parts = tagline.split(" - ", limit = 2)
        val match = if (parts.size > 1) parts[1].trim() else ""
        return match.ifBlank { fallback }
    }

    /** Jam mulai tayang (HH:MM WIB) dari t_stamp asli — dipakai di subtitle hero. */
    private fun airTime(start: String): String {
        val s = start.toLongOrNull() ?: return "LIVE"
        if (s <= 0L) return "LIVE 24/7"
        return try {
            java.text.SimpleDateFormat("HH:mm 'WIB'", java.util.Locale("id", "ID"))
                .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta") }
                .format(java.util.Date(s * 1000))
        } catch (e: Exception) { "LIVE" }
    }

    private fun fraction(start: String, end: String): Float {
        val s = start.toLongOrNull() ?: return 0.65f
        val e = end.toLongOrNull() ?: return 0.65f
        if (e <= s) return 0.65f
        val now = System.currentTimeMillis() / 1000f
        return when {
            now < s -> 0.05f
            now > e -> 0.65f
            else -> ((now - s) / (e - s)).coerceIn(0.05f, 0.95f)
        }
    }

    private fun progressOf(start: String, end: String): Float = fraction(start, end)
}

/** Parse header_iptv / header_license JSON strings into a case-insensitive header map. */
fun parseHeaders(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        val obj = JSONObject(raw)
        val out = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.optString(key, "")
            // "none" means: omit this header entirely (contract from README).
            if (value.isNotBlank() && !value.equals("none", ignoreCase = true)) {
                out[key] = value
            }
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }
}

/** Pick the headers Media3 should apply to the stream request. */
fun streamHeaders(dto: ChannelDto): Map<String, String> {
    val headers = parseHeaders(dto.headerIptv).toMutableMap()
    // Normalize the three names the player cares about, preserving all others.
    fun take(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
    val ua = take("User-Agent")
    val ref = take("Referer", "Referrer")
    val origin = take("Origin")
    val result = mutableMapOf<String, String>()
    ua?.let { result["User-Agent"] = it }
    ref?.let { result["Referer"] = it }
    origin?.let { result["Origin"] = it }
    // Keep any extra headers the scraper collected (Host, cookies, etc.).
    headers.forEach { (k, v) -> if (k !in setOf("User-Agent", "Referer", "Referrer", "Origin")) result[k] = v }
    return result
}
