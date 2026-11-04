package com.yongyongthon.buscollector.web;

import com.yongyongthon.buscollector.config.ApiType;
import com.yongyongthon.buscollector.config.CollectorProperties;
import com.yongyongthon.buscollector.config.CollectorProperties.Job;
import com.yongyongthon.buscollector.config.SchedulePolicy;
import com.yongyongthon.buscollector.store.CallLogRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** static/index.html 대시보드용 조회 API (읽기 전용) */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final CollectorProperties props;
    private final JdbcTemplate jdbc;
    private final CallLogRepository callLog;
    private final ZoneId zone;

    public DashboardController(CollectorProperties props, JdbcTemplate jdbc, CallLogRepository callLog) {
        this.props = props;
        this.jdbc = jdbc;
        this.callLog = callLog;
        this.zone = ZoneId.of(props.zone());
    }

    /** job 설정 요약과 하루 예상 호출 수 */
    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs() {
        return props.jobs().stream().map(j -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", j.name());
            m.put("enabled", j.enabled());
            m.put("days", j.days());
            m.put("start", j.start());
            m.put("end", j.end());
            m.put("intervalMinutes", j.intervalMinutes());
            m.put("collect", j.collect());
            m.put("routes", j.routes().stream().map(CollectorProperties.RouteTarget::name).toList());
            m.put("stations", j.stations().stream().map(s -> s.mobileNo() + " " + s.name()).toList());
            m.put("dailyLimit", j.dailyLimit());
            m.put("expectedPerDay", expected(j));
            m.put("nextRun", j.enabled() ? SchedulePolicy.nextRun(j, LocalDateTime.now(zone)) : null);
            return m;
        }).toList();
    }

    /** 인증키별 오늘 호출 수(재시도 포함) / 오늘 예상 / 한도. 같은 키를 쓰는 job 은 합산 (예상은 오늘 도는 job 만) */
    @GetMapping("/quota")
    public List<Map<String, Object>> quota() {
        LocalDate today = LocalDate.now(zone);
        Map<String, List<Job>> byKey = new LinkedHashMap<>();
        for (Job j : props.jobs()) {
            if (j.enabled()) {
                byKey.computeIfAbsent(j.apiKeyEnv(), k -> new java.util.ArrayList<>()).add(j);
            }
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        byKey.forEach((key, jobs) -> {
            List<String> names = jobs.stream().map(Job::name).toList();
            for (ApiType type : List.of(ApiType.LOCATION, ApiType.ARRIVAL)) {
                List<Job> using = jobs.stream().filter(j -> j.collect().contains(type)).toList();
                if (using.isEmpty()) {
                    continue;
                }
                List<Job> usingToday = using.stream().filter(j -> SchedulePolicy.runsOn(j, today)).toList();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("apiKeyEnv", key);
                m.put("jobs", usingToday.stream().map(Job::name).toList());
                m.put("apiType", type);
                m.put("used", callLog.attemptsOn(names, type, today));
                m.put("expected", usingToday.stream().mapToInt(j -> expected(j).getOrDefault(type.name(), 0)).sum());
                m.put("limit", using.stream().mapToInt(Job::dailyLimit).min().orElse(0));
                out.add(m);
            }
        });
        return out;
    }

    /** 최근 n일 API 별 호출/실패/저장 행 수 */
    @GetMapping("/daily")
    public List<Map<String, Object>> daily(@RequestParam(defaultValue = "14") int days) {
        LocalDate from = LocalDate.now(zone).minusDays(Math.min(Math.max(days, 1), 60) - 1L);
        return jdbc.queryForList("""
                SELECT DATE_FORMAT(called_at, '%Y-%m-%d') AS day, job_name, api_type,
                       COUNT(*) AS calls,
                       SUM(CASE WHEN ok THEN 0 ELSE 1 END) AS failures,
                       COALESCE(SUM(row_count), 0) AS saved_rows
                FROM api_call_log
                WHERE called_at >= ? AND api_type IN ('LOCATION', 'ARRIVAL')
                GROUP BY day, job_name, api_type
                ORDER BY day DESC, job_name, api_type
                """, Timestamp.valueOf(from.atStartOfDay()));
    }

    /** 해당 날짜에 수집이 돈 시각(분 단위) 목록 — 과거 보기 슬라이더용 */
    @GetMapping("/cycles")
    public List<Map<String, Object>> cycles(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return jdbc.queryForList("""
                SELECT DATE_FORMAT(called_at, '%H:%i') AS t, COUNT(*) AS calls,
                       SUM(CASE WHEN ok THEN 1 ELSE 0 END) AS ok
                FROM api_call_log
                WHERE api_type IN ('LOCATION', 'ARRIVAL') AND called_at >= ? AND called_at < ?
                GROUP BY t ORDER BY t
                """, Timestamp.valueOf(date.atStartOfDay()), Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }

    /** 노선별 버스 위치. at 이 없으면 가장 최근, 있으면 그 시각(분) 이전 3분 안의 마지막 성공 호출 */
    @GetMapping("/latest/locations")
    public List<Map<String, Object>> latestLocations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        Window w = window(at);
        return jdbc.queryForList("""
                SELECT l.route_id, l.route_name, l.plate_no, l.station_seq, rs.station_name, l.state_cd,
                       l.remain_seat_cnt, l.low_plate, l.crowded, l.collected_at
                FROM bus_location l
                JOIN (SELECT target_id, MAX(id) AS id FROM api_call_log
                      WHERE api_type = 'LOCATION' AND ok = TRUE AND called_at >= ? AND called_at < ?
                      GROUP BY target_id) c ON c.id = l.call_log_id
                LEFT JOIN route_station rs ON rs.route_id = l.route_id AND rs.station_seq = l.station_seq
                ORDER BY l.route_name, l.station_seq
                """, w.from(), w.to());
    }

    /** 정류장별 도착 예정 (대상 노선만). at 규칙은 위치와 같다 */
    @GetMapping("/latest/arrivals")
    public List<Map<String, Object>> latestArrivals(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        Window w = window(at);
        return jdbc.queryForList("""
                SELECT s.mobile_no, s.station_name, a.station_id, a.route_name, a.arrival_order, a.plate_no,
                       a.predict_time_min, a.location_no, a.remain_seat_cnt, a.current_station, a.collected_at
                FROM bus_arrival a
                JOIN (SELECT target_id, MAX(id) AS id FROM api_call_log
                      WHERE api_type = 'ARRIVAL' AND ok = TRUE AND called_at >= ? AND called_at < ?
                      GROUP BY target_id) c ON c.id = a.call_log_id
                JOIN (SELECT station_id, MIN(mobile_no) AS mobile_no, MIN(station_name) AS station_name
                      FROM station_master GROUP BY station_id) s ON s.station_id = a.station_id
                WHERE a.route_id IN (SELECT route_id FROM route_master)
                ORDER BY s.mobile_no, a.route_name, a.arrival_order
                """, w.from(), w.to());
    }

    private record Window(Timestamp from, Timestamp to) {
    }

    private static Window window(LocalDateTime at) {
        if (at == null) {
            return new Window(Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0)),
                    Timestamp.valueOf(LocalDateTime.of(2100, 1, 1, 0, 0)));
        }
        LocalDateTime end = at.withSecond(0).withNano(0).plusMinutes(1);
        return new Window(Timestamp.valueOf(end.minusMinutes(3)), Timestamp.valueOf(end));
    }

    /** 노선별 정류장 수, 회차 지점, 대상 정류장 순번 — 위치 막대 그리기용 */
    @GetMapping("/routes")
    public List<Map<String, Object>> routes() {
        List<Map<String, Object>> routes = jdbc.queryForList("""
                SELECT route_id, MIN(route_name) AS route_name, MIN(start_station) AS start_station,
                       MIN(end_station) AS end_station
                FROM route_master GROUP BY route_id ORDER BY route_name
                """);
        List<Map<String, Object>> stops = jdbc.queryForList("""
                SELECT rs.route_id, rs.station_seq, rs.station_name, rs.mobile_no, rs.turn_yn,
                       rs.station_id IN (SELECT station_id FROM station_master) AS target
                FROM route_station rs
                WHERE rs.route_id IN (SELECT route_id FROM route_master)
                ORDER BY rs.route_id, rs.station_seq
                """);
        for (Map<String, Object> r : routes) {
            List<Map<String, Object>> mine = stops.stream()
                    .filter(s -> r.get("route_id").equals(s.get("route_id"))).toList();
            int max = mine.stream().mapToInt(s -> ((Number) s.get("station_seq")).intValue()).max().orElse(0);
            Map<String, Object> turn = mine.stream().filter(s -> "Y".equals(s.get("turn_yn"))).findFirst().orElse(null);
            r.put("max_seq", max);
            r.put("turn_seq", turn == null ? max : turn.get("station_seq"));
            r.put("turn_station", turn == null ? r.get("end_station") : turn.get("station_name"));
            r.put("targets", mine.stream().filter(s -> truthy(s.get("target"))).map(s -> Map.of(
                    "seq", s.get("station_seq"),
                    "name", String.valueOf(s.get("station_name")),
                    "mobile_no", String.valueOf(s.get("mobile_no")))).toList());
        }
        return routes;
    }

    private static boolean truthy(Object v) {
        return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0;
    }

    private static Map<String, Integer> expected(Job j) {
        return SchedulePolicy.expectedCalls(j);
    }
}
