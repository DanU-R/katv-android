#!/usr/bin/env bash
# Auto install APK KaTv terbaru ke VM begitu cooldown selesai + verifikasi UI event.
# Dipanggil cron tiap 5 menit; akan exit 0 diam-diam kalau VM masih suspended.
set -uo pipefail
KEY=$(grep -m1 '^INSTABOX_API_KEY=' /root/.hermes/.env | cut -d= -f2- | tr -d '"')
MID=m_agq3apocymavez7mk4qzspg44q
BASE="https://api.instabox.sh/v1/machines/$MID"
APK=/root/.hermes/projects/bittv-tv/app/build/outputs/apk/debug/app-debug.apk
LOG=/root/.hermes/projects/bittv-tv/output/auto_install.log
DONE=/root/.hermes/projects/bittv-tv/output/.auto_install_done

[ -f "$DONE" ] && exit 0

STATE=$(curl -s -m 15 -H "Authorization: Bearer $KEY" "$BASE" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("state","?"))' 2>/dev/null)
echo "$(date -u +%FT%TZ) state=$STATE" >> "$LOG"
[ "$STATE" = "running" ] || exit 0

FN="/tmp/katv_auto_$(date +%s).apk"
RC=$(curl -s -o /dev/null -w '%{http_code}' -m 240 -X PUT -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/octet-stream' --data-binary "@$APK" "$BASE/files?path=$FN&mode=644")
echo "$(date -u +%FT%TZ) PUT=$RC" >> "$LOG"
[ "$RC" = "201" ] || exit 0

OUT=$(curl -s -m 150 -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -X POST "$BASE/exec" \
  -d "{\"cmd\":\"adb -s 127.0.0.1:5555 install -r $FN 2>&1 | tail -1; adb -s 127.0.0.1:5555 shell am force-stop dev.katv.tv; adb -s 127.0.0.1:5555 shell am start -n dev.katv.tv/.MainActivity >/dev/null 2>&1; sleep 18; adb -s 127.0.0.1:5555 shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb -s 127.0.0.1:5555 shell cat /sdcard/u.xml | tr '<>' '\\n\\n' | grep -oE 'text=\\\"[^\\\"]+\\\"' | grep -v 'text=\\\"\\\"' | head -60\",\"timeoutSeconds\":120}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("stdout",""))' 2>/dev/null)
echo "$OUT" >> "$LOG"
echo "$(date -u +%FT%TZ) DONE" >> "$LOG"
touch "$DONE"
