package dev.katv.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.katv.tv.ui.theme.KaTvColors
import dev.katv.tv.ui.theme.MonoFont
import dev.katv.tv.ui.theme.SansFont
import kotlinx.coroutines.delay

/**
 * Splash KaTv — obsidian + wordmark, aksen vermilion terukur.
 * Frame pertama (Compose), lalu [onFinished] memindahkan ke Home.
 * Deep-link melewati splash ini.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val obsidian = Color(KaTvColors.Obsidian)
    val surface = Color(KaTvColors.Surface)
    val vermilion = Color(KaTvColors.Vermilion)
    val paper = Color(KaTvColors.Paper)
    val quiet = Color(KaTvColors.Quiet)

    val fade = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fade.animateTo(1f, animationSpec = tween(350, easing = LinearEasing))
        progress.animateTo(1f, animationSpec = tween(1500, easing = LinearEasing))
        delay(350)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(obsidian),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(fade.value)
        ) {
            Text(
                text = "LIVE TELEVISION",
                color = quiet,
                fontFamily = MonoFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(34.dp))

            // mark: kotak border vermilion + play paper
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(108.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val pad = 2.dp.toPx()
                    drawRoundRect(
                        color = vermilion,
                        topLeft = Offset(pad, pad),
                        size = Size(size.width - pad * 2, size.height - pad * 2),
                        cornerRadius = CornerRadius(24.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    val tri = Path().apply {
                        moveTo(size.width * 0.40f, size.height * 0.32f)
                        lineTo(size.width * 0.70f, size.height * 0.50f)
                        lineTo(size.width * 0.40f, size.height * 0.68f)
                        close()
                    }
                    drawPath(tri, color = paper)
                }
            }
            Spacer(Modifier.height(28.dp))

            Row {
                Text(
                    text = "Ka",
                    color = paper,
                    fontFamily = SansFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 58.sp,
                    letterSpacing = (-2).sp
                )
                Text(
                    text = "T",
                    color = vermilion,
                    fontFamily = SansFont,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 60.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "v",
                    color = vermilion,
                    fontFamily = SansFont,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 60.sp,
                    letterSpacing = (-1).sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "SPORTS  ·  EVENTS  ·  24/7",
                color = quiet,
                fontFamily = MonoFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(42.dp))

            Canvas(Modifier.width(190.dp).height(3.dp)) {
                drawRect(color = surface, size = size)
                drawRect(color = vermilion, size = Size(size.width * progress.value, size.height))
            }
        }
    }
}
