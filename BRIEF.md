# KaTv Android TV UI — implementation brief

## Project
`/root/.hermes/projects/bittv-tv` — Android TV, Jetpack Compose, single Activity. No Kotlin source yet — create everything. Keep the package consistent with build.gradle (currently `dev.anistream.app` namespace — you may change it to `dev.bittv.tv` everywhere including manifest if you prefer, but be consistent).

## Design reference (READ THIS FILE — exact exported HTML mockup)
`/root/.hermes/projects/bittv-tv/design-reference.html` — mockup titled "KaTv — Maximalist Live Grid". Reproduce faithfully in Compose.

Design language:
- Colors: bg obsidian `#121211`, surface `#1B1B19`, rule border `#282826`, vermilion `#E03C1F`, paper text `#F4F2ED`, quiet `#94928B`.
- Fonts: Inter (sans) + JetBrains Mono (mono). No runtime font download — use system sans/monospace fallback if exact fonts unavailable.
- Zero gradients/shadows/bubbly cards except the one hero image overlay gradient explicitly in the mockup. Sharp 4px corners, 1px hairlines, 2px progress bars.

## Screen structure (single scrollable screen)
1. **Hero player (16:9)**: background image with top+bottom dark gradient. Top bar: "KaTv" wordmark left, centered mono "2.4M · 1080p", search + cast icons right. Pulsing vermilion LIVE badge top-right. Glass control pill bottom-right: mute, "1080p", PiP, fullscreen — NO pause (live).
2. **Program bar**: mono "CH 01" badge with vermilion border, bold title "Madrid vs Milan", mono subtitle "ARENA · 72:18 elapsed", LIVE tag right. Below: borderless tabs ALL/SPORTS/TV/NEWS/MOVIES, active has 2px vermilion underline.
3. **Section header**: mono uppercase letter-spaced "ALL LIVE CHANNELS", bold "Live channels", mono "01—06" right.
4. **3-column grid of 6 channel cards**: 4:3 thumbnail, mono channel number, LIVE tag, bold channel name, muted program title, mono "72:18 / 90:00", 2px vermilion progress bar at bottom. Active card has vermilion border. Tapping a card switches the hero (image/title/subtitle/progress) and active border — implement this interactivity.
5. **UP NEXT ticker** at bottom: "UP NEXT | The Red Room | 22:00 · MUBI".

The HTML has `data-title` / `data-image` attributes and JS click handlers — mirror that logic in Compose state.

## Splash / loading screen
`app/src/main/res/drawable-nodpi/splash_art.jpg` (1440x2560 KaTv splash art). Show fullscreen ~1400ms on launch via LaunchedEffect delay, then navigate to home. No windowSplashScreen API needed.

## Real data
`app/src/main/assets/channels.json` (1177 channels) + `regions.json` (41 regions). Channel fields (all strings): region, region_name, id, name, tagline, hls, logo, jenis, is_live, is_movie, premium, subtitle, namespace, country_name, t_stamp, s_stamp, header_iptv, header_license, url_license, fake_event. Use kotlinx-serialization. For the 6-card grid, use the first 6 channels with is_live = "t" and map name/tagline/logo; derive a fake channel number and a progress value. Keep the mockup's editorial look even though data is real.

## Player
Clicking hero or a WATCH action opens a player screen: Media3 ExoPlayer + OkHttp DataSource that parses each channel's `header_iptv` JSON string and applies User-Agent / Referer / Origin headers (value "none" means omit that header). Wire DASH/DRM via url_license + header_license where feasible; degrade gracefully with an on-screen error + retry. Subtitle track when `subtitle` non-empty. Add media3-exoplayer-dash + media3-datasource-okhttp 1.5.1 to build.gradle if needed.

## Build config already present
AGP 8.7.3, Kotlin + Compose plugin 2.0.21, compileSdk 35, minSdk 26, Java 17, Compose BOM 2024.12.01, material3, navigation-compose 2.8.5, lifecycle-viewmodel-compose, media3-exoplayer/hls/ui/extractor 1.5.1, okhttp 4.12, kotlinx-serialization-json 1.7.3, coil-compose 2.7.0. AndroidManifest has LEANBACK_LAUNCHER + INTERNET, theme AppTheme in res/values/styles.xml, mipmap launcher icons + banner already installed from user's new icon.

## TV focus
TV-friendly: isFocusable + focusRequester, initial focus on first channel card, visible vermilion focus indicator. No WebView. Single Activity.

## Constraints
- Do NOT modify /root/.hermes/projects/anistream-android or the bittv-scrape data.
- Leave no stray temp files in the project.
- Add .github/workflows/android.yml modeled on /root/.hermes/projects/anistream-android/.github/workflows/android.yml if missing.

## Deliverable
Complete Kotlin source, theme, MainActivity with splash -> home navigation, all Compose screens, README updated. Run ./gradlew :app:assembleDebug. If local SDK is missing (only platform-tools installed), say so explicitly and report exactly what you validated (syntax/imports) vs what you could not compile. List files created and compile errors fixed.
