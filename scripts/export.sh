#!/usr/bin/env bash
# 하루치 CSV 를 한 번에 받는다.
#   ./scripts/export.sh 2026-10-28 [http://localhost:8080] [ADMIN_TOKEN]
# VM 은 SSH 터널로: ssh -L 8080:localhost:8080 ubuntu@VM  후 실행
set -euo pipefail
DATE=${1:?날짜 yyyy-MM-dd}
BASE=${2:-http://localhost:8080}
TOKEN=${3:-${ADMIN_TOKEN:-}}
OUT=export/$DATE
mkdir -p "$OUT"
for t in bus_location bus_arrival api_call_log; do
  curl -sf -H "X-Admin-Token: $TOKEN" "$BASE/export?table=$t&date=$DATE" -o "$OUT/$t.csv"
done
for t in route_master route_station station_master; do
  curl -sf -H "X-Admin-Token: $TOKEN" "$BASE/export?table=$t" -o "$OUT/$t.csv"
done
wc -l "$OUT"/*.csv
