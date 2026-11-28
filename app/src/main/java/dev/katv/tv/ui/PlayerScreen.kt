package dev.katv.tv.ui

import android.content.pm.ActivityInfo
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dev.katv.tv.PlayerVm
import dev.katv.tv.data.ChannelRepository
import dev.katv.tv.ui.theme.KaTvColors
import dev.katv.tv.ui.theme.MonoFont
import dev.katv.tv.ui.theme.SansFont
import kotlinx.coroutines.delay

private val Vermilion = Color(KaTvColors.Vermilion)
private val Paper = Color(KaTvColors.Paper)
private val Quiet = Color(KaTvColors.Quiet)
private val Obsidian = Color(KaTvColors.Obsidian)

@Composable
fun PlayerScreen(playerVm: PlayerVm, channelIndex: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        // JANGAN paksa rotasi. Rotasi memicu recreate SurfaceView → surface baru attach ke
        // ExoPlayer yang masih playing → frame stall ("freeze") di banyak device.
        // Video tetap di-fit penuh (RESIZE_MODE_FIT) mengikuti orientasi apa pun.
        val win = activity?.window
        // Jangan biarkan konten masuk ke area system bar.
        if (win != null) WindowCompat.setDecorFitsSystemWindows(win, false)
        val controller = win?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val barTypes = WindowInsetsCompat.Type.statusBars() or
            WindowInsetsCompat.Type.navigationBars()
        // Hide sekali + sekali setelah layout stabil (bukan 3x — layout thrash).
        controller?.hide(barTypes)
        val decor = win?.decorView
        val hideLater = Runnable { controller?.hide(barTypes) }
        decor?.postDelayed(hideLater, 300)
        onDispose {
            decor?.removeCallbacks(hideLater)
            if (win != null) WindowCompat.setDecorFitsSystemWindows(win, true)
            controller?.show(barTypes)
        }
    }

    // List channel HARUS dari tab yang sedang diputar, bukan default 'EV'.
    // Kalau salah tab, currentIdx akan mengarah ke channel yang salah dan saat balik
    // home tampil channel 01.
    // Diagnostik harus siap sebelum player jalan: logcat Redroid tidak bisa dipakai
    // (buffer penuh spam lowmemorykiller), jadi bukti ditulis ke file.
    androidx.compose.runtime.LaunchedEffect(Unit) { playerVm.bindDiagDir(context) }

    val cards = remember(playerVm.tabCode) {
        val ev = ChannelRepository.loadGrid(context, playerVm.tabCode)
        // JANGAN biarkan tab EV kosong (mis. semua event sudah lewat / feed lagi sync):
        // fallback ke channel 24/7 supaya user tetap dapat sesuatu yang bisa diputar,
        // bukan layar "No channel selected".
        if (ev.isEmpty() && playerVm.tabCode.equals("EV", true)) {
            ChannelRepository.loadGrid(context, "C0")
        } else ev
    }
    var currentIdx by remember {
        mutableIntStateOf(
            if (cards.isEmpty()) 0
            else (if (playerVm.index in cards.indices) playerVm.index else channelIndex)
                .coerceIn(cards.indices)
        )
    }
    val error = remember { mutableStateOf<String?>(null) }
    // MediaId yang menyebabkan error — dipakai untuk tahu kapan error sudah basi.
    val errorMediaId = remember { mutableStateOf<String?>(null) }
    // Stall detection: player bisa STATE_READY / onError tidak pernah datang, tapi
    // TIDAK ADA frame yang dirender (mis. playlist 403 berisi HTML yang tetap di-parse).
    // Tanpa deteksi ini UI jadi layar hitam total tanpa pesan & tanpa tombol keluar.
    val stalled = remember { mutableStateOf(false) }
    val lastPos = remember { mutableStateOf(-1L) }
    val retryKey = remember { mutableIntStateOf(0) }
    val retryCount = remember { mutableIntStateOf(0) }
    val maxRetries = 3
    val failed = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val loading = remember { mutableStateOf(false) }
    val card = if (playerVm.deeplinkMode) playerVm.deeplinkCard else cards.getOrNull(currentIdx)

    // Channel embed (PPW/EsportEx/NFL) diputar oleh WebViewPlayer, BUKAN ExoPlayer.
    // Untuk channel ini playerVm.player tetap kosong / tidak dipakai, jadi watchdog
    // stall berbasis ExoPlayer akan memberi false positive "Stream tidak merespons"
    // padahal WebView sedang main. Hitung di sini supaya watchdog bisa melewatinya.
    val currentUrl = remember(card, currentIdx) { card?.channel?.hls.orEmpty() }
    val isEmbedChannel = remember(currentUrl) {
        val direct = currentUrl.contains(".m3u8", true) || currentUrl.contains(".mp4", true) ||
            currentUrl.contains(".mpd", true) || currentUrl.contains("/cenc", true) ||
            currentUrl.contains("/manifest", true)
        !direct && currentUrl.startsWith("http") &&
            (currentUrl.contains("embed") || currentUrl.contains("player") ||
                currentUrl.contains("/#") || currentUrl.contains("iframe"))
    }

    // Pasang listener ke VM SEBELUM pertama kali play (error saat prepare tertangkap).
    // currentIdx dibaca dari VM supaya callback selalu saw channel aktif.
    DisposableEffect(playerVm) {
        playerVm.onError = { _ ->
            // Coba server alternatif dulu sebelum pindah channel.
            val recovered = playerVm.advanceServer(context)
            if (!recovered) {
                // BATAS RETRY: tanpa ini, kalau SEMUA channel gagal (mis. semua kena 403
                // dari IP device), error → pindah → retry → error ... jadi loop tanpa henti
                // yang membuat UI terlihat freeze dan hammer player terus-menerus.
                if (retryCount.intValue >= maxRetries) {
                    failed.value = emptySet()
                    errorMediaId.value = playerVm.player?.currentMediaItem?.mediaId
                    error.value = "Semua channel gagal dimuat.\nCek koneksi / coba lagi."
                    loading.value = false
                    // JANGAN reset retryCount di sini — kalau direset, handler berikutnya
                    // menganggap ini percobaan baru dan loop kembali tanpa pernah menampilkan
                    // pesan. Biarkan mentok supaya user lihat error + tombol RETRY.
                } else if (playerVm.deeplinkMode) {
                    // Deep-link: TIDAK ada channel lain untuk dicoba. Tampilkan error
                    // (sebelumnya layar cuma hitam tanpa info apa pun).
                    errorMediaId.value = playerVm.player?.currentMediaItem?.mediaId
                    error.value = "Stream tidak bisa dimuat.\nURL mungkin sudah mati / diblok."
                    loading.value = false
                } else if (cards.isEmpty()) {
                    // Tidak ada card sama sekali: jangan loop, tampilkan pesan.
                    errorMediaId.value = playerVm.player?.currentMediaItem?.mediaId
                    error.value = "Tidak ada channel tersedia."
                    loading.value = false
                } else {
                    retryCount.intValue++
                    val from = currentIdx
                    failed.value = failed.value + from
                    val dead = failed.value
                    val next = (1..cards.size).map { (from + it) % cards.size }
                        .firstOrNull { it !in dead }
                    currentIdx = next ?: run {
                        failed.value = emptySet()
                        (from + 1) % cards.size
                    }
                    error.value = null
                    retryKey.intValue++
                }
            } else {
                error.value = null
                retryKey.intValue++
            }
        }
        onDispose {
            playerVm.onError = null
        }
    }

    val fsViewRef = remember { mutableStateOf<PlayerView?>(null) }
    // Satu-satunya efek samping: ganti MediaItem HANYA kalau beda channel.
    // retryKey/force menimpa playingUrl supaya RETRY benar-benar memuat ulang.
    // Kalau URL yang sama sudah diputar (kasus normal buka fullscreen dari hero),
    // KITA TIDAK memanggil play() sama sekali: murni pasang PlayerView ke player yang
    // sudah ada → tidak ada re-prepare, tidak ada kedip "Memuat channel…".
    LaunchedEffect(currentIdx, retryKey.intValue, playerVm.resolvingDeeplinkState.value) {
        android.util.Log.d("KaTvDeep", "PlayerScreen effect: resolving=${playerVm.resolvingDeeplinkState.value} idx=$currentIdx retry=$retryKey")
        // Deep-link: JANGAN di-skip kalau ini RETRY manual (retryKey>0) — user menekan
        // RETRY harus benar-benar memuat ulang URL, bukan kembali ke layar hitam.
        if (playerVm.resolvingDeeplinkState.value) return@LaunchedEffect
        if (playerVm.deeplinkMode) {
            if (retryKey.intValue > 0) {
                playerVm.deeplinkCard?.let { dc ->
                    playerVm.play(context, dc, force = true)
                    loading.value = true
                }
            }
            return@LaunchedEffect
        }
        // Anchor identitas: saat MASUK first compose (retryKey==0), index VM bisa drift
        // (duplicate channel / grid beda). Pakai URL yang benar-benar diputar di home
        // sebagai source of truth supaya fullscreen selalu menampilkan channel yang sama.
        // Retry (retryKey>0) SENGAJA dilewati supaya fallback error tetap jalan.
        if (retryKey.intValue == 0 && playerVm.playingUrl.isNotBlank()) {
            val anchor = cards.indexOfFirst { it.channel.hls == playerVm.playingUrl }
            if (anchor >= 0 && anchor != currentIdx) {
                currentIdx = anchor
                return@LaunchedEffect
            }
        }
        val target = cards.getOrNull(currentIdx)
        if (target != null) {
            playerVm.index = currentIdx
            playerVm.selectedIndex = currentIdx
            // Channel embed diputar WebViewPlayer. JANGAN serahkan URL embed ke
            // ExoPlayer: dia akan gagal parse HTML → onPlayerError → handleError
            // menaikkan retryCount sampai mentok, sehingga overlay "Semua channel
            // gagal" menutupi WebView yang sebenarnya sedang main.
            if (isEmbedChannel) {
                loading.value = false
                return@LaunchedEffect
            }
            val already = playerVm.playingUrl == target.channel.hls
            val playing = playerVm.player?.isPlaying == true
            if (already && playing && retryKey.intValue == 0) {
                // Pure attach: stream sudah hidup, jangan ganti MediaItem / re-prepare.
                // Cukup pastikan playWhenReady aktif & player benar-benar playing — setelah
                // PlayerView baru attach, surface swap bisa bikin stream rapuh diam sesaat.
                playerVm.player?.playWhenReady = true
                if (playerVm.player?.isPlaying == false) playerVm.player?.play()
                loading.value = false
            } else {
                playerVm.play(context, target, force = retryKey.intValue > 0)
                // Genuine switch / retry yang menampilkan overlay.
                loading.value = retryKey.intValue > 0 || !already
            }
        }
    }

    // Hapus overlay "Memuat" begitu frame pertama mulai diputar — ATAU setelah timeout.
    // Dua jalan keluar ini mencegah teks "Memuat channel…" nempel selamanya saat
    // isPlaying sempat fluktuasi (surface swap / HLS rebuffer) padahal video sudah tampil.
    // Pantau status player: bersihkan overlay error HANYA kalau video benar-benar playing.
    // Catatan: STATE_READY saja tidak cukup — stream bisa READY tapi frame diam (stall),
    // dan kalau kita anggap "sukses", pesan error yang sah malah hilang.
    // Sekalian reset retryCount saat sukses, supaya error berikutnya (channel lain)
    // dapat jatah retry penuh lagi — bukan langsung mentok.
    // Pantau status player. Error dihapus HANYA kalau media item yang sekarang berbeda
    // dari media yang error (artinya channel berganti & berhasil). Kalau media sama,
    // error tetap tampil walau posisi > 0 dari stream sebelumnya — sumber bug "hitam
    // tanpa pesan" adalah menghapus error berdasarkan posisi player yang masih nyangkut.
    // Pantau status player. Loop ini SENGAJA lambat (1.5 s) dan hanya menulis state kalau
    // NILAINYA BERUBAH. Loop cepat (500 ms) yang membaca currentPosition memicu
    // recomposition seluruh layar → UI thread macet (jank 33%, p95 1.4 s = "freeze").
    LaunchedEffect(playerVm) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            val p = playerVm.player ?: continue
            val nowMedia = try { p.currentMediaItem?.mediaId ?: "" } catch (_: Exception) { "" }
            val playing = p.isPlaying
            // Diagnostik berkala: buktikan subtitle mati & resolusi yang dipilih.
            // Hanya saat playing supaya tidak spam ketika idle/buffering.
            if (playing) playerVm.logTrackState(p, "periodic")
            // Bersihkan error HANYA kalau media berganti dan sudah jalan.
            val em = errorMediaId.value
            if (em != null && nowMedia.isNotEmpty() && nowMedia != em && playing) {
                error.value = null
                errorMediaId.value = null
                if (retryCount.intValue != 0) retryCount.intValue = 0
            }
            // ── STALL STATE RESET ────────────────────────────────────────────
            // Begitu benar-benar playing, hapus status stall supaya watchdog berikut
            // (channel lain) mulai dari nol.
            if (playing) {
                stalled.value = false
                lastPos.value = p.currentPosition
            }
            // Tulis state hanya kalau benar-benar berubah (hindari recomposition sia-sia).
            if (playing && loading.value) loading.value = false
            if (playing && error.value == null && retryCount.intValue != 0) retryCount.intValue = 0
        }
    }

    // Watchdog stall independen: berjalan per MediaItem (retryKey/currentIdx), bukan
    // polling posisi. Kalau dalam 9 detik tidak pernah ada frame yang jalan, tampilkan
    // pesan + tombol RETRY/BACK supaya user tidak terjebak layar hitam.
    // SKIP untuk channel embed: yang memutar adalah WebViewPlayer, bukan ExoPlayer,
    // jadi player kosong di sini bukan bukti stream mati (false positive).
    LaunchedEffect(currentIdx, retryKey.intValue, isEmbedChannel) {
        android.util.Log.d("KaTvWatchdog", "start idx=$currentIdx embed=$isEmbedChannel url=${currentUrl.take(80)}")
        stalled.value = false
        if (isEmbedChannel) { android.util.Log.d("KaTvWatchdog", "skip embed"); return@LaunchedEffect }
        kotlinx.coroutines.delay(9000)
        val p = playerVm.player
        android.util.Log.d("KaTvWatchdog", "check player=$p isPlaying=${p?.isPlaying} pos=${p?.currentPosition}")
        if (p == null) return@LaunchedEffect
        val playing = p.isPlaying
        val advanced = p.currentPosition > 800L
        if (!playing && !advanced) {
            android.util.Log.d("KaTvWatchdog", "STALL fired")
            stalled.value = true
            if (error.value == null) {
                errorMediaId.value = p.currentMediaItem?.mediaId
                error.value = "Stream tidak merespons.\nChannel mungkin sudah selesai / URL mati."
                loading.value = false
            }
        }
    }

    // Overlay "Memuat" hilang otomatis: kalau player sudah playing, atau setelah 2.5 s.
    // Key cukup loading.value — jangan tambahkan retryKey (bikin effect restart & loop ganda).
    LaunchedEffect(loading.value) {
        if (!loading.value) return@LaunchedEffect
        var waited = 0L
        while (loading.value && waited < 2500L) {
            kotlinx.coroutines.delay(250)
            waited += 250
            val p = playerVm.player
            if (p?.isPlaying == true) { loading.value = false; break }
        }
        if (loading.value) loading.value = false
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var controlsHideTick by remember { mutableIntStateOf(0) }
    var quality by remember { mutableIntStateOf(playerVm.quality) }
    var muted by remember { mutableStateOf(playerVm.muted) }

    // Auto-hide controls after 8 s of inactivity. Tap on video toggles + resets the timer.
    LaunchedEffect(controlsVisible, retryKey.intValue, controlsHideTick) {
        if (controlsVisible) {
            delay(8000)
            controlsVisible = false
        }
    }

    // ---- Vertical swipe gestures (LEFT = brightness, RIGHT = volume) ----
    val activity = context as? ComponentActivity
    // Simpan nilai window apa adanya (bisa -1 = ikut sistem) supaya bisa dipulihkan
    // persis saat keluar fullscreen — jangan sampai seluruh app tetap gelap.
    val originalWindowBrightness = remember {
        activity?.window?.attributes?.screenBrightness ?: -1f
    }
    val seedBrightness = remember {
        if (originalWindowBrightness >= 0f) {
            originalWindowBrightness
        } else {
            val system = try {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            } catch (_: Exception) {
                0.5f
            }
            system.coerceIn(0.01f, 1f)
        }
    }
    val currentBrightness = remember { mutableFloatStateOf(seedBrightness) }

    fun applyBrightness(value: Float) {
        val win = activity?.window ?: return
        val attrs = win.attributes
        attrs.screenBrightness = value.coerceIn(0.01f, 1f)
        win.attributes = attrs
    }

    DisposableEffect(Unit) {
        onDispose {
            val win = activity?.window ?: return@onDispose
            val attrs = win.attributes
            attrs.screenBrightness = originalWindowBrightness
            win.attributes = attrs
        }
    }

    // "volume" | "brightness" + persen; null = tidak ditampilkan.
    var gestureIndicator by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(gestureIndicator) {
        if (gestureIndicator != null) {
            delay(800)
            gestureIndicator = null
        }
    }
    // Ditentukan sekali di awal drag dari sisi sentuhan; dibaca ulang di lambda drag
    // yang sama (pointerInput key = Unit), jadi tidak perlu state/recomposition.
    var brightnessGesture = remember { mutableStateOf(false) }

    if (card == null) {
        Box(Modifier.fillMaxSize().background(Obsidian), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Channel belum siap", color = Paper, fontFamily = SansFont, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "TAK MUAT ULANG",
                    color = Vermilion, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        ChannelRepository.clearCache()
                        retryKey.intValue++
                    }
                )
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Obsidian)) {
        // Lokal gesture di Box pembungkus: detectVerticalDragGestures HANYA memakan
        // drag vertikal, tap tetap diteruskan ke PlayerView (klik toggle controls).
        Box(Modifier.fillMaxSize()) {
            val u = (if (playerVm.deeplinkMode) playerVm.deeplinkCard else cards.getOrNull(currentIdx))?.channel?.hls.orEmpty()
            // Sama seperti hero: .mpd/cenc/manifest adalah stream langsung (DASH), BUKAN embed.
            val isDirect = u.contains(".m3u8", true) || u.contains(".mp4", true) ||
                u.contains(".mpd", true) || u.contains("/cenc", true) || u.contains("/manifest", true)
            val isEmbed = !isDirect && u.startsWith("http") &&
                (u.contains("embed") || u.contains("player") || u.contains("/#") || u.contains("iframe"))
            if (isEmbed && u.isNotBlank()) {
                // Stream berbasis embed HTML (PPW/EsportEx): pakai browser engine
                // dengan ad-block + auto-unmute, bukan ExoPlayer (yang gak paham HTML).
                WebViewPlayer(url = u, modifier = Modifier.fillMaxSize())
            } else {
            // Gesture & tap ditangani langsung di AndroidView di bawah (modifier-nya),
            // supaya PlayerView tidak "memakan" touch sebelum gesture terdeteksi.
            // Tidak ada key(retryKey): PlayerView tidak dibongkar. Pasang player yang SAMA
            // di sini (hero sudah melepasnya saat navigasi), jadi tidak ada ExoPlayer baru
            // dan tidak ada fetch HLS ulang -> fullscreen langsung tampil.
            AndroidView(
                factory = { ctx ->
                    val view = PlayerView(ctx)
                    // SurfaceView default: decoder software (OMX.google.h264, umum di
                    // Redroid/TV box murah) TIDAK support SurfaceTexture/TextureView —
                    // codec langsung released → layar hitam. Freeze saat rotasi ditangani
                    // dengan menahan view (lihat AndroidView tanpa key / DisposableEffect).
                    fsViewRef.value = view
                    view.player = playerVm.ensurePlayer(ctx)
                    view.useController = false
                    view.controllerShowTimeoutMs = 0
                    view.tag = playerVm.player
                    view.setOnClickListener {
                        controlsVisible = !controlsVisible
                        controlsHideTick++
                    }
                    view
                },
                update = { view ->
                    if (view.player !== playerVm.player) view.player = playerVm.player
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { start ->
                                brightnessGesture.value = start.x < size.width / 2f
                            },
                            onVerticalDrag = { _, dragAmount ->
                                val height = size.height.toFloat().coerceAtLeast(1f)
                                val delta = -dragAmount / height
                                if (brightnessGesture.value) {
                                    val from = activity?.window?.attributes?.screenBrightness
                                        ?.takeIf { it >= 0f } ?: currentBrightness.floatValue
                                    val next = (from + delta).coerceIn(0.01f, 1f)
                                    currentBrightness.floatValue = next
                                    applyBrightness(next)
                                    gestureIndicator = "brightness" to (next * 100).toInt()
                                } else {
                                    val from = playerVm.player?.volume ?: 0f
                                    val next = (from + delta).coerceIn(0f, 1f)
                                    playerVm.player?.volume = next
                                    val nowMuted = next <= 0f
                                    playerVm.muted = nowMuted
                                    muted = nowMuted
                                    gestureIndicator = "volume" to (next * 100).toInt()
                                }
                            }
                        )
                    }
            ) // AndroidView
            } // else (non-embed)
        }

        // Indikator volume/brightness: muncul saat drag, hilang 800 ms setelahnya.
        gestureIndicator?.let { (kind, pct) ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            kind == "brightness" -> Icons.Default.WbSunny
                            pct <= 0 -> Icons.Default.VolumeOff
                            else -> Icons.Default.VolumeUp
                        },
                        contentDescription = kind,
                        tint = Vermilion,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "$pct%",
                        color = Paper,
                        fontFamily = MonoFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                }
            }
        }

        // Jangan lepas player dari PlayerView saat layar ini hilang. Player dimiliki VM,
        // tidak pernah di-release; melepasnya bikin surface detach gap saat balik ke hero.
        DisposableEffect(Unit) {
            onDispose {
                playerVm.onError = null
            }
        }

        if (loading.value && error.value == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Memuat channel…", color = Paper, fontFamily = MonoFont, fontSize = 13.sp)
            }
        }

        // Custom controls — tap video toggles visibility; auto-hide after 8 s.
        if (controlsVisible) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(16.dp)
                    .clickable { controlsVisible = !controlsVisible }
            ) {
                Text(
                    "CH ${card.number} · ${card.channelName}",
                    color = Paper, fontFamily = SansFont, fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(card.programTitle, color = Quiet, fontFamily = MonoFont, fontSize = 12.sp)
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "BACK",
                    color = Vermilion,
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onBack() }
                )
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { onBack() }) {
                    Icon(
                        Icons.Default.FullscreenExit,
                        contentDescription = "Exit fullscreen",
                        tint = Vermilion
                    )
                }
            }
            // Glass control pill bottom-right: mute + resolution (same as hero).
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    muted = !muted
                    playerVm.muted = muted
                    playerVm.player?.volume = if (muted) 0f else 1f
                    playerVm.diag("MUTE muted=$muted vol=${playerVm.player?.volume}")
                }) {
                    Icon(
                        if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = "Mute",
                        tint = if (muted) Vermilion else Color.White
                    )
                }
                var qualityExpanded by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .clickable { qualityExpanded = true }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            playerVm.qualityLabels[quality.coerceIn(playerVm.qualityLabels.indices)],
                            color = Color.White,
                            fontFamily = MonoFont, fontSize = 11.sp
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Pilih resolusi",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = qualityExpanded,
                        onDismissRequest = { qualityExpanded = false },
                        modifier = Modifier
                            .background(Color(0xFF141416))
                            .border(1.dp, Color(0xFF3A3A3C), RoundedCornerShape(8.dp))
                    ) {
                        playerVm.qualityLabels.forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        color = if (i == quality) Vermilion else Color.White,
                                        fontFamily = MonoFont,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    quality = i
                                    playerVm.quality = i
                                    qualityExpanded = false
                                    playerVm.applyQuality()
                                    playerVm.diag("QUALITY picked=$i ${playerVm.qualityLabels[i]}")
                                }
                            )
                        }
                    }
                }
            }
        }

        // Overlay error HANYA kalau memang ada error DAN player tidak sedang playing.
        // `stalled` ikut: stream yang "READY" tapi tidak pernah merender frame juga
        // harus menampilkan pesan, bukan layar hitam tanpa jalan keluar.
        // Channel embed dilewati: ExoPlayer-nya memang tidak dipakai, jadi statusnya
        // tidak mencerminkan apa pun tentang WebView.
        val liveNow = (playerVm.player?.isPlaying == true) ||
            (playerVm.player?.playbackState == androidx.media3.common.Player.STATE_READY && !stalled.value)
        if (error.value != null && !liveNow && !isEmbedChannel) {
            Column(
                Modifier.align(Alignment.Center).background(Color(0xEE000000)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Playback error", color = Vermilion, fontFamily = SansFont,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(error.value ?: "", color = Paper, fontFamily = MonoFont, fontSize = 12.sp,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RETRY", color = Vermilion, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, modifier = Modifier.clickable {
                            // Retry manual harus mengulang dari nol: reset counter & daftar gagal,
                            // kalau tidak, retryCount sudah mentok → langsung error lagi.
                            retryCount.intValue = 0
                            failed.value = emptySet()
                            stalled.value = false
                            playerVm.resetServersForRetry()
                            retryKey.intValue++
                            loading.value = true
                            error.value = null
                        })
                    Spacer(Modifier.width(28.dp))
                    // Jalan keluar wajib ada: tanpa ini user terjebak layar hitam
                    // karena controls auto-hide setelah 8 detik.
                    Text("KEMBALI", color = Quiet, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, modifier = Modifier.clickable { onBack() })
                }
            }
        }
    }
}
