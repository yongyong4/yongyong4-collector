#!/usr/bin/env bash
# VM(Ubuntu 22.04/24.04) 최초 설치. /opt/busdata/.env 를 먼저 올려둔 뒤 sudo 로 실행:
#   sudo bash /opt/busdata/install.sh
set -euo pipefail

APP_DIR=/opt/busdata
[ -f "$APP_DIR/.env" ] || { echo "$APP_DIR/.env 가 없습니다. deploy.sh 로 먼저 올리세요."; exit 1; }
set -a; . "$APP_DIR/.env"; set +a
: "${DB_PASSWORD:?.env 에 DB_PASSWORD 필요}"
# SQL 문자열·셸에 그대로 들어가므로 안전한 문자만 허용
if ! [[ "$DB_PASSWORD" =~ ^[A-Za-z0-9._@%+=-]+$ ]]; then
  echo "DB_PASSWORD 는 영문·숫자·._@%+=- 만 써 주세요 (따옴표·공백·\$ 불가)"; exit 1
fi
if grep -q $'\r' "$APP_DIR/.env"; then
  echo ".env 에 윈도우 줄바꿈(CRLF)이 있습니다. LF 로 바꿔 주세요"; exit 1
fi
DB_NAME=${DB_NAME:-busdata}
DB_USER=${DB_USER:-bus}

echo "== 패키지 설치"
apt-get update -y
DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jre-headless mariadb-server curl

echo "== 시간대 KST"
timedatectl set-timezone Asia/Seoul

# 우분투 자동 보안 업데이트는 기본 06:00~07:00 에 돌며 서비스를 재시작한다 → 평일·주말 수집 시간대 밖(16시대)으로 옮김
echo "== 자동 업데이트 16:00 으로 이동"
mkdir -p /etc/systemd/system/apt-daily-upgrade.timer.d
printf '[Timer]\nOnCalendar=\nOnCalendar=*-*-* 16:00\nRandomizedDelaySec=60m\n' > /etc/systemd/system/apt-daily-upgrade.timer.d/override.conf
systemctl daemon-reload
systemctl restart apt-daily-upgrade.timer

# 1GB 메모리 VM 대비 스왑 1GB
if [ "$(free -m | awk '/Mem:/{print $2}')" -lt 2000 ] && ! swapon --show | grep -q swapfile; then
  echo "== 스왑 1GB 생성"
  fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== MariaDB 설정"
cat > /etc/mysql/mariadb.conf.d/99-busdata.cnf <<CNF
[mysqld]
default-time-zone = '+09:00'
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci
innodb_buffer_pool_size = 128M
bind-address = 127.0.0.1
CNF
systemctl enable --now mariadb
systemctl restart mariadb
mariadb <<SQL
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DB_USER'@'localhost' IDENTIFIED BY '$DB_PASSWORD';
ALTER USER '$DB_USER'@'localhost' IDENTIFIED BY '$DB_PASSWORD';
GRANT ALL ON \`$DB_NAME\`.* TO '$DB_USER'@'localhost';
FLUSH PRIVILEGES;
SQL

echo "== 앱 사용자·디렉터리"
id busdata >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin busdata
mkdir -p "$APP_DIR/logs" "$APP_DIR/backups"
chown -R busdata:busdata "$APP_DIR"
chmod 600 "$APP_DIR/.env"

echo "== systemd 등록"
cp "$APP_DIR/busdata-collector.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable busdata-collector
systemctl restart busdata-collector

echo "== DB 백업 cron (평일 10:40, 주말 15:10 — 수집 종료 후, 텔레그램으로도 전송)"
install -m 644 "$APP_DIR/busdata-backup.cron" /etc/cron.d/busdata-backup

echo "완료. 로그: journalctl -u busdata-collector -f  또는  tail -f $APP_DIR/logs/collector.log"
