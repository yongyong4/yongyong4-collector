#!/usr/bin/env bash
# 로컬(맥)에서 빌드 후 VM 에 올리고 재시작한다.
#   ./deploy/deploy.sh ubuntu@1.2.3.4 [ssh 키 경로]
# 최초 1회는 .env 도 올리고 install.sh 를 실행한다. 이후 .env 를 바꿨으면 UPLOAD_ENV=1 ./deploy/deploy.sh ...
set -euo pipefail

HOST=${1:?사용법: deploy.sh user@host [키파일]}
KEY=${2:-}
SSH_OPTS=()
if [ -n "$KEY" ]; then SSH_OPTS=(-i "$KEY"); fi
ROOT=$(cd "$(dirname "$0")/.." && pwd)

echo "== 빌드"
(cd "$ROOT" && mvn -q -DskipTests package)

echo "== 업로드"
UP=/tmp/busdata-upload
ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "rm -rf $UP && mkdir -p $UP"
FILES=("$ROOT/target/bus-collector.jar" "$ROOT/deploy/busdata-collector.service" "$ROOT/deploy/install.sh" "$ROOT/deploy/busdata-backup.cron" "$ROOT/scripts/backup.sh")
FIRST=false
if ! ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "test -f /etc/systemd/system/busdata-collector.service"; then
  FIRST=true
  [ -f "$ROOT/.env" ] || { echo ".env 가 없습니다 (.env.example 참고)"; exit 1; }
  FILES+=("$ROOT/.env")
elif [ "${UPLOAD_ENV:-0}" = "1" ]; then
  echo "== .env 도 교체 (UPLOAD_ENV=1)"
  FILES+=("$ROOT/.env")
fi
scp ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "${FILES[@]}" "$HOST:$UP/"
ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "sudo mkdir -p /opt/busdata && sudo cp -a $UP/. /opt/busdata/ && rm -rf $UP"

if $FIRST; then
  echo "== 최초 설치 (install.sh)"
  ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "sudo bash /opt/busdata/install.sh"
else
  echo "== 재시작"
  ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "sudo chown -R busdata:busdata /opt/busdata && sudo install -m 644 /opt/busdata/busdata-backup.cron /etc/cron.d/busdata-backup && sudo systemctl restart busdata-collector"
fi

echo "== 상태"
sleep 15
ssh ${SSH_OPTS[@]+"${SSH_OPTS[@]}"} "$HOST" "systemctl is-active busdata-collector; curl -s localhost:8080/health/collect | head -c 600; echo"
