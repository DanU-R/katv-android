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
        val instance = ExoPlayer.Builder(app)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(app).setDataSourceFactory(factory)
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
    fun advanceServer(context: Context): Boolean {
        if (serverList.size < 2) return false
        for (step in 1..serverList.size) {
            val idx = (serverIdx + step) % serverList.size
            val url = serverList[idx]
            if (url == playingUrl && !url.isBlank()) continue
            serverIdx = idx
            currentCard?.let { card ->
                val next = card.copy(channel = card.channel.copy(
                    hls = url,
                    jenis = if (url.contains(".m3u8", true)) "hls" else "dash-widevine"
                ))
                android.util.Log.d("KaTvFailover", "try server $idx/${serverList.size}: ${url.take(90)}")
                playResolved(context, next, streamHeaders(next.channel), force = true)
                return true
            }
        }
        return false
    }

    /** Ganti channel hanya kalau URL beda. Fullscreen = no-op → instan. */
    fun play(context: Context, card: ChannelCard, force: Boolean = false) {
        // Simpan server alternatif untuk failover (dedup URL, kecuali URL utama).
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
    }

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
        if (licenseUrl.startsWith("http", ignoreCase = true) && uuid != null) {
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

    override fun onCleared() {
        player?.release()
        player = null
        super.onCleared()
    }
}
