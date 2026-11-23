# AniStream Design System

## Product
AniStream is a native Android anime streaming app. Users browse an anime catalog, search, view details with episode lists, and watch via native VOD player with timeline/seek. Target: Indonesian anime fans, mobile-first.

## Key Screens
1. **Home** — Hero featured anime, search bar, "Popular Now" poster grid, bottom nav
2. **Detail** — Cover art, title, synopsis, episode list with play buttons
3. **Player** — VOD timeline, play/pause, forward/back 30s, quality selector, fullscreen
4. **Favorites** — Saved series list
5. **History** — Watch history with progress

## Visual Style

### Colors
- Primary bg: `#0A0E17` (deep midnight)
- Card bg: `#111827` (dark navy)
- Surface: `#1A1F2E` (slightly lighter)
- Accent/Cyan: `#00C8E0` (bright cyan)
- Accent dim: `#0891B2` (darker cyan for hover)
- Text primary: `#F1F5F9` (near white)
- Text muted: `#94A3B8` (slate)
- Live red: `#EF4444` (only for LIVE badge)
- Divider: `#1E293B`

### Typography
- Font: Inter (sans-serif)
- Hero title: 28px bold
- Card title: 14px semibold
- Body: 13px regular
- Caption: 11px regular
- Episode number: 12px medium

### Spacing
- Screen padding: 16px horizontal
- Card gap: 12px
- Section gap: 24px
- Poster grid: 3 columns, 8px gap

### Cards
- Poster card: 16:10 aspect ratio, 6px radius, subtle shadow
- Episode row: full width, 56px height, right-aligned play button
- Border radius: 8px for cards, 6px for buttons

### Bottom Nav
- Height: 58px
- Icons + labels, active state = cyan, inactive = slate
- 4 items: Home, Search, Favorites, History

### Player
- Dark overlay controls
- Timeline: 4px height, cyan scrubber
- Control buttons: 44dp touch target
- Quality badge: small pill, cyan bg

### States
- Loading: shimmer skeleton matching card shapes
- Empty: centered icon + message
- Error: inline retry chip
- Selected: cyan border + subtle glow

## Motion
- Page transitions: fade through (200ms)
- Poster hover: scale 1.03
- Player controls: fade in/out 300ms

## Thumbnail Rules
- Poster cards: MAX 16:10 aspect ratio, 3 columns per row = ~100dp wide
- DO NOT use full-width hero-sized cards for grid items
- Grid items should fit at least 3 per row on mobile
- Episode rows: compact, single line, play button right-aligned