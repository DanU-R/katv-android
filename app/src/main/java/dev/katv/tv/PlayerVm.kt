package dev.katv.tv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.katv.tv.data.ChannelCard
import dev.katv.tv.data.parseHeaders
import dev.katv.tv.data.streamHeaders
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

import kotlinx.coroutines.launch
import dev.katv.tv.data.RctiResolver
import dev.katv.tv.data.ChannelDto

private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:138.0) Gecko/20100101 Firefox/138.0"

private fun drmUuidFor(jenis: String) = when (jenis.lowercase(Locale.ROOT)) {
    "dash-widevine", "drm_rcti_plus" -> C.WIDEVINE_UUID
    "dash-clearkey" -> C.CLEARKEY_UUID
    else -> null
}

/**
 * SATU ExoPlayer untuk seluruh activity (hero + fullscreen).
 *
 * Player tidak pernah dilepas saat pindah layar: yang berubah cuma PlayerView
 * yang dipasang. Header channel disuntik lewat interceptor yang membaca referensi
 * mutable, jadi ganti channel = ganti MediaItem pada player yang SAMA (tanpa
 * bikin player baru / fetch ulang saat buka fullscreen).
 */
class PlayerVm : ViewModel() {

    var player: ExoPlayer? = null
        private set
    var playingUrl: String = ""
        private set

    // Selection state ikut VM supaya balik dari fullscreen tidak reset ke channel 01.
    var tab: Int = 0
    var tabCode: String = "EV"
    var index: Int = 0
    var selectedIndex: Int = 0
    var muted: Boolean = false
    var quality: Int = 0

    /** Dipasang composable yang sedang aktif; listener player cuma satu. */
    var onError: ((PlaybackException) -> Unit)? = null

    /** Activity dipakai untuk FLAG_KEEP_SCREEN_ON saat video playing. */
    private var activityRef: android.app.Activity? = null

    private val headersRef = AtomicReference<Map<String, String>>(emptyMap())
    private val uaRef = AtomicReference(DEFAULT_UA)
    /** JSON lisensi ClearKey untuk item saat ini (null = pakai DRM default/network). */
    private val clearkeyRef = AtomicReference<String?>(null)
    private var dataSourceFactory: OkHttpDataSource.Factory? = null

    val qualityLabels = listOf("AUTO", "1080p", "720p", "480p")
    val qualityHeights = listOf(Int.MAX_VALUE, 1080, 720, 480)

    fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                headersRef.get().forEach { (key, value) -> request.header(key, value) }
                chain.proceed(request.build())
            }
            .build()
        val factory = OkHttpDataSource.Factory(client).setUserAgent(uaRef.get())
        dataSourceFactory = factory
        val drmProvider = androidx.media3.exoplayer.drm.DrmSessionManagerProvider { mediaItem ->
            val ck = clearkeyRef.get()
            if (ck != null) {
                androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(
                        C.CLEARKEY_UUID,
                        androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER
                    )
                    .setPlayClearSamplesWithoutKeys(true)
                    .build(
                        androidx.media3.exoplayer.drm.LocalMediaDrmCallback(
                            ck.toByteArray(Charsets.UTF_8)
                        )
                    )
            } else {
                androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider().get(mediaItem)
            }
        }
        val instance = ExoPlayer.Builder(app)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(app)
                    .setDataSourceFactory(factory)
                    .setDrmSessionManagerProvider(drmProvider)
            )
            .build()
        instance.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke(error)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Selama video playing, layar TIDAK boleh mati (FLAG_KEEP_SCREEN_ON).
                activityRef?.window?.let { w ->
                    if (isPlaying) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        })
        player = instance
        return instance
    }

    // Cache hasil resolver RCTI+ (URL player -> stream asli + headers)
    private val rctiCache = mutableMapOf<String, RctiResolver.Result>()
    /** Card asli dari deep-link media_url — dipakai PlayerScreen, bukan cards[0] grid. */
    var deeplinkCard: ChannelCard? = null

    /** Deep-link media_url aktif: PlayerScreen jangan pernah auto-play dari grid. */
    var deeplinkMode = false

    /** Deep-link sedang me-resolve: PlayerScreen harus nahan auto-play sampai selesai. */
    val resolvingDeeplinkState = androidx.compose.runtime.mutableStateOf(false)
    var resolvingDeeplink: Boolean
        get() = resolvingDeeplinkState.value
        set(value) { resolvingDeeplinkState.value = value }

    /** Daftar server alternatif channel aktif (dari field "servers") + posisinya. */
    private var serverList = listOf<String>()
    private var serverIdx = -1
    private var currentCard: ChannelCard? = null

    /** Coba server berikutnya untuk channel yang sama. @return true kalau ada server baru diputar. */
    /** Server yang sudah dicoba & gagal — cegah mencoba server yang sama berulang. */
    private val triedServers = mutableSetOf<String>()

    /** Reset daftar server yang sudah dicoba — dipakai tombol RETRY manual. */
    fun resetServersForRetry() {
        triedServers.clear()
        serverIdx = -1
    }

    /** Coba server berikutnya untuk channel yang sama. @return true kalau ada server baru diputar. */
    fun advanceServer(context: Context): Boolean {
        if (serverList.size < 2) return false
        // Kalau serverIdx=-1 (belum pernah main), mulai dari 0 — bukan 1.
        val startFrom = if (serverIdx < 0) 0 else serverIdx
        for (step in 1..serverList.size) {
            val idx = (startFrom + step) % serverList.size
            val url = serverList[idx]
            if (url.isBlank() || url in triedServers) continue
            serverIdx = idx
            val card = currentCard ?: return false
            triedServers.add(url)
            val next = card.copy(channel = card.channel.copy(
                hls = url,
                jenis = if (url.contains(".m3u8", true)) "hls" else "dash-widevine"
            ))
            // PENTING: update currentCard supaya percobaan berikutnya memakai URL server
            // yang baru (kalau tidak, currentCard tetap URL lama dan failover mengulang).
            currentCard = next
            android.util.Log.d("KaTvFailover", "try server $idx/${serverList.size}: ${url.take(90)}")
            playResolved(context, next, streamHeaders(next.channel), force = true)
            return true
        }
        // Semua server sudah dicoba → reset supaya RETRY manual bisa mengulang.
        triedServers.clear()
        return false
    }

    /** Ganti channel hanya kalau URL beda. Fullscreen = no-op → instan. */
    fun play(context: Context, card: ChannelCard, force: Boolean = false) {
        // Simpan server alternatif untuk failover (dedup URL, kecuali URL utama).
        // Reset daftar "sudah dicoba" HANYA kalau ini channel yang benar-benar berbeda —
        // kalau direset tiap play(), failover tidak pernah selesai dan bisa loop selamanya.
        val isNewChannel = card.channel.id != currentCard?.channel?.id
        if (isNewChannel) triedServers.clear()
        currentCard = card
        val urls = mutableListOf<String>()
        try {
            val arr = org.json.JSONArray(card.channel.servers.ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val u = arr.getJSONObject(i).optString("url", "")
                if (u.isNotBlank() && u !in urls) urls.add(u)
            }
        } catch (_: Exception) {}
        if (card.channel.hls.isNotBlank() && card.channel.hls !in urls) urls.add(0, card.channel.hls)
        serverList = urls
        serverIdx = urls.indexOf(card.channel.hls).let { if (it >= 0) it else -1 }
        triedServers.clear()
        // RCTI+ URL-nya bukan media — resolve dulu ke .m3u8/.mpd asli via visitorsToken.
        if (RctiResolver.isRctiPlayer(card.channel.hls)) {
            android.util.Log.d("KaTvDeep", "rcti branch entered: ${card.channel.hls.take(80)}")
            val cached = rctiCache[card.channel.hls]
            if (cached != null) {
                playResolved(context, applyRcti(card, cached), cached.headers, force)
                resolvingDeeplink = false
                return
            }
            viewModelScope.launch {
                val r = RctiResolver.resolve(card.channel.hls)
                if (r != null) {
                    rctiCache[card.channel.hls] = r
                    playResolved(context, applyRcti(card, r), r.headers, force)
                } else {
                    playResolved(context, card, streamHeaders(card.channel), force) // fallback: tampilkan error
                }
                resolvingDeeplink = false
            }
            return
        }
        playResolved(context, card, streamHeaders(card.channel), force)
        resolvingDeeplink = false
    }

    private fun applyRcti(card: ChannelCard, r: RctiResolver.Result): ChannelCard {
        return card.copy(channel = card.channel.copy(
            hls = r.url,
            jenis = if (r.drm) "dash-widevine" else "hls",
            urlLicense = r.licenseUrl,
            headerLicense = r.licenseHeaders.entries.joinToString(",") { "${it.key}: ${it.value}" }
        ))
    }

    private fun playResolved(context: Context, card: ChannelCard, resolvedHeaders: Map<String, String>, force: Boolean) {
        activityRef = context as? android.app.Activity
        val instance = ensurePlayer(context)
        val url = card.channel.hls
        val headers = resolvedHeaders.toMutableMap()
        headersRef.set(headers)
        val ua = headers["User-Agent"]
        if (!ua.isNullOrBlank()) {
            uaRef.set(ua)
            dataSourceFactory?.setUserAgent(ua)
        }
        instance.volume = if (muted) 0f else 1f
        applyQuality(instance)
        // SAMA persis & masih siap: jangan stop/prepare ulang (itu yang bikin kedip
        // + "Memuat channel…" tiap buka fullscreen / retry). Cukup lanjutkan.
        if (!force && playingUrl == url &&
            (instance.playbackState == Player.STATE_READY ||
             instance.playbackState == Player.STATE_BUFFERING)
        ) {
            instance.playWhenReady = true
            if (!instance.isPlaying) instance.play()
            activityRef?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        playingUrl = url
        // setMediaItem sudah otomatis stop+prepare di ExoPlayer — panggil stop() manual
        // di sini bikin transisi ke likedediam sesaat (double stop). Hapus.
        instance.setMediaItem(mediaItem(card))
        instance.prepare()
        instance.playWhenReady = true
        activityRef?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun applyQuality() { player?.let { applyQuality(it) } }

    /** Force-highest + semua subtitle (text track) dimatikan. */
    fun applyQuality(instance: ExoPlayer) {
        val builder = instance.trackSelectionParameters.buildUpon()
            .setForceHighestSupportedBitrate(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        val choice = quality.coerceIn(qualityHeights.indices)
        if (choice == 0) builder.clearVideoSizeConstraints()
        else builder.setMaxVideoSize(Int.MAX_VALUE, qualityHeights[choice])
        instance.trackSelectionParameters = builder.build()
        logTrackState(instance, "applyQuality q=$choice")
    }

    /**
     * Diagnostik: laporkan track yang benar-benar dipilih player.
     * Dipakai untuk membuktikan (a) subtitle mati, (b) resolusi yang aktif.
     * Ditulis ke file di external files dir KARENA logcat Redroid tidak bisa
     * diandalkan (buffer 256 KiB penuh oleh spam lowmemorykiller → log hilang).
     */
    fun logTrackState(instance: ExoPlayer, where: String) {
        try {
            val groups = instance.currentTracks.groups
            val video = StringBuilder()
            val text = StringBuilder()
            for (g in groups) {
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    val sel = if (g.isTrackSelected(i)) "*" else ""
                    when (g.type) {
                        C.TRACK_TYPE_VIDEO -> video.append("${f.width}x${f.height}$sel ")
                        C.TRACK_TYPE_TEXT -> text.append("${f.language ?: "?"}$sel ")
                    }
                }
            }
            val anyTextSelected = groups.any { g ->
                g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.isTrackSelected(it) }
            }
            val line = "$where | video=[$video] text=[$text] textSelected=$anyTextSelected " +
                "vol=${instance.volume} muted=${instance.volume == 0f} " +
                "state=${instance.playbackState} playing=${instance.isPlaying}"
            android.util.Log.d("KaTvTracks", line)
            diag("TRACKS $line")
        } catch (e: Exception) {
            android.util.Log.d("KaTvTracks", "$where | log failed: ${e.message}")
        }
    }

    /** Tulis baris diagnostik ke file yang bisa dibaca dari host/adb. */
    fun diag(line: String) {
        try {
            val dir = diagDir ?: return
            val f = java.io.File(dir, "diag.log")
            if (f.exists() && f.length() > 400_000) f.delete()
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("$ts $line\n")
        } catch (_: Exception) {
        }
    }

    /** Dipanggil Activity/Composable sekali untuk menunjuk folder diagnostik. */
    fun bindDiagDir(context: Context) {
        diagDir = context.getExternalFilesDir(null)?.absolutePath
        diag("=== diag start dir=$diagDir ===")
    }

    private var diagDir: String? = null

    /** MediaItem dengan MIME hint + DRM bila perlu; subtitle TIDAK didaftarkan. */
    private fun mediaItem(card: ChannelCard): MediaItem {
        val channel = card.channel
        android.util.Log.d("KaTvDeep", "mediaItem uri=${channel.hls.take(70)} jenis=${channel.jenis} lic=${channel.urlLicense.take(30)}")
        val builder = MediaItem.Builder()
            .setUri(channel.hls)
            .setMediaId(channel.id)
        if (channel.jenis.startsWith("dash", ignoreCase = true) ||
            channel.jenis.equals("drm_rcti_plus", ignoreCase = true)
        ) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD)
        } else if (channel.hls.contains(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        val licenseUrl = channel.urlLicense.trim()
        val uuid = drmUuidFor(channel.jenis)
        // ClearKey DRM dari SamuraiTV — HANYA untuk stream DASH/cenc yang benar-benar
        // terenkripsi. Kalau dipaksa ke HLS biasa, ExoPlayer menganggap konten DRM dan
        // gagal buka (error "Source error" / layar hitam).
        val isDash = channel.jenis.startsWith("dash", true) || channel.hls.contains(".mpd", true) ||
            channel.hls.contains("/cenc", true)
        val ckObj = try {
            org.json.JSONObject(channel.clearkeys.ifBlank { "{}" })
        } catch (_: Exception) { org.json.JSONObject() }
        val hasKeys = isDash && ckObj.length() > 0
        if (hasKeys) {
            val keys = org.json.JSONArray()
            val it = ckObj.keys()
            while (it.hasNext()) {
                val kid = it.next()
                val key = ckObj.getString(kid)
                if (kid.length == 32 && key.length == 32) {
                    val ko = org.json.JSONObject()
                    ko.put("kty", "oct")
                    ko.put("k", b64url(hexBytes(key)))
                    ko.put("kid", b64url(hexBytes(kid)))
                    keys.put(ko)
                }
            }
            if (keys.length() > 0) {
                val lic = org.json.JSONObject()
                lic.put("keys", keys)
                lic.put("type", "temporary")
                clearkeyRef.set(lic.toString())
                builder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                        // single-session: setMultiSession(true) untuk ClearKey lokal justru
                        // membuat ExoPlayer meminta sesi tambahan yang tidak ada.
                        .setMultiSession(false)
                        .build()
                )
            } else clearkeyRef.set(null)
        } else clearkeyRef.set(null)
        if (!hasKeys && licenseUrl.startsWith("http", ignoreCase = true) && uuid != null) {
            builder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(uuid)
                    .setLicenseUri(licenseUrl)
                    .setLicenseRequestHeaders(parseHeaders(channel.headerLicense))
                    .setPlayClearContentWithoutKey(true)
                    .build()
            )
        }
        return builder.build()
    }

    private fun hexBytes(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    /** Base64 URL-safe tanpa padding (format kunci ClearKey / EME). */
    private fun b64url(b: ByteArray): String = android.util.Base64.encodeToString(
        b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
    )

    override fun onCleared() {
        player?.release()
        player = null
        super.onCleared()
    }
}
