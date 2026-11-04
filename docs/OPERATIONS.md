# 수집기 운영 가이드

2026 용용톤 교통 트랙 — **"이 버스, 제시간에·자리 있게 탈 수 있나?"** 예측을 위한 원천 데이터 수집기.

경기도 실시간 버스 API(공공데이터포털)는 과거 기록을 주지 않으므로, 평일 아침 출근 시간대의 **버스 위치(남은 좌석)** 와 **정류장 도착 예정 정보**를 2분 간격으로 쌓는다. 분석·모델링은 이 DB를 Python 으로 따로 한다.

- Spring Boot 3.5 / Java 21 / MariaDB / Flyway
- Docker 없이 VM 에 jar + systemd 로 배포

## 수집 대상 (기본 job: `express-morning`)

| 노선 | routeId | 기점 → 종점 |
|---|---|---|
| 5000 | 228000174 | 남동차고지 → 서울역버스환승센터 |
| 5001A | 228000429 | 남동차고지 → 신논현역 |
| 5003A | 228000431 | 남동차고지 → 신논현역 |
| 5005 | 228000175 | 남동차고지 → 서울역버스환승센터 |
| 5005(거점) | 228000447 | 강남대역.강남대입구 → 서울역버스환승센터 |

| 정류소번호 | stationId | 이름 | 비고 |
|---|---|---|---|
| 29399 | 228000745 | 시청.용인대역 | 4개 노선 공통 |
| 29808 | 228000172 | 동백역.성산마을서해그랑블 | 5000·5003A |
| 29307 | 228000684 | 강남대역.강남대입구 | 거점버스 출발지 |
| 29262 | 228000682 | 기흥역6번출구 | |
| 29145 | 228000680 | 상미마을.신갈오거리.롯데캐슬레이시티 | 고속도로 진입 전 마지막 |

(2026-10-27 실제 API 로 변환·확인. 모두 서울 방면 = 노선 회차 지점 이전 구간)

- 평일(월~금) 06:00~10:00, 2분 간격 → 하루 121주기. 한 주기 = 위치 5회(노선별) + 도착 5회(정류장별)
- 위치만 10:02~10:30 추가 수집(job `express-tail`, 15주기) — 10시 직전 예측의 실제 도착을 확인하기 위해
- 하루 호출: 위치 605 + 75 = **680회**, 도착 **605회** (API 별 한도 1,000, 같은 인증키 job 은 합산해 보호)
- 주말(토·일) 10:00~15:00, 2분 간격(job `express-weekend`, 151주기) — 주말 점심 혼잡 확인용. 노선·정류장은 평일과 같고(서울 방면), 하루 위치·도착 각 **755회**. 주말엔 평일 job 이 돌지 않아 한도를 혼자 쓴다
- 공휴일도 수집한다. 공휴일 여부는 분석 단계에서 판단

## 빠른 시작 (로컬)

```bash
# 1) DB 준비 (MariaDB 10.6+)
mariadb -u root -e "CREATE DATABASE busdata CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE USER 'bus'@'localhost' IDENTIFIED BY '<비밀번호>'; GRANT ALL ON busdata.* TO 'bus'@'localhost';"

# 2) 환경변수
cp .env.example .env   # 인증키·DB 비밀번호 입력

# 3) 실행 (테이블은 Flyway 가 자동 생성, master 가 비어 있으면 ID 변환 자동 수행)
mvn -DskipTests package && java -jar target/bus-collector.jar

# 4) 확인
curl -X POST localhost:8080/admin/collect     # 시간대 무관 1주기 즉시 수집
curl localhost:8080/health/collect
```

## 환경변수 (`.env`)

`.env` 는 커밋 금지(.gitignore 에 포함). 앱은 실행 디렉터리의 `.env` 를 자동으로 읽고, VM 에서는 systemd `EnvironmentFile` 로도 주입된다.

| 이름 | 설명 |
|---|---|
| `DATA_GO_KR_KEY_EXPRESS` | 공공데이터포털 일반 인증키(**Decoding**). Encoding 키(% 포함)를 넣어도 동작 |
| `DATA_GO_KR_KEY_CITY` | 시내버스 job 용 (예정) |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | MariaDB 접속 |
| `TELEGRAM_BOT_TOKEN` `TELEGRAM_CHAT_ID` | 텔레그램 알림 (둘 다 있어야 켜짐) |
| `HEALTHCHECK_URL` | 선택. 외부 감시 서비스 ping URL |
| `ADMIN_TOKEN` | 설정 시 `/admin/**` 와 원본 JSON 내보내기(`/export?raw=true`) 에 `X-Admin-Token` 헤더(또는 `?token=`) 필요. 일반 CSV·미리보기는 공개 |

공공데이터포털에서 아래 4개 API 활용신청이 되어 있어야 한다 (하나의 인증키로 공통 사용).
`경기도_버스위치정보 조회(15080648)`, `경기도_버스도착정보 조회(15080346)`, `경기도_버스노선 조회(15080662)`, `경기도_정류소 조회(15080666)`

## 대시보드

`http://localhost:8080/` (운영 서버는 `http://<서버주소>:8080/`). `src/main/resources/static/index.html` 한 파일, 빌드 도구 없음.

- 맨 위 상태 표시: 서버 시각(KST) 시계 + 수집 상태. 초록 점 깜빡임 = 수집 시간대이고 제때 수집 중, 회색 = 대기(다음 수집 시각 표시), 빨강 = 지연 / 서버 응답 없음
- job 별 상태(수집 중 / 지연 / 수집 시간 아님), 오늘 호출 수 / 예상치, 마지막 성공 시각
- 최근 14일 일별 호출·실패·저장 행 — 평일 호출 수가 예상치(605)보다 적으면 노란색
- 노선별 버스 위치: 위 굵은 선 = 서울행(기점→회차), 아래 흐린 선 = 회차 후. ①~⑤ = 수집 대상 정류장. 버스 박스 위 = 차량번호 끝 4자리, 아래 = 남은 좌석(주황 10석 미만, 빨강 만석). 박스를 누르면 그 차량을 **따라가기**(과거 재생 중에도 유지, 도착 예정 표의 같은 차량도 강조). 펼치면 버스 목록 표
- 정류장별 도착 예정(다음 버스 2대), 최근 오류
- CSV 내보내기: 테이블마다 **미리보기**(페이지 안에서 100행씩 표로) / 다운로드
- 인증키·API 별 오늘 호출 한도 사용량 막대 (세로선 = 하루 예상치)
- **과거 기록 보기**: 보는 시점에서 '과거 보기' → 날짜 선택 → 슬라이더/◀▶ 로 2분 단위 수집 시각 이동, '재생'으로 아침 전체를 순서대로 재생. 최근 14일 표의 날짜를 눌러도 이동
- 각 영역 제목의 시각 배지: 실시간은 `HH:MM 수집 · N분 전`(5분 넘으면 주황), 과거 보기는 `MM/DD HH:MM 기록`
- 관리 버튼: **지금 1회 수집**, **ID 다시 변환**(두 번 눌러 실행). `ADMIN_TOKEN` 이 있으면 페이지의 관리 토큰 칸에 입력
- 우측 상단 **사용 방법**: 각 영역 읽는 법을 예시 화면과 함께 안내
- 30초 자동 새로고침. 조회는 `/api/**`(읽기 전용, 토큰 불필요)

## 엔드포인트

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/health/collect` | job·API 별 마지막 성공 시각, 오늘 호출/실패/저장 행 수, 최근 오류 5건 |
| POST | `/admin/init[?job=]` | 노선번호→routeId, 정류소번호→stationId 변환 후 master 저장. 결과 표를 로그로 출력 |
| POST | `/admin/collect[?job=]` | 시간대와 무관하게 1주기 즉시 수집 (일일 한도는 지킴) |
| GET | `/admin/master` | 변환된 노선·정류소 목록 |
| POST | `/admin/alert-test` | 텔레그램 테스트 메시지 |
| GET | `/api/latest/locations[?at=yyyy-MM-ddTHH:mm:ss]` | 노선별 버스 위치. at 을 주면 그 분 이전 3분 안의 마지막 수집 |
| GET | `/api/latest/arrivals[?at=…]` | 정류장별 도착 예정 (대상 노선) |
| GET | `/api/cycles?date=yyyy-MM-dd` | 그날 수집이 돈 시각 목록 |
| GET | `/api/quota` | 인증키·API 별 오늘 호출 수 / 하루 예상 / 한도 (같은 키 job 합산) |
| GET | `/export/preview?table=&date=&offset=0&limit=100` | CSV 와 같은 내용을 JSON 으로 일부만 (최대 500행) + 전체 행 수. `raw=true` 일 때만 토큰 필요 |
| GET | `/export/zip?from=2026-10-28&to=2026-11-13&tables=bus_location,bus_arrival,route_station[&excel=true]` | 기간의 일자별 CSV 를 zip 하나로 (`bus_location_2026-10-28.csv` …). 행이 없는 날은 빠지고, 기준표는 한 파일. 최대 92일 |
| GET | `/export?table=&date=yyyy-MM-dd` | CSV 다운로드. `bus_location` `bus_arrival` `api_call_log` 은 date 필수, `route_master` `route_station` `station_master` 는 전체. `raw=true` 면 api_call_log 원본 포함, `excel=true` 면 BOM 추가 |

하루치를 한 번에 받기: `./scripts/export.sh 2026-10-28` → `export/2026-10-28/*.csv`

## 테이블

모든 `DATETIME` 은 **KST 로컬 시각**이다 (DB 세션·JVM 모두 +09:00 고정).

| 테이블 | 1행 | 주요 컬럼 |
|---|---|---|
| `api_call_log` | API 호출 1건 | `api_type`, `target_id`, `called_at`, `query_time`, `ok`, `result_code`, `attempts`(재시도 포함), `row_count`, **`raw_json`(응답 원문)**, `raw_text`(JSON 아닌 응답), `error_msg` |
| `bus_location` | 한 시점의 버스 1대 | `collected_at`, `route_id`, `plate_no`, `station_seq`, `station_id`, `state_cd`(0 교차로통과/1 도착/2 출발), `remain_seat_cnt`(-1 정보없음), `low_plate`, `crowded`, `call_log_id` |
| `bus_arrival` | 한 시점·한 정류장·한 노선의 n번째 버스 | `collected_at`, `station_id`, `route_id`, `route_name`, `arrival_order`(1\|2), `plate_no`, `predict_time_min`, `predict_time_sec`, `location_no`(남은 정류장 수), `remain_seat_cnt`, `current_station`, `sta_order`, `flag` |
| `route_master` | job 의 대상 노선 | `route_id`, `route_name`, `region`, `start_station`, `end_station`, `raw_json` |
| `route_station` | 노선의 경유 정류장 | `route_id`, `station_seq`, `station_id`, `station_name`, `mobile_no`, `turn_yn` |
| `station_master` | job 의 대상 정류장 | `station_id`, `mobile_no`, `station_name`, `direction_note`(경유 노선·순번·다음 정류장) |

분석 시 알아둘 점
- **`plate_no` 가 위치정보와 도착정보를 같은 버스로 잇는 키.**
- 시각 기준은 **`collected_at`** 을 쓴다. API `query_time` 은 같은 순간 호출끼리도 수 초씩 어긋난다(서버별 시계/캐시 차이로 보임).
- 도착정보는 정류장을 지나는 **모든 노선**을 저장한다. 대상 노선만 보려면 `route_id IN (SELECT route_id FROM route_master)`.
- `bus_location.station_seq` 는 `route_station(route_id, station_seq)` 로 정류장 이름에 붙인다.
- 파싱 실수·필드 누락이 의심되면 `api_call_log.raw_json` 을 다시 파싱하면 된다 (`call_log_id` 로 연결).
- 운행 버스가 없으면 API 가 `resultCode=4`(결과 없음)를 준다. 실패가 아니라 0행 성공으로 기록된다 (예: 5005(거점)은 06:40~07:20 외에는 항상 4).

```python
import pandas as pd, sqlalchemy as sa
eng = sa.create_engine("mysql+pymysql://bus:PASSWORD@localhost:3306/busdata")
loc = pd.read_sql("SELECT * FROM bus_location WHERE collected_at >= '2026-10-28' AND collected_at < '2026-10-29'", eng)
```

## 설정 / 수집 대상 추가

모든 대상·시간대·간격·인증키 이름은 `src/main/resources/application.yml` 의 `collector.jobs` 에 있다. job 은 서로 독립적이다 (인증키·한도·시간대·간격이 다름).

```yaml
collector:
  jobs:
    - name: city-daytime              # 고유 이름 (DB 의 job_name)
      enabled: true
      api-key-env: DATA_GO_KR_KEY_CITY # 인증키를 담은 환경변수 이름
      daily-limit: 1000                # API 종류별 하루 호출 한도 (도달 시 건너뛰고 기록)
      days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
      start: "07:00"
      end: "19:00"                     # 포함
      interval-minutes: 5
      collect: [LOCATION, ARRIVAL]     # 위치 / 도착
      routes:
        - { name: "21", region: "용인" }             # 노선번호 + 걸러낼 조건(지역·기점 '포함')
        - { name: "31-1", route-id: "228000xxx" }   # 모호하면 routeId 직접 지정
      stations:
        - { mobile-no: "29808", name: "동백역" }      # 화면 정류소번호. 대상 노선 경유 목록에서 찾음
        - { mobile-no: "12345", station-id: "228000xxx" }
```

추가 후 순서
1. `.env` 에 새 키 추가 → 재시작 (master 가 비어 있는 job 은 기동 시 자동 변환)
2. 로그의 `===== master 초기화 [job] =====` 표에서 routeId·정류장 이름·**다음 정류장(방향)** 확인
3. 동명 노선이 여러 개면 표에 후보 목록이 찍힌다 → `route-id` 를 직접 적고 `POST /admin/init?job=...`

정류소는 **대상 노선의 경유 정류장 목록에서 mobileNo 로 찾기** 때문에 진행 방향이 자동으로 맞다 (예: 강남대역 서울방면 29307 / 반대 29306 은 순번이 다름). 회차 이후 구간이면 경고가 뜬다. 경유 목록에 없으면 정류소 검색 API 로 대체하고 "방향 확인 필요" 경고를 남긴다.

> 주의: `--collector.jobs[0].start=...` 처럼 명령줄·환경변수로 job 리스트의 일부만 덮어쓰면 Spring Boot 는 **리스트 전체를 교체**한다(나머지 필드가 비게 됨). 시간대 변경은 yml 을 고치거나 `--spring.config.location=file:my.yml` 로 전체 설정 파일을 지정한다.

API 엔드포인트 경로(`collector.api.paths`), 타임아웃, 재시도 횟수도 같은 파일에 있다.

## 동작 방식

- 매 분 0초(Asia/Seoul)에 깨어나 job 별로 요일·시간대·간격이 맞으면 별도 스레드에서 1주기 실행. 같은 job 은 동시에 1주기만.
- 호출은 순차. 연결 5초 / 응답 10초 타임아웃, 실패 시 1초 후 1회 재시도. HTTP 상태와 `resultCode` 모두 검사 (0 정상, 4 결과없음 = 성공).
- 인증키 미등록·한도초과 같은 게이트웨이 오류(`OpenAPI_ServiceResponse`)는 재시도하지 않는다.
- 실패해도 `api_call_log` 에 기록하고 다음 대상으로 넘어간다. 주기 시간(간격-15초)을 넘기면 남은 호출은 "건너뜀"으로 기록.
- 매 호출 전 오늘 호출 수(재시도 포함)를 집계해 `daily-limit` 에 도달하면 건너뜀.
- 일일 요약 로그: 매일 10:10, 23:10 (`collector.summary-cron`). 매 주기 끝에도 한 줄 요약.
- 인증키는 로그에 남지 않는다.

## 텔레그램 알림

`.env` 에 `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` 가 있으면 켜진다 (없으면 조용히 생략). 확인: 대시보드 관리 → **텔레그램 알림 테스트** 또는 `POST /admin/alert-test`.

| 언제 | 메시지 |
|---|---|
| 앱 시작/재시작 | 🔄 수집기 시작됨 · 작업 목록 · 다음 수집 시각 |
| 그날 첫 정기 주기 | ▶️ 수집 시작 06:00 · 호출/실패/저장 행 (중간에 재시작했으면 "재시작 후 첫 수집") |
| 그날 마지막 정기 주기 | ✅ 수집 종료 · API 별 호출/예상, 실패, 저장 행. 부족하거나 실패가 있으면 ⚠️ |
| 같은 대상 3회 연속 실패 | ⚠️ 5000번 위치 3회 연속 실패 + 마지막 오류 (1번만) |
| 그 대상 회복 | ✅ 회복 · 중단 시간 |
| 인증키 미등록·한도 초과 등 포털 오류 | 🚨 즉시 (같은 경고는 30분에 1번) |
| 한도 90% / 100% | ⚠️ / 🚨 (하루 1번) |
| 주기 멈춤 감지, 인증키 없음 | 🚨 |

- 수동 수집(`/admin/collect`)은 시작·종료 알림을 보내지 않는다. 실패 알림은 보낸다.
- 앱이나 VM 이 통째로 죽으면 앱이 알림을 보낼 수 없다. 대비하려면 healthchecks.io 같은 외부 감시에 체크를 만들고(주기 2분, 유예 10분 정도, 알림 채널에 텔레그램 연결) 그 ping URL 을 `HEALTHCHECK_URL` 에 넣는다. 정기 주기마다 ping 한다. 단, 수집 시간대 밖에는 ping 이 없으니 감시 스케줄을 평일 06:00~10:30 으로 맞춘다.
- 봇 토큰이 노출됐다면 @BotFather 에서 `/revoke` 로 새로 발급하고 `.env` 를 바꾼다.

## VM 배포 (Ubuntu, Docker 없음)

오라클 클라우드 / AWS 프리티어 Ubuntu 22.04·24.04 기준. 1GB 메모리 VM 이면 스왑 1GB 를 자동으로 만든다.

```bash
# 맥에서. .env 에 DB_PASSWORD, ADMIN_TOKEN 을 실제 값으로 넣어둘 것
./deploy/deploy.sh ubuntu@<VM_IP> ~/.ssh/<key>.pem
```

- 최초 실행: jar·.env·스크립트 업로드 → `install.sh` (OpenJDK 21, MariaDB 설치, 시간대 KST, DB·계정 생성, systemd 등록, 백업 cron)
- 이후 실행: jar 교체 후 재시작. `.env` 를 바꿨으면 `UPLOAD_ENV=1 ./deploy/deploy.sh ...` 로 함께 올린다
- `DB_PASSWORD` 는 영문·숫자·`._@%+=-` 만 (install.sh 가 검사). `.env` 는 LF 줄바꿈
- systemd `Restart=always` + `enable` 이라 VM 재부팅·프로세스 종료 시 자동 재시작. 재시작 후 다음 분부터 수집 재개.

VM 에서 확인
```bash
systemctl status busdata-collector
tail -f /opt/busdata/logs/collector.log
curl -s localhost:8080/health/collect
```

운영 서버는 8080 이 외부에 열려 있다 (조회 API·대시보드 공개, `/admin/**`·raw 내보내기는 `ADMIN_TOKEN` 필요). 8080 을 닫은 경우 맥에서 SSH 터널로 접근:
```bash
ssh -L 8080:localhost:8080 ubuntu@<VM_IP>
./scripts/export.sh 2026-10-28 http://localhost:8080 <ADMIN_TOKEN>
```

### 백업

`scripts/backup.sh` 가 평일 10:40·주말 15:10 (cron `deploy/busdata-backup.cron`, 수집 종료 후) `/opt/busdata/backups/busdata_YYYY-MM-DD_HHMM.sql.gz` 를 만들고 14일 지난 파일은 지운다.
`.env` 에 `TELEGRAM_BOT_TOKEN`·`TELEGRAM_CHAT_ID` 가 있으면 **같은 파일을 텔레그램으로도 보낸다** (오라클 무료 VM 회수·디스크 장애 대비). 전체 덤프라 가장 최근 파일 하나만 있으면 된다. 45MB 를 넘으면 조각(`.part-aa`, `.part-ab` …)으로 나눠 보낸다.

수동 실행·로그: `sudo bash /opt/busdata/backup.sh`, `/opt/busdata/logs/backup.log`. VM 에서 직접 내려받기:
```bash
scp ubuntu@<VM_IP>:/opt/busdata/backups/*.sql.gz ./backups/
```
복원 (새 VM 이면 `deploy.sh` 로 설치를 먼저 마친 뒤, 앱을 멈추고):
```bash
sudo systemctl stop busdata-collector
cat busdata_xxx.sql.gz.part-* > busdata_xxx.sql.gz   # 조각으로 받은 경우만
gunzip -c busdata_xxx.sql.gz | mariadb -u bus -p busdata
sudo systemctl start busdata-collector
```

## 디렉터리

```
src/main/java/com/yongyongthon/buscollector
├── config/   CollectorProperties(yml 바인딩), SchedulePolicy(요일·시간대·간격 판단)
├── client/   GbisClient(호출·재시도·resultCode 검사), 응답 파싱 유틸
├── master/   MasterInitService(노선·정류소 ID 변환)
├── collect/  CollectScheduler(매 분 tick), CollectService(1주기 수집)
├── store/    JdbcTemplate 저장소
└── web/      health / admin / export / 대시보드 API
src/main/resources/static/index.html   대시보드
src/main/resources/db/migration   Flyway 스키마
deploy/   install.sh, deploy.sh, systemd unit
scripts/  backup.sh, export.sh
```
