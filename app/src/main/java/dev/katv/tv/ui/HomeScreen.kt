package dev.katv.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import dev.katv.tv.PlayerVm
import dev.katv.tv.data.ChannelCard
import dev.katv.tv.data.ChannelRepository
import dev.katv.tv.data.FeedRepository
import dev.katv.tv.ui.theme.KaTvColors
import dev.katv.tv.ui.theme.MonoFont
import dev.katv.tv.ui.theme.SansFont

private val Obsidian = Color(KaTvColors.Obsidian)
private val Surface = Color(KaTvColors.Surface)
private val Rule = Color(KaTvColors.Rule)
private val Vermilion = Color(KaTvColors.Vermilion)
private val Paper = Color(KaTvColors.Paper)
private val Quiet = Color(KaTvColors.Quiet)

private val qualityHeights = listOf(Int.MAX_VALUE, 1080, 720, 480)

@Composable
fun HomeScreen(playerVm: PlayerVm, onOpenPlayer: (Int) -> Unit) {
    val context = LocalContext.current
    var feedVersion by remember { mutableIntStateOf(0) }
    // JANGAN blok UI dengan spinner saat startup. Cache/asset channels.json langsung
    // dipakai (loadGrid cache-first), jadi Home muncul INSTAN tanpa blank hitam.
    // Feed refresh jalan SILENT di background, gak pernah rebuild grid di tengah jalan.
    LaunchedEffect(Unit) {
        try {
            // Pastikan fetch ulang walaupun proses pernah sync (mis. balik dari fullscreen).
            FeedRepository.resetSync()
            val n = FeedRepository.syncFeed(context)
            android.util.Log.d("KaTvFeed", "syncFeed result=$n")
            if (n > 0) {
                // Sync selesai → paksa rebuild dari channels.json yang baru (bukan asset lama).
                // Tanpa ini, allCache sudah terisi dari asset sebelum sync selesai → event baru tak muncul.
                val prevUrl = playerVm.playingUrl
                ChannelRepository.clearCache()
                val refreshed = ChannelRepository.loadAll(context)
                if (prevUrl.isBlank() || refreshed.any { it.hls == prevUrl }) {
                    feedVersion++
                    android.util.Log.d("KaTvFeed", "feedVersion -> $feedVersion, cache cleared")
                }
            }
        } catch (e: Exception) {
            // feed gagal -> cache/asset lama tetap dipakai, tanpa error UI
        }
        // Auto-refresh jadwal event tiap 5 menit: source EV.json bisa berubah
        // kapan saja (pertandingan baru). Tanpa ini, event baru cuma muncul kalau
        // app dibuka ulang — itu bukan jadwal dinamis.
        while (true) {
            kotlinx.coroutines.delay(300_000L)
            try {
                FeedRepository.resetSync()
                val n = FeedRepository.syncFeed(context)
                if (n > 0) {
                    val prevUrl = playerVm.playingUrl
                    ChannelRepository.clearCache()
                    val refreshed = ChannelRepository.loadAll(context)
                    // Jangan rebuild grid kalau channel yang sedang diputar masih ada —
                    // ini yang bikin pindah channel sendiri tiap 5 menit pas feed refresh.
                    if (prevUrl.isBlank() || refreshed.any { it.hls == prevUrl }) {
                        feedVersion++
                    } else {
                        android.util.Log.d("KaTvFeed", "skip rebuild: current channel no longer in feed")
                    }
                }
            } catch (e: Exception) { /* jaga feed lama tetap tampil */ }
        }
    }
    val tabs = remember(feedVersion) { ChannelRepository.loadTabs(context) }
    val tabScrollState = rememberScrollState()
    // Restore tab/selection dari VM: balik dari fullscreen harus membuka tab & channel
    // yang sama dengan yang terakhir diputar (bukan default tab 0 / channel 01).
    var tab by remember {
        mutableIntStateOf(if (tabs.isEmpty()) 0 else playerVm.tab.coerceIn(tabs.indices))
    }
    LaunchedEffect(tab) { runCatching { tabScrollState.animateScrollTo(tab * 110) } }
    val tabCode = tabs.getOrNull(tab)?.code ?: "EV"
    // key(feedVersion) memaksa loadGrid dihitung ULANG setelah feed sync selesai
    // (tanpa ini, Compose bisa pakai hasil pertama dari asset lama).
    val cards = androidx.compose.runtime.key(feedVersion) {
        ChannelRepository.loadGrid(context, tabCode)
    }
    var selected by remember(tabCode, cards) {
        mutableIntStateOf(
            if (tabCode.equals("EV", true)) {
                // Prioritas: LIVE dulu, lalu 24/7 (paling stabil), baru UPCOMING.
                // Jangan pilih UPCOMING/ENDED di depan — itu yang bikin loncat-loncat
                // ke event yang belum mulai / sudah selesai saat feed refresh.
                val live = cards.indexOfFirst { it.eventStatus == "LIVE" }
                val always = cards.indexOfFirst { it.channel.tagline.contains("LIVE 24/7", true) }
                val active = when {
                    live >= 0 -> live
                    always >= 0 -> always
                    else -> cards.indexOfFirst { it.eventStatus == "UPCOMING" }
                }
                if (active >= 0) active else 0
            } else if (tabCode == playerVm.tabCode && cards.isNotEmpty())
                playerVm.selectedIndex.coerceIn(cards.indices)
            else 0
        )
    }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Channel yang gagal/timeout → dilewati auto-fallback (dinamis).
    val failed = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val heroErrorCount = remember { mutableIntStateOf(0) }
    var quality by remember { mutableIntStateOf(playerVm.quality) }
    var qualityOpen by remember { mutableStateOf(false) }
    val qualityLabels = listOf("AUTO", "1080p", "720p", "480p")

    val scroll = rememberScrollState()
    val firstCardFocus = remember { FocusRequester() }
    LaunchedEffect(cards) {
        if (cards.isNotEmpty()) firstCardFocus.requestFocus()
    }

    if (cards.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No channels available", color = Paper, fontFamily = SansFont, fontSize = 16.sp)
        }
        return
    }

    val shown = remember(cards, query) {
        if (query.isBlank()) cards
        else cards.filter { it.channelName.contains(query, true) || it.channel.countryName.contains(query, true) }
            .ifEmpty { cards }
    }
    val safeIdx = selected.coerceIn(shown.indices)
    val current = shown.getOrNull(safeIdx) ?: cards.first()
    // Index di grid penuh (tanpa filter search) — inilah yang dipakai fullscreen.
    val vmIndex = cards.indexOf(current).let { if (it >= 0) it else 0 }

    // Satu pintu masuk fullscreen: sinkronkan VM dulu supaya route index dan identitas
    // VM identik (juga saat tap "LIVE" pada row yang bukan channel hero aktif).
    val heroViewRef = remember { mutableStateOf<PlayerView?>(null) }
    // Guard anti-navigate-ganda. Hero punya Box clickable + tombol Fullscreen clickable
    // yang NESTED; satu tap bisa memicu keduanya → dua kali navigate("player/N") →
    // back stack korup → kembali ke splash (gejala "freeze"/layar hitam).
    var navigating by remember { mutableStateOf(false) }
    // Reset saat layar ini kembali aktif (balik dari player) — kalau tidak, semua tap
    // berikutnya diblokir guard.
    LaunchedEffect(Unit) { navigating = false }
    fun openPlayer(index: Int) {
        if (navigating) return
        navigating = true
        val safe = if (cards.isEmpty()) 0 else index.coerceIn(cards.indices)
        heroViewRef.value?.player = null
        playerVm.tab = tab
        playerVm.tabCode = tabCode
        playerVm.index = safe
        playerVm.selectedIndex = safe
        onOpenPlayer(safe)
    }

    // Jadikan VM source of truth untuk tab + selection. Ditulis tiap kali berubah
    // (termasuk saat tap row/hero) supaya fullscreen & balik-from-fullscreen konsisten.
    LaunchedEffect(tab, tabCode, vmIndex) {
        playerVm.tab = tab
        playerVm.tabCode = tabCode
        playerVm.index = vmIndex
        playerVm.selectedIndex = vmIndex
    }

    // Lompat ke channel berikutnya yang belum gagal; wrap-around. Kalau semua gagal, reset.
    fun advanceFrom(index: Int) {
        val n = cards.size
        if (n == 0) return
        for (step in 1..n) {
            val next = (index + step) % n
            if (next !in failed.value) { selected = next; return }
        }
        failed.value = emptySet()
        selected = (index + 1) % n
    }

    // Single source of truth: VM punya SATU player. Hero & fullscreen cuma ganti MediaItem.
    // Buka fullscreen TIDAK melepas player → tidak ada fetch ulang, tidak ada audio dobel.
    DisposableEffect(playerVm) {
        playerVm.onError = { _ ->
            // Server alternatif dulu sebelum loncat channel.
            val recovered = playerVm.advanceServer(context)
            if (!recovered) {
                // Batas loncat-channel: kalau semua gagal (mis. semua 403 dari IP device),
                // jangan loop tanpa henti — berhenti dan biarkan UI tampil normal.
                if (heroErrorCount.intValue >= cards.size.coerceAtLeast(1) + 2) {
                    failed.value = emptySet()
                    heroErrorCount.intValue = 0
                } else {
                    heroErrorCount.intValue++
                    val idx = cards.indexOfFirst { it.channel.hls == playerVm.playingUrl }
                        .let { if (it >= 0) it else playerVm.index }
                    failed.value = failed.value + idx
                    advanceFrom(idx)
                }
            }
        }
        onDispose {
            // Jangan release: fullscreen / navigation harus tetap pakai player yang sama.
            // playerVm dilepas saat Activity ViewModelStore dihapus (onCleared).
            playerVm.onError = null
        }
    }

    // Ganti URL hanya kalau channel benar-benar berubah. Setelah balik dari fullscreen,
    // current di-resolve dari tab+selected yang di-restore, jadi URL-nya sama dengan
    // yang sedang diputar → playerVm.play() jadi no-op (tanpa re-fetch / lompat ke CH01).
    // Embed (PPW/EsportEx) harus pakai WebView, BUKAN ExoPlayer — kalau dipaksa
    // ExoPlayer, URL HTML-nya error → onError → advanceFrom → loncat channel sendiri.
    val heroIsEmbed = remember(current.channel.hls) {
        val u = current.channel.hls
        // Stream langsung = HLS/DASH/progressive. Yang dianggap embed HANYA halaman HTML
        // (bukan .m3u8/.mpd/.mp4) — sebelumnya .mpd tidak dicek sehingga DASH Samurai
        // salah dianggap embed dan dimuat ke WebView (layar kosong).
        val isDirect = u.contains(".m3u8", true) || u.contains(".mp4", true) ||
            u.contains(".mpd", true) || u.contains("/cenc", true) || u.contains("/manifest", true)
        !isDirect && u.startsWith("http") &&
            (u.contains("embed") || u.contains("player") || u.contains("/#") || u.contains("iframe"))
    }
    LaunchedEffect(current.channel.hls, heroIsEmbed) {
        playerVm.tab = tab
        playerVm.tabCode = tabCode
        playerVm.index = vmIndex
        playerVm.selectedIndex = vmIndex
        if (!heroIsEmbed) playerVm.play(context, current)
    }

    // Jangan lepas player dari PlayerView saat navigasi ke fullscreen. Player dimiliki VM
    // dan TIDAK pernah di-release; melepasnya di sini bikin surface hero detach sebentar
    // sebelum PlayerView fullscreen attach → frame diam ("seolah pause") pada stream
    // HLS yang rapuh (fallback EVENTS/C5/SP). Biarkan view hierarchy yang membuang
    // PlayerView lama; referensi ke player di view lama tidak berbahaya.

    Column(modifier = Modifier.fillMaxSize().background(Obsidian)) {
        // ── STICKY hero player: di LUAR Column yang discroll ─────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF272724))
        ) {
            if (heroIsEmbed) {
                WebViewPlayer(url = current.channel.hls, modifier = Modifier.fillMaxSize())
            } else {
            AndroidView(
                factory = { ctx ->
                    val instance = playerVm.ensurePlayer(ctx)
                    heroViewRef.value = PlayerView(ctx).apply {
                        player = instance
                        useController = false
                        controllerShowTimeoutMs = 0
                        tag = instance
                    }
                    heroViewRef.value!!
                },
                update = { view ->
                    if (view.player !== playerVm.player) view.player = playerVm.player
                },
                modifier = Modifier.fillMaxSize()
            )
            }
            // gradient overlay (visual tidak diubah)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x4D121211),
                        0.5f to Color(0x00121211),
                        1f to Obsidian
                    )
                )
            )

            // Top bar: wordmark left, centered mono "2.4M · 1080p", icons right.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "KaTv",
                    color = Color.White,
                    fontFamily = SansFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    letterSpacing = (-0.06).sp
                )
                Spacer(Modifier.weight(1f))
                Row {
                    HeroIcon(Icons.Default.Search, "Search live channels") { searchOpen = !searchOpen }
                    HeroIcon(Icons.Default.Cast, "Cast to device") { /* Cast perangkat belum didukung */ }
                }
            }

            Text(
                "2.4M · 1080p",
                color = Color.White.copy(alpha = 0.8f),
                fontFamily = MonoFont,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
            )

            // Pulsing LIVE badge, right 16, top 64.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PulsingDot()
                Spacer(Modifier.width(6.dp))
                Text(
                    "LIVE", color = Vermilion, fontFamily = MonoFont,
                    fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                    letterSpacing = 0.12.sp,
                    modifier = Modifier.clickable { openPlayer(vmIndex) }
                )
            }

            // Glass control pill bottom-right: mute, 1080p, PiP, fullscreen (no pause).
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeroIcon(
                    if (playerVm.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    "Mute or unmute live broadcast",
                    tint = if (playerVm.muted) Vermilion else Color.White.copy(alpha = 0.85f)
                ) {
                    playerVm.muted = !playerVm.muted
                    playerVm.player?.volume = if (playerVm.muted) 0f else 1f
                }
                Box {
                    Text(
                        qualityLabels[quality.coerceIn(qualityLabels.indices)],
                        color = Color.White.copy(alpha = 0.85f),
                        fontFamily = MonoFont, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp).clickable { qualityOpen = true }
                    )
                    DropdownMenu(
                        expanded = qualityOpen,
                        onDismissRequest = { qualityOpen = false },
                        modifier = Modifier.background(Surface)
                    ) {
                        qualityLabels.forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        color = if (i == quality) Vermilion else Paper,
                                        fontFamily = MonoFont, fontSize = 11.sp
                                    )
                                },
                                onClick = {
                                    quality = i
                                    playerVm.quality = i
                                    qualityOpen = false
                                    playerVm.applyQuality()
                                }
                            )
                        }
                    }
                }
                HeroIcon(Icons.Default.PictureInPictureAlt, "Picture in picture") { openPlayer(vmIndex) }
                HeroIcon(Icons.Default.Fullscreen, "Enter fullscreen mode") { openPlayer(vmIndex) }
            }
        }

        // ── Hanya konten bawah yang discroll; hero tetap menempel di atas ────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll)
        ) {
            // ── 2. Program bar ───────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Rule, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "CH ${current.number}",
                        color = Vermilion,
                        fontFamily = MonoFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier.border(1.dp, Vermilion).padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.programTitle,
                            color = Paper,
                            fontFamily = SansFont,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (tabCode.equals("EV", true)) {
                                // Jadwal dinamis: "Argentina vs Costa Rica  · 18 Nov 06:00 WIB"
                                listOf(current.matchInfo, current.subtitle)
                                    .filter { it.isNotBlank() }.joinToString("  ·  ")
                            } else if (current.matchInfo.isNotBlank()) current.matchInfo
                            else "${current.channel.countryName.uppercase()} · LIVE",
                            color = Quiet,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { openPlayer(vmIndex) }
                    ) {
                        // Status event dinamis: LIVE / UPCOMING / SELESAI (dari clock).
                        val status = if (tabCode.equals("EV", true) && current.eventStatus.isNotBlank())
                            current.eventStatus else "LIVE"
                        val statusColor = when (status) {
                            "LIVE" -> Vermilion
                            "UPCOMING" -> Paper
                            else -> Quiet // SELESAI
                        }
                        if (status == "LIVE") PulsingDot()
                        Spacer(Modifier.width(if (status == "LIVE") 6.dp else 0.dp))
                        Text(status, color = statusColor, fontFamily = MonoFont, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "◀",
                        color = Vermilion,
                        fontFamily = MonoFont,
                        fontSize = 16.sp,
                        modifier = Modifier.clickable {
                            tab = (tab - 1).coerceAtLeast(0)
                        }.padding(horizontal = 8.dp, vertical = 14.dp)
                    )
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(tabScrollState),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        tabs.forEachIndexed { i, tabInfo ->
                            val active = i == tab
                            val label = tabInfo.name.uppercase()
                            Text(
                                label,
                                color = if (active) Paper else Quiet,
                                fontFamily = MonoFont,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .height(48.dp)
                                    .drawBehind {
                                        val y = size.height - 2.dp.toPx()
                                        drawLine(
                                            color = if (active) Vermilion else Color.Transparent,
                                            start = Offset(0f, y),
                                            end = Offset(size.width, y),
                                            strokeWidth = 2.dp.toPx(),
                                            cap = StrokeCap.Butt
                                        )
                                    }
                                    .padding(horizontal = 8.dp, vertical = 14.dp)
                                    .clickable { tab = i }
                            )
                        }
                    }
                    Text(
                        "▶",
                        color = Vermilion,
                        fontFamily = MonoFont,
                        fontSize = 16.sp,
                        modifier = Modifier.clickable {
                            tab = (tab + 1).coerceAtMost(tabs.lastIndex)
                        }.padding(horizontal = 8.dp, vertical = 14.dp)
                    )
                }
            }

            // ── 3. Section header ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        "ALL LIVE CHANNELS",
                        color = Paper,
                        fontFamily = MonoFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 0.12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Live channels",
                        color = Paper,
                        fontFamily = SansFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 19.sp,
                        letterSpacing = (-0.04).sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("01—06", color = Quiet, fontFamily = MonoFont, fontSize = 12.sp)
            }

            // Search bar (toggle dari ikon search di hero)
            if (searchOpen) {
                TextField(
                    value = query,
                    onValueChange = { query = it; selected = 0 },
                    label = { Text("Cari channel…", fontFamily = SansFont) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // ── 4. Vertical channel list ─────────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                shown.forEach { card ->
                    val idx = cards.indexOf(card)
                    ChannelRowView(
                        card = card,
                        active = idx == selected,
                        focusRequester = if (idx == 0) firstCardFocus else null,
                        onClick = { selected = idx },
                        onClickLive = { openPlayer(idx) }
                    )
                }
            }

            // ── 5. UP NEXT ticker ────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Rule)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "UP NEXT",
                    color = Vermilion,
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 0.1.sp
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(1.dp).height(16.dp).background(Rule))
                Spacer(Modifier.width(8.dp))
                Text(
                    "The Red Room",
                    color = Paper,
                    fontFamily = SansFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Text("22:00 · MUBI", color = Quiet, fontFamily = MonoFont, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HeroIcon(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White.copy(alpha = 0.85f),
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PulsingDot() {
    Box(Modifier.size(6.dp).background(Vermilion, androidx.compose.foundation.shape.CircleShape))
}

@Composable
private fun ChannelRowView(
    card: ChannelCard,
    active: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onClickLive: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val showRing = active || focused

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 86.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interaction)
            .clickable { onClick() }
            .background(if (active) Vermilion.copy(alpha = 0.055f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            card.number,
            color = if (active) Vermilion else Paper,
            fontFamily = MonoFont,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 10.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                card.channelName,
                color = Paper,
                fontFamily = SansFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 0.04.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                card.subtitle,
                color = Quiet,
                fontFamily = SansFont,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (active) {
            Text("〽", color = Vermilion, fontSize = 18.sp, letterSpacing = (-4).sp)
        } else {
            val st = card.eventStatus.ifBlank { "LIVE" }
            Text(
                st,
                color = when (st) { "LIVE" -> Vermilion; "UPCOMING" -> Paper; else -> Quiet },
                fontFamily = MonoFont, fontSize = 10.sp,
                modifier = Modifier.clickable { onClickLive() }
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF242422)))
}
