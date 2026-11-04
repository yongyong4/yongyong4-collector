# Yongyong Collector

용인 대중교통 이동 전략 추천 서비스에 쓸 광역버스 실시간 데이터를 쌓는 수집기입니다.
경기도 버스 API(GBIS)는 과거 기록을 주지 않아서, 정해진 시간대에 버스 위치·남은 좌석·도착 예정 정보를 2분 간격으로 저장합니다.

## 개발 환경

- Java 21
- Spring Boot 3.5.15
- Maven
- MariaDB 10.6+ / Flyway (테이블 자동 생성)
- Spring Web, Spring JDBC (JdbcTemplate)
- 배포: Docker 없이 Ubuntu VM에 jar + systemd

## 수집 대상

| 노선 | routeId | 기점 → 종점 |
|---|---|---|
| 5000 | 228000174 | 남동차고지 → 서울역버스환승센터 |
| 5001A | 228000429 | 남동차고지 → 신논현역 |
| 5003A | 228000431 | 남동차고지 → 신논현역 |
| 5005 | 228000175 | 남동차고지 → 서울역버스환승센터 |
| 5005(거점) | 228000447 | 강남대역.강남대입구 → 서울역버스환승센터 |

정류장(서울 방면): 시청.용인대역(29399), 동백역.성산마을서해그랑블(29808), 강남대역.강남대입구(29307), 기흥역6번출구(29262), 상미마을.신갈오거리(29145)

| 시간대 | 수집 |
|---|---|
| 평일 06:00~10:00 | 위치 + 도착, 2분 간격 |
| 평일 10:02~10:30 | 위치만 (10시 직전 예측의 실제 도착 확인용) |
| 주말 10:00~15:00 | 위치 + 도착, 2분 간격 |

## 초기 실행

1. JDK 21과 MariaDB가 설치돼 있는지 확인합니다: `java -version`, `mariadb --version`
2. DB와 계정을 만듭니다.
   ```bash
   mariadb -u root -e "CREATE DATABASE busdata CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
     CREATE USER 'bus'@'localhost' IDENTIFIED BY '<비밀번호>'; GRANT ALL ON busdata.* TO 'bus'@'localhost';"
   ```
3. 환경변수 파일을 만들고 값을 채웁니다.
   ```bash
   cp .env.example .env
   ```
4. 빌드 후 실행합니다.
   ```bash
   mvn -DskipTests package
   java -jar target/bus-collector.jar
   ```
5. 브라우저에서 확인합니다.
   - http://localhost:8080/ → 대시보드
   - http://localhost:8080/health/collect → 수집 상태

수집 시간대가 아닐 때 바로 1번 수집해 보려면 `curl -X POST localhost:8080/admin/collect`

## 환경변수 (`.env`)

| 이름 | 설명 |
|---|---|
| `DATA_GO_KR_KEY_EXPRESS` | 공공데이터포털 인증키 (Decoding) |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | MariaDB 접속 정보 |
| `ADMIN_TOKEN` | `/admin/**` 호출용 토큰. 서버에서는 반드시 설정 |
| `TELEGRAM_BOT_TOKEN` `TELEGRAM_CHAT_ID` | 선택. 텔레그램 알림·백업 전송 |

공공데이터포털에서 아래 API 활용신청이 필요합니다 (인증키 하나로 공통 사용).
경기도_버스위치정보 조회, 경기도_버스도착정보 조회, 경기도_버스노선 조회, 경기도_정류소 조회

## 조회 API (수집 DB 읽기 전용)

수집 DB에 저장된 값을 돌려주며 GBIS를 호출하지 않습니다. 토큰이 필요 없습니다. 서버 주소는 팀 채널에서 공유합니다.

| 경로 | 설명 |
|---|---|
| `GET /api/latest/locations[?at=yyyy-MM-ddTHH:mm:ss]` | 노선별 최신 버스 위치·남은 좌석 |
| `GET /api/latest/arrivals[?at=…]` | 정류장별 도착 예정 (다음 버스 2대) |
| `GET /api/routes` | 수집 대상 노선·정류장 |
| `GET /export?table=&date=yyyy-MM-dd` | 테이블별 CSV 다운로드 |

수집 시간대 밖에는 데이터가 갱신되지 않으니 응답의 `collected_at`을 확인하세요.

## 테이블

| 테이블 | 내용 |
|---|---|
| `api_call_log` | API 호출 1건과 응답 원문 |
| `bus_location` | 한 시점의 버스 1대 위치·좌석 |
| `bus_arrival` | 한 시점·한 정류장·한 노선의 n번째 도착 예정 |
| `route_master` / `route_station` / `station_master` | 노선·경유 정류장·대상 정류장 기준 정보 |

- 모든 시각은 KST입니다.
- `route_id`로 노선을, `plate_no`로 위치·도착 정보의 같은 버스를 연결합니다.
- 스키마: `src/main/resources/db/migration/V1__init.sql`

## 대시보드

`http://<서버주소>:8080/`에서 실시간 수집 상태를 볼 수 있습니다.

- job별 수집 상태, 오늘 API 호출 수·한도 사용량
- 노선별 버스 위치·남은 좌석, 정류장별 도착 예정
- 과거 기록 보기 (날짜·시각을 골라 2분 단위로 재생)
- 테이블별 CSV 미리보기·다운로드

## 데이터 받기 (분석용)

```bash
# 하루치 전체 CSV → export/2026-10-28/*.csv
./scripts/export.sh 2026-10-28 http://<서버주소>:8080

# 기간 전체를 zip 하나로
curl -o data.zip "http://<서버주소>:8080/export/zip?from=2026-10-28&to=2026-11-13&tables=bus_location,bus_arrival,route_station"
```

## 운영 서버 (Oracle Cloud)

| 항목 | 내용 |
|---|---|
| 클라우드 | Oracle Cloud Always Free, 오사카 리전 |
| 사양 | x86_64, 2 vCPU, 메모리 1GB (+ 스왑), 디스크 45GB |
| OS / 런타임 | Ubuntu 24.04, OpenJDK 21, MariaDB 10.11 |
| 실행 | systemd 서비스 `busdata-collector`, 설치 경로 `/opt/busdata` |
| 네트워크 | 8080(대시보드·조회 API)만 외부 공개. DB(3306)는 서버 내부에서만 접속 가능 |
| 백업 | 평일 10:40, 주말 15:10 DB 전체 덤프 → 서버 보관(14일) + 텔레그램 전송 |
| 수집 시작 | 2026-10-28 |

서버 주소와 SSH 키는 팀 채널에서 공유합니다.

배포 (맥/리눅스에서, 빌드까지 스크립트가 함께 함):
```bash
./deploy/deploy.sh ubuntu@<서버주소> <SSH키경로>
```

- 수집 시간대(평일 06:00~10:30, 주말 10:00~15:00)에는 배포하지 않습니다. 재시작하는 동안 수집이 빠집니다.
- 처음 배포하면 JDK·MariaDB 설치, DB 생성, systemd 등록, 백업 cron까지 자동으로 진행됩니다.
- 상태 확인: `systemctl status busdata-collector`, 로그: `/opt/busdata/logs/collector.log`

## 테스트

```bash
mvn test
```

## 폴더 구조

```
src/main/java/com/yongyongthon/buscollector/
  config/    yml 설정 바인딩, 수집 시간대 판단
  client/    GBIS API 호출·재시도·응답 파싱
  master/    노선번호·정류소번호 → ID 변환
  collect/   매 분 스케줄러, 1주기 수집
  store/     DB 저장 (JdbcTemplate)
  alert/     텔레그램 알림
  web/       대시보드 API, 관리, CSV 내보내기
src/main/resources/
  application.yml          수집 대상·시간대·간격 설정
  db/migration/            Flyway 스키마
  static/index.html        대시보드
deploy/    VM 설치·배포 스크립트, systemd
scripts/   백업, CSV 내보내기
docs/OPERATIONS.md         운영 가이드 (배포, 백업, 알림, 설정 변경 상세)
```
