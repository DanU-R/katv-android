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
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val win = activity?.window
        // Jangan biarkan konten masuk ke area system bar.
        if (win != null) WindowCompat.setDecorFitsSystemWindows(win, false)
        val controller = win?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val barTypes = WindowInsetsCompat.Type.statusBars() or
            WindowInsetsCompat.Type.navigationBars()
        // Hide SEKARANG + lagi setelah window selesai layout (rotate ke landscape).
        controller?.hide(barTypes)
        val decor = win?.decorView
        val hideLater = Runnable { controller?.hide(barTypes) }
        decor?.post(hideLater)
        decor?.postDelayed(hideLater, 250)
        decor?.postDelayed(hideLater, 800)
        onDispose {
            decor?.removeCallbacks(hideLater)
            // Selalu balik ke PORTRAIT saat keluar fullscreen. Jangan pakai 'previous':
            // navigasi sebelumnya bisa meninggalkan landscape, bikin list home kepotong.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (win != null) WindowCompat.setDecorFitsSystemWindows(win, true)
            controller?.show(barTypes)
        }
    }

    // List channel HARUS dari tab yang sedang diputar, bukan default 'EV'.
    // Kalau salah tab, currentIdx akan mengarah ke channel yang salah dan saat balik
    // home tampil channel 01.
    val cards = remember(playerVm.tabCode) {
        ChannelRepository.loadGrid(context, playerVm.tabCode)
    }
    var currentIdx by remember {
        mutableIntStateOf(
            if (cards.isEmpty()) 0
            else (if (playerVm.index in cards.indices) playerVm.index else channelIndex)
                .coerceIn(cards.indices)
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    val failed = remember { mutableStateOf<Set<Int>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    val card = if (playerVm.deeplinkMode) playerVm.deeplinkCard else cards.getOrNull(currentIdx)

    // Pasang listener ke VM SEBELUM pertama kali play (error saat prepare tertangkap).
    // currentIdx dibaca dari VM supaya callback selalu saw channel aktif.
    DisposableEffect(playerVm) {
        playerVm.onError = { _: PlaybackException ->
            val from = currentIdx
            failed.value = failed.value + from
            val dead = failed.value
            val next = (1..cards.size).map { (from + it) % cards.size }.firstOrNull { it !in dead }
            if (next != null) {
                currentIdx = next
                error = null
                retryKey++
            } else {
                failed.value = emptySet()
                currentIdx = if (cards.isEmpty()) 0 else (from + 1) % cards.size
                error = null
                retryKey++
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
    LaunchedEffect(currentIdx, retryKey, playerVm.resolvingDeeplinkState.value) {
        android.util.Log.d("KaTvDeep", "PlayerScreen effect: resolving=${playerVm.resolvingDeeplinkState.value} idx=$currentIdx")
        if (playerVm.deeplinkMode || playerVm.resolvingDeeplinkState.value) return@LaunchedEffect // deep-link: jangan auto-play dari grid
        // Anchor identitas: saat MASUK first compose (retryKey==0), index VM bisa drift
        // (duplicate channel / grid beda). Pakai URL yang benar-benar diputar di home
        // sebagai source of truth supaya fullscreen selalu menampilkan channel yang sama.
        // Retry (retryKey>0) SENGAJA dilewati supaya fallback error tetap jalan.
        if (retryKey == 0 && playerVm.playingUrl.isNotBlank()) {
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
            val already = playerVm.playingUrl == target.channel.hls
            val playing = playerVm.player?.isPlaying == true
            if (already && playing && retryKey == 0) {
                // Pure attach: stream sudah hidup, jangan ganti MediaItem / re-prepare.
                // Cukup pastikan playWhenReady aktif & player benar-benar playing — setelah
                // PlayerView baru attach, surface swap bisa bikin stream rapuh diam sesaat.
                playerVm.player?.playWhenReady = true
                if (playerVm.player?.isPlaying == false) playerVm.player?.play()
                loading = false
            } else {
                playerVm.play(context, target, force = retryKey > 0)
                // Genuine switch / retry yang menampilkan overlay.
                loading = retryKey > 0 || !already
            }
        }
    }

    // Hapus overlay "Memuat" begitu frame pertama mulai diputar.
    LaunchedEffect(loading) {
        while (loading) {
            kotlinx.coroutines.delay(200)
            if (playerVm.player?.isPlaying == true) { loading = false; break }
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var controlsHideTick by remember { mutableIntStateOf(0) }
    var quality by remember { mutableIntStateOf(playerVm.quality) }
    var muted by remember { mutableStateOf(playerVm.muted) }

    // Auto-hide controls after 8 s of inactivity. Tap on video toggles + resets the timer.
    LaunchedEffect(controlsVisible, retryKey, controlsHideTick) {
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No channel selected", color = Paper, fontFamily = SansFont, fontSize = 16.sp)
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Obsidian)) {
        // Lokal gesture di Box pembungkus: detectVerticalDragGestures HANYA memakan
        // drag vertikal, tap tetap diteruskan ke PlayerView (klik toggle controls).
        Box(Modifier.fillMaxSize()) {
            val u = (if (playerVm.deeplinkMode) playerVm.deeplinkCard else cards.getOrNull(currentIdx))?.channel?.hls.orEmpty()
            val isEmbed = !u.contains(".m3u8", true) && !u.contains(".mp4", true) &&
                (u.startsWith("http") && (u.contains("embed") || u.contains("player") || u.contains("/#") || u.contains("iframe")))
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

        if (loading && error == null) {
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
                                }
                            )
                        }
                    }
                }
            }
        }

        if (error != null) {
            Column(
                Modifier.align(Alignment.Center).background(Color(0xEE000000)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Playback error", color = Vermilion, fontFamily = SansFont,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(error ?: "", color = Paper, fontFamily = MonoFont, fontSize = 12.sp,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text("RETRY", color = Vermilion, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
                    fontSize = 14.sp, modifier = Modifier.clickable {
                        failed.value = failed.value + currentIdx
                        retryKey++
                        loading = true
                        error = null
                    })
            }
        }
    }
}
