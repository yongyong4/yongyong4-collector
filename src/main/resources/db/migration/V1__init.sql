-- 모든 DATETIME 은 KST(Asia/Seoul) 로컬 시각이다.

-- 호출 단위 원본. raw_json 은 응답 JSON 원문(압축), JSON 이 아닌 응답은 raw_text 에 저장
CREATE TABLE api_call_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_name     VARCHAR(50)  NOT NULL,
    api_type     VARCHAR(20)  NOT NULL,           -- LOCATION / ARRIVAL / ROUTE_LIST / ROUTE_STATIONS / STATION_SEARCH
    target_id    VARCHAR(50),                     -- routeId / stationId / 검색어
    called_at    DATETIME(3)  NOT NULL,           -- 수집기 시각
    query_time   DATETIME(3),                     -- API 응답 msgHeader.queryTime
    http_status  INT,
    result_code  INT,
    result_msg   VARCHAR(200),
    ok           BOOLEAN      NOT NULL,
    attempts     INT          NOT NULL,           -- 재시도 포함 실제 호출 수 (일일 한도 집계 기준)
    duration_ms  INT,
    row_count    INT,
    raw_json     JSON,
    raw_text     MEDIUMTEXT,
    error_msg    VARCHAR(1000),
    INDEX idx_call_called (called_at),
    INDEX idx_call_job_type (job_name, api_type, called_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 버스 위치 스냅샷 (1행 = 한 시점의 버스 1대)
CREATE TABLE bus_location (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    call_log_id      BIGINT       NOT NULL,
    collected_at     DATETIME(3)  NOT NULL,
    query_time       DATETIME(3),
    route_id         VARCHAR(20)  NOT NULL,
    route_name       VARCHAR(50),
    plate_no         VARCHAR(20),
    veh_id           VARCHAR(20),
    station_seq      INT,
    station_id       VARCHAR(20),
    state_cd         INT,                         -- 0 교차로통과 / 1 정류소 도착 / 2 정류소 출발
    remain_seat_cnt  INT,                         -- -1 정보 없음
    low_plate        INT,
    crowded          INT,
    route_type_cd    INT,
    tagless_cd       INT,
    INDEX idx_loc_route_plate (route_id, plate_no, collected_at),
    INDEX idx_loc_collected (collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 정류장 도착 예정 스냅샷 (1행 = 한 시점, 한 정류장, 한 노선의 n번째 버스)
CREATE TABLE bus_arrival (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    call_log_id       BIGINT       NOT NULL,
    collected_at      DATETIME(3)  NOT NULL,
    query_time        DATETIME(3),
    station_id        VARCHAR(20)  NOT NULL,
    route_id          VARCHAR(20),
    route_name        VARCHAR(50),
    route_type_cd     INT,
    arrival_order     TINYINT      NOT NULL,      -- 1 | 2
    plate_no          VARCHAR(20),
    veh_id            VARCHAR(20),
    predict_time_min  INT,
    predict_time_sec  INT,
    location_no       INT,                        -- 남은 정류장 수
    remain_seat_cnt   INT,
    low_plate         INT,
    crowded           INT,
    state_cd          INT,
    current_station   VARCHAR(100),               -- stationNm{n}: 버스 현재 위치 정류장명
    flag              VARCHAR(20),
    sta_order         INT,                        -- 이 정류장의 노선 내 순번
    route_dest_name   VARCHAR(100),
    tagless_cd        INT,
    INDEX idx_arr_station_route (station_id, route_id, collected_at),
    INDEX idx_arr_plate (plate_no, collected_at),
    INDEX idx_arr_collected (collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 노선 기준표
CREATE TABLE route_master (
    job_name            VARCHAR(50)  NOT NULL,
    route_id            VARCHAR(20)  NOT NULL,
    route_name          VARCHAR(50)  NOT NULL,
    region              VARCHAR(100),
    start_station       VARCHAR(100),
    end_station         VARCHAR(100),
    route_type_cd       INT,
    company             VARCHAR(100),
    note                VARCHAR(500),
    raw_json            JSON,
    updated_at          DATETIME     NOT NULL,
    PRIMARY KEY (job_name, route_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 노선별 경유 정류장 (분석 시 station_seq ↔ 정류장 매핑용)
CREATE TABLE route_station (
    route_id      VARCHAR(20)  NOT NULL,
    station_seq   INT          NOT NULL,
    station_id    VARCHAR(20)  NOT NULL,
    station_name  VARCHAR(100),
    mobile_no     VARCHAR(20),
    region        VARCHAR(50),
    turn_yn       CHAR(1),
    x             DOUBLE,
    y             DOUBLE,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (route_id, station_seq),
    INDEX idx_rs_station (station_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 정류소 기준표
CREATE TABLE station_master (
    job_name        VARCHAR(50)  NOT NULL,
    station_id      VARCHAR(20)  NOT NULL,
    mobile_no       VARCHAR(20),
    station_name    VARCHAR(100),
    region          VARCHAR(50),
    x               DOUBLE,
    y               DOUBLE,
    direction_note  VARCHAR(1000),                -- 경유 노선·순번·다음 정류장 (방향 확인용)
    updated_at      DATETIME     NOT NULL,
    PRIMARY KEY (job_name, station_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
