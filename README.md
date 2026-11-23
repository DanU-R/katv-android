# KaTv — Android TV (Jetpack Compose)

UI Android TV untuk KaTv, dibangun dari nol mengikuti `design-reference.html` (export lengkap draft Superdesign "KaTv — Maximalist Live Grid") sebagai source of truth token warna, tipografi, layout, dan logika klik.

## Yang dibangun

- **Splash** ~1400 ms memakai `res/drawable-nodpi/splash_art.jpg` (artwork asli 1440×2560, bukan dibuat ulang).
- **Satu layar home Compose** (single scrollable screen), urutan elemen persis mockup:
  1. Hero 16:9 + gradient (top/bottom) + wordmark KaTv, mono `2.4M · 1080p`, ikon search/cast, badge LIVE berdenyut, control pill (mute / 1080p / PiP / fullscreen — **tanpa pause**).
  2. Program bar: badge `CH 01` border vermilion, judul, subtitle mono, tag LIVE; tabs ALL / SPORTS / TV / NEWS / MOVIES dengan underline 2px vermilion.
  3. Section header `ALL LIVE CHANNELS` / `Live channels` / `01—06`.
  4. Grid 3 kolom × 2 baris (6 card) dengan thumbnail 4:3, nomor mono, LIVE, nama channel, program muted, `elapsed / duration` mono, progress bar 2px. Klik card mengganti hero (gambar + judul) dan border aktif — sama seperti handler JS di mockup.
  5. Ticker `UP NEXT | The Red Room | 22:00 · MUBI`.
- **TV focus**: `isFocusable` + `FocusRequester` pada card pertama, indikator focus vermilion.
- **Player Media3 ExoPlayer**: `OkHttpDataSource` menyuntik semua header dari `header_iptv` (`"none"` → header dihapus), dukungan HLS / DASH / progressive, DRM Widevine/ClearKey via `url_license` + `header_license`, subtitle saat field `subtitle` tidak kosong, overlay error + tombol RETRY.

## Token desain (dari tailwind.config di design-reference.html)

| token | hex |
|---|---|
| obsidian | `#121211` |
| surface | `#1B1B19` |
| rule | `#282826` |
| vermilion | `#E03C1F` |
| paper | `#F4F2ED` |
| quiet | `#94928B` |

Font: Inter (sans) + JetBrains Mono (mono), fallback `sans-serif`/`monospace` bila font tidak terpasang (tidak ada runtime font download). Tanpa gradient kecuali hero, tanpa shadow, sudut 4px, hairline 1px, progress 2px.

## Data

`app/src/main/assets/channels.json` (1177 channel, 20 field string) + `regions.json` (41 region). Enam card memakai **6 channel `is_live = "t"` pertama** dari data asli (nama, tagline, logo, HLS, header). Judul editorial / gambar hero / persentase progress (72/48/31/59/62/49%) diambil persis dari atribut `data-title` / `data-image` dan teks card di mockup.

## Build

```bash
cd /root/.hermes/projects/bittv-tv
./gradlew :app:assembleDebug
```

SDK lokal di VPS hanya punya `platform-tools` (tanpa Android platform 35), jadi build lokal bisa gagal pada tahap SDK lookup. CI GitHub di `.github/workflows/android.yml` meng-install platform 35 + build-tools 35.0.0 dan menjalankan `:app:testDebugUnitTest :app:assembleDebug`.

## Paket

Namespace & applicationId: `dev.anistream.app` (konsisten dengan build.gradle dan AndroidManifest yang sudah ada).

## Kontrak player

1. `header_iptv` wajib dikirim apa adanya ke request stream; nilai `"none"` → jangan dikirim.
2. `jenis` menentukan tipe source: `hls` → HLS, `dash*` → DASH + EME, `ts` → progressive.
3. `dash-widevine`, `dash-clearkey`, `drm_rcti_plus` → `DefaultDrmSessionManager` dengan `url_license` + `header_license`.
4. `subtitle` non-kosong → daftarkan subtitle track.
5. `fake_event=t` disimpan di model untuk filtering di masa depan (tidak ditampilkan di grid 6-card).

Sumber data mentah & laporan lengkap: `/root/.hermes/projects/anistream-android/output/bittv-scrape/REPORT.md` (read-only, tidak diubah).
