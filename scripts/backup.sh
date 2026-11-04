#!/usr/bin/env bash
# DB 전체 덤프(gzip). 14일 지난 백업은 삭제. VM 에서는 install.sh 가 cron 으로 평일 10:40 실행.
# TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID 가 있으면 텔레그램으로도 보낸다 (VM 이 사라져도 데이터가 남도록).
# 텔레그램 봇 파일 한도(50MB)를 넘으면 45MB 조각으로 나눠 보낸다. 복원: cat 조각* > x.sql.gz
set -euo pipefail
APP_DIR=${APP_DIR:-/opt/busdata}
set -a; . "$APP_DIR/.env"; set +a
mkdir -p "$APP_DIR/backups"
NAME="busdata_$(date +%F_%H%M).sql.gz"
OUT="$APP_DIR/backups/$NAME"
mariadb-dump --single-transaction --quick -u "${DB_USER:-bus}" -p"${DB_PASSWORD:-}" "${DB_NAME:-busdata}" | gzip > "$OUT"
find "$APP_DIR/backups" -name 'busdata_*.sql.gz' -mtime +14 -delete
SIZE=$(wc -c < "$OUT" | tr -d ' ')
echo "$(date '+%F %T') backup ok: $OUT (${SIZE} bytes)"

if [ -z "${TELEGRAM_BOT_TOKEN:-}" ] || [ -z "${TELEGRAM_CHAT_ID:-}" ]; then
  exit 0
fi

send() { # $1 파일, $2 캡션
  curl -sS -f --max-time 300 \
    -F "chat_id=$TELEGRAM_CHAT_ID" -F "document=@$1" -F "caption=$2" \
    "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendDocument" > /dev/null
}

MB=$(awk "BEGIN {printf \"%.1f\", $SIZE/1048576}")
LIMIT=$((45 * 1024 * 1024))
if [ "$SIZE" -le "$LIMIT" ]; then
  send "$OUT" "[버스수집] 💾 DB 백업 $(date '+%m/%d %H:%M') · ${MB}MB
복원: gunzip -c $NAME | mariadb -u bus -p busdata"
else
  TMP=$(mktemp -d)
  split -b 45m "$OUT" "$TMP/$NAME.part-"
  PARTS=$(ls "$TMP" | wc -l | tr -d ' ')
  i=0
  for p in "$TMP"/*; do
    i=$((i + 1))
    send "$p" "[버스수집] 💾 DB 백업 $(date '+%m/%d %H:%M') · ${MB}MB · 조각 $i/$PARTS
복원: cat $NAME.part-* > $NAME 후 gunzip -c $NAME | mariadb -u bus -p busdata"
  done
  rm -rf "$TMP"
fi
echo "$(date '+%F %T') telegram backup sent"
