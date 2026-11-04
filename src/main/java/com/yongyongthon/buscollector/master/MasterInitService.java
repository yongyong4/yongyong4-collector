package com.yongyongthon.buscollector.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.yongyongthon.buscollector.client.ApiResponse;
import com.yongyongthon.buscollector.client.GbisClient;
import com.yongyongthon.buscollector.collect.ApiKeys;
import com.yongyongthon.buscollector.config.ApiType;
import com.yongyongthon.buscollector.config.CollectorProperties;
import com.yongyongthon.buscollector.config.CollectorProperties.Job;
import com.yongyongthon.buscollector.config.CollectorProperties.RouteTarget;
import com.yongyongthon.buscollector.config.CollectorProperties.StationTarget;
import com.yongyongthon.buscollector.store.CallLogRepository;
import com.yongyongthon.buscollector.store.MasterRepository;
import com.yongyongthon.buscollector.store.MasterRepository.RouteRow;
import com.yongyongthon.buscollector.store.MasterRepository.RouteStationRow;
import com.yongyongthon.buscollector.store.MasterRepository.StationRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.yongyongthon.buscollector.client.Json.dbl;
import static com.yongyongthon.buscollector.client.Json.integer;
import static com.yongyongthon.buscollector.client.Json.str;

/**
 * 설정의 노선번호 → routeId, 화면 정류소번호(mobileNo) → stationId 변환 후 master 테이블에 저장한다.
 * 정류소는 대상 노선의 경유 정류장 목록에서 먼저 찾으므로 진행 방향(상행)이 자동으로 맞는다.
 * 결과는 사람이 확인할 수 있도록 표 형태로 로그에 남긴다.
 */
@Service
public class MasterInitService {

    private static final Logger log = LoggerFactory.getLogger(MasterInitService.class);

    private final CollectorProperties props;
    private final GbisClient client;
    private final ApiKeys keys;
    private final MasterRepository master;
    private final CallLogRepository callLog;

    public MasterInitService(CollectorProperties props, GbisClient client, ApiKeys keys,
                             MasterRepository master, CallLogRepository callLog) {
        this.props = props;
        this.client = client;
        this.keys = keys;
        this.master = master;
        this.callLog = callLog;
    }

    public record InitReport(String job, List<String> lines, List<String> problems) {
    }

    /** master 가 비어 있을 때만 변환. 기동 직후 초기화와 첫 수집 주기가 겹쳐도 한 번만 돈다 */
    public synchronized boolean initIfEmpty(Job job) {
        boolean empty = (!job.routes().isEmpty() && master.routes(job.name()).isEmpty())
                || (!job.stations().isEmpty() && master.stations(job.name()).isEmpty());
        if (empty) {
            init(job);
        }
        return empty;
    }

    public synchronized InitReport init(Job job) {
        List<String> lines = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String key = keys.keyFor(job);
        if (key == null) {
            problems.add("인증키 환경변수 " + job.apiKeyEnv() + " 가 비어 있음");
            return report(job, lines, problems);
        }

        // 1) 노선
        Map<String, List<RouteStationRow>> stationsByRoute = new LinkedHashMap<>();
        Map<String, String> routeNames = new LinkedHashMap<>();
        for (RouteTarget rt : job.routes()) {
            RouteRow route = resolveRoute(job, rt, key, problems);
            if (route == null) {
                continue;
            }
            master.upsertRoute(route);
            routeNames.put(route.routeId(), route.routeName());
            List<RouteStationRow> rs = loadRouteStations(job, route.routeId(), key, problems);
            if (!rs.isEmpty()) {
                master.replaceRouteStations(route.routeId(), rs);
            } else {
                rs = master.routeStations(route.routeId());
            }
            stationsByRoute.put(route.routeId(), rs);
            lines.add(String.format("노선 %-10s routeId=%-10s %s | %s → %s | 정류장 %d개",
                    route.routeName(), route.routeId(), route.region(), route.startStation(), route.endStation(),
                    rs.size()));
        }

        // 2) 정류소
        List<String> stationIds = new ArrayList<>();
        for (StationTarget st : job.stations()) {
            StationRow row = resolveStation(job, st, key, stationsByRoute, routeNames, problems);
            if (row == null) {
                continue;
            }
            master.upsertStation(row);
            stationIds.add(row.stationId());
            lines.add(String.format("정류소 %-6s stationId=%-10s %s | %s", row.mobileNo(), row.stationId(),
                    row.stationName(), row.directionNote()));
        }
        // 전부 해석된 경우에만 설정에서 빠진 예전 행을 지운다 (조회 실패로 기존 대상이 사라지지 않게)
        if (problems.isEmpty()) {
            master.deleteStale(job.name(), List.copyOf(routeNames.keySet()), stationIds);
        }
        return report(job, lines, problems);
    }

    private InitReport report(Job job, List<String> lines, List<String> problems) {
        StringBuilder sb = new StringBuilder("\n===== master 초기화 [" + job.name() + "] =====\n");
        lines.forEach(l -> sb.append("  ").append(l).append('\n'));
        if (problems.isEmpty()) {
            sb.append("  문제 없음. 이름·방향(다음 정류장)을 눈으로 확인하세요.\n");
        } else {
            sb.append("  ⚠ 확인 필요:\n");
            problems.forEach(p -> sb.append("    - ").append(p).append('\n'));
        }
        sb.append("==========================================");
        if (problems.isEmpty()) {
            log.info(sb.toString());
        } else {
            log.warn(sb.toString());
        }
        return new InitReport(job.name(), lines, problems);
    }

    private RouteRow resolveRoute(Job job, RouteTarget rt, String key, List<String> problems) {
        ApiResponse r = call(job, ApiType.ROUTE_LIST, rt.name(),
                props.api().paths().routeList(), Map.of("keyword", baseNumber(rt.name())), key);
        if (!r.ok()) {
            if (rt.routeId() != null) {
                problems.add("노선 " + rt.name() + ": 노선조회 실패, 설정된 routeId " + rt.routeId() + " 그대로 사용 (" + r.error() + ")");
                return new RouteRow(job.name(), rt.routeId(), rt.name(), null, null, null, null, null,
                        "설정값(조회 실패)", null);
            }
            problems.add("노선 " + rt.name() + ": 노선조회 실패 " + r.error());
            return null;
        }
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode n : r.items()) {
            if (rt.routeId() != null) {
                if (rt.routeId().equals(str(n, "routeId"))) {
                    candidates.add(n);
                }
                continue;
            }
            if (!rt.name().equals(str(n, "routeName"))) {
                continue;
            }
            if (rt.region() != null && !contains(str(n, "regionName"), rt.region())) {
                continue;
            }
            if (rt.startStation() != null && !contains(str(n, "startStationName"), rt.startStation())) {
                continue;
            }
            candidates.add(n);
        }
        if (candidates.size() != 1) {
            StringBuilder sb = new StringBuilder("노선 " + rt.name() + ": 조건에 맞는 노선 " + candidates.size()
                    + "개. application.yml 에 route-id 를 직접 지정하세요. 검색 결과:");
            for (JsonNode n : r.items()) {
                sb.append("\n        ").append(str(n, "routeName")).append(" routeId=").append(str(n, "routeId"))
                        .append(" 지역=").append(str(n, "regionName")).append(" 기점=").append(str(n, "startStationName"))
                        .append(" 종점=").append(str(n, "endStationName"));
            }
            problems.add(sb.toString());
            return null;
        }
        JsonNode n = candidates.get(0);
        return new RouteRow(job.name(), str(n, "routeId"), str(n, "routeName"), str(n, "regionName"),
                str(n, "startStationName"), str(n, "endStationName"), integer(n, "routeTypeCd"),
                firstNonNull(str(n, "adminName"), str(n, "companyName")), null, n.toString());
    }

    private List<RouteStationRow> loadRouteStations(Job job, String routeId, String key, List<String> problems) {
        ApiResponse r = call(job, ApiType.ROUTE_STATIONS, routeId,
                props.api().paths().routeStations(), Map.of("routeId", routeId), key);
        if (!r.ok()) {
            problems.add("노선 " + routeId + " 경유정류장 조회 실패: " + r.error());
            return List.of();
        }
        List<RouteStationRow> rows = new ArrayList<>();
        for (JsonNode n : r.items()) {
            Integer seq = integer(n, "stationSeq");
            String sid = str(n, "stationId");
            if (seq == null || sid == null) {
                continue;
            }
            rows.add(new RouteStationRow(routeId, seq, sid, str(n, "stationName"), normMobile(str(n, "mobileNo")),
                    str(n, "regionName"), str(n, "turnYn"), dbl(n, "x"), dbl(n, "y")));
        }
        return rows;
    }

    private StationRow resolveStation(Job job, StationTarget st, String key,
                                      Map<String, List<RouteStationRow>> stationsByRoute,
                                      Map<String, String> routeNames, List<String> problems) {
        String mobile = normMobile(st.mobileNo());
        List<String> notes = new ArrayList<>();
        String stationId = st.stationId();
        RouteStationRow any = null;

        for (var e : stationsByRoute.entrySet()) {
            List<RouteStationRow> list = e.getValue();
            int turnSeq = list.stream().filter(s -> "Y".equalsIgnoreCase(s.turnYn()))
                    .mapToInt(RouteStationRow::stationSeq).min().orElse(Integer.MAX_VALUE);
            for (int i = 0; i < list.size(); i++) {
                RouteStationRow s = list.get(i);
                boolean match = stationId != null ? stationId.equals(s.stationId()) : Objects.equals(mobile, s.mobileNo());
                if (!match) {
                    continue;
                }
                if (stationId == null) {
                    stationId = s.stationId();
                } else if (!stationId.equals(s.stationId())) {
                    problems.add("정류소 " + st.mobileNo() + ": 노선마다 stationId 가 다름 (" + stationId + " / " + s.stationId() + ")");
                }
                any = s;
                String next = i + 1 < list.size() ? list.get(i + 1).stationName() : "(종점)";
                String dir = s.stationSeq() > turnSeq ? " [회차 후 구간!]" : "";
                notes.add(routeNames.get(e.getKey()) + " #" + s.stationSeq() + " 다음:" + next + dir);
                if (!dir.isEmpty()) {
                    problems.add("정류소 " + st.mobileNo() + " 가 " + routeNames.get(e.getKey()) + " 의 회차 후(하행) 구간에 있음. 방향 확인 필요");
                }
            }
        }

        if (any != null) {
            return new StationRow(job.name(), stationId, firstNonNull(any.mobileNo(), mobile),
                    firstNonNull(any.stationName(), st.name()), any.region(), any.x(), any.y(),
                    String.join(", ", notes));
        }

        // 노선 경유 목록에 없으면 정류소 검색으로 대체 (방향 보장 안 됨)
        String keyword = mobile != null ? mobile : st.name();
        ApiResponse r = call(job, ApiType.STATION_SEARCH, keyword,
                props.api().paths().stationSearch(), Map.of("keyword", keyword), key);
        if (r.ok()) {
            for (JsonNode n : r.items()) {
                boolean match = st.stationId() != null ? st.stationId().equals(str(n, "stationId"))
                        : Objects.equals(mobile, normMobile(str(n, "mobileNo")));
                if (match) {
                    problems.add("정류소 " + st.mobileNo() + ": 대상 노선 경유 목록에 없어 정류소 검색 결과 사용. 방향·노선 확인 필요");
                    return new StationRow(job.name(), str(n, "stationId"), normMobile(str(n, "mobileNo")),
                            str(n, "stationName"), str(n, "regionName"), dbl(n, "x"), dbl(n, "y"),
                            "정류소 검색 결과 (경유 노선 없음)");
                }
            }
        }
        if (st.stationId() != null) {
            problems.add("정류소 " + st.mobileNo() + ": 조회 실패, 설정된 stationId " + st.stationId() + " 그대로 사용");
            return new StationRow(job.name(), st.stationId(), mobile, st.name(), null, null, null, "설정값(조회 실패)");
        }
        problems.add("정류소 " + st.mobileNo() + " (" + st.name() + "): stationId 를 찾지 못함"
                + (r.ok() ? "" : " - " + r.error()));
        return null;
    }

    private ApiResponse call(Job job, ApiType type, String target, String path, Map<String, String> params, String key) {
        ApiResponse r = client.get(path, params, key);
        long id = callLog.insert(job.name(), type, target, r);
        callLog.finish(id, r.items().size(), null);
        return r;
    }

    /** "5005(거점)" → "5005" 처럼 검색어는 번호 부분만 쓴다 */
    static String baseNumber(String routeName) {
        int p = routeName.indexOf('(');
        return p > 0 ? routeName.substring(0, p).trim() : routeName.trim();
    }

    /** API 의 mobileNo 는 앞 공백이 붙어 오기도 한다 */
    static String normMobile(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean contains(String s, String part) {
        return s != null && s.contains(part);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
