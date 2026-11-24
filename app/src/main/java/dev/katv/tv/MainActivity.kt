package dev.katv.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.katv.tv.ui.HomeScreen
import dev.katv.tv.ui.SplashScreen
import dev.katv.tv.ui.PlayerScreen
import dev.katv.tv.ui.WebViewPlayer
import dev.katv.tv.ui.theme.KaTvTypography
import dev.katv.tv.data.ChannelRepository
import dev.katv.tv.data.RctiResolver
import android.net.Uri
import dev.katv.tv.data.ChannelCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startMediaEarly = intent?.getStringExtra("media_url")
        val deepVm = androidx.lifecycle.ViewModelProvider(this)[PlayerVm::class.java]
        if (!startMediaEarly.isNullOrBlank()) {
            deepVm.resolvingDeeplink = true
            deepVm.deeplinkMode = true
        }
        setContent {
            MaterialTheme(typography = KaTvTypography) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color(0xFF121211)
                ) {
                    // Splash ditangani SISTEM (Android 12+ windowSplashScreen*, API<=30
                    // windowBackground layer-list). Tidak ada overlay Compose: NavHost
                    // langsung menggambar frame pertama & sistem menutup splash sendiri.
                    Box(Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        // SATU ViewModel & SATU ExoPlayer untuk seluruh activity.
                        val playerVm: PlayerVm = viewModel()
                        val startEmbed = intent?.getStringExtra("embed_url")
                        val startMedia = intent?.getStringExtra("media_url")
                        val startRoute = when {
                            !startEmbed.isNullOrBlank() -> "player/url/${Uri.encode(startEmbed)}"
                            !startMedia.isNullOrBlank() -> "player/0"
                            else -> "splash"
                        }
                        // Deep-link media_url: cari channel di feed (semua region) lalu play.
                        if (!startMedia.isNullOrBlank()) {
                            lifecycleScope.launch {
                                val found = withContext(Dispatchers.IO) {
                                    ChannelRepository.loadAll(this@MainActivity)
                                        .firstOrNull { it.hls == startMedia }
                                        ?.let { dto ->
                                            ChannelCard(
                                                number = "01", channelName = dto.name,
                                                programTitle = dto.name, matchInfo = dto.tagline,
                                                subtitle = "", logoUrl = dto.logo, heroImageUrl = dto.logo,
                                                progress = 0f, isLive = true, channel = dto
                                            )
                                        }
                                }
                                if (found != null) {
                                    android.util.Log.d("KaTvDeep", "found ${found.channel.name} -> play")
                                    playerVm.deeplinkCard = found
                                    playerVm.play(this@MainActivity, found, force = true)
                                } else {
                                    // URL custom (test/deep-link): bikin ChannelDto minimal langsung.
                                    android.util.Log.d("KaTvDeep", "not in feed, try direct: $startMedia")
                                    val dto = dev.katv.tv.data.ChannelDto(
                                        id = "deeplink", name = "Deep Link", hls = startMedia,
                                        jenis = if (RctiResolver.isRctiPlayer(startMedia)) "drm_rcti_plus" else "hls"
                                    )
                                    val dc = ChannelCard(
                                        number = "01", channelName = dto.name, programTitle = dto.name,
                                        matchInfo = "", subtitle = "", logoUrl = "", heroImageUrl = "",
                                        progress = 0f, isLive = true, channel = dto
                                    )
                                    playerVm.deeplinkCard = dc
                                    playerVm.play(this@MainActivity, dc, force = true)
                                }
                            }
                        }
                        NavHost(navController = navController, startDestination = startRoute) {
                            composable("splash") {
                                SplashScreen(
                                    onFinished = {
                                        navController.navigate("home") {
                                            popUpTo("splash") { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable("home") {
                                HomeScreen(
                                    playerVm = playerVm,
                                    onOpenPlayer = { navController.navigate("player/$it") }
                                )
                            }
                            composable("player/url/{url}") { backStackEntry ->
                                val raw = backStackEntry.arguments?.getString("url").orEmpty()
                                val embedUrl = Uri.decode(raw)
                                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                                    WebViewPlayer(url = embedUrl, modifier = Modifier.fillMaxSize())
                                    androidx.compose.material3.Text(
                                        "BACK",
                                        color = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier
                                            .clickable { navController.popBackStack() }
                                            .padding(16.dp)
                                    )
                                }
                            }
                            composable("player/{index}") { backStackEntry ->
                                val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: 0
                                PlayerScreen(
                                    playerVm = playerVm,
                                    channelIndex = index,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
