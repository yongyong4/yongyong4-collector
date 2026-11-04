package com.yongyongthon.buscollector.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.yongyongthon.buscollector.alert.AlertService;
import com.yongyongthon.buscollector.client.ApiResponse;
import com.yongyongthon.buscollector.client.GbisClient;
import com.yongyongthon.buscollector.config.ApiType;
import com.yongyongthon.buscollector.config.CollectorProperties;
import com.yongyongthon.buscollector.config.CollectorProperties.Job;
import com.yongyongthon.buscollector.master.MasterInitService;
import com.yongyongthon.buscollector.store.CallLogRepository;
import com.yongyongthon.buscollector.store.MasterRepository;
import com.yongyongthon.buscollector.store.MasterRepository.Target;
import com.yongyongthon.buscollector.store.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.yongyongthon.buscollector.client.Json.integer;
import static com.yongyongthon.buscollector.client.Json.str;

/**
 * 한 주기 수집: 노선별 위치 → 정류장별 도착 순서로 호출하고 원본 + 파싱 행을 저장한다.
 * 개별 호출 실패는 기록만 하고 다음 대상으로 넘어간다. 주기 시간을 넘길 것 같으면 남은 호출을 건너뛴다.
 */
@Service
public class CollectService {

    private static final Logger log = LoggerFactory.getLogger(CollectService.class);

    private final CollectorProperties props;
    private final GbisClient client;
    private final ApiKeys keys;
    private final MasterRepository master;
    private final MasterInitService masterInit;
    private final CallLogRepository callLog;
    private final SnapshotRepository snapshots;
    private final AlertService alerts;
    private final ZoneId zone;

    public CollectService(CollectorProperties props, GbisClient client, ApiKeys keys, MasterRepository master,
                          MasterInitService masterInit, CallLogRepository callLog, SnapshotRepository snapshots,
                          AlertService alerts) {
        this.props = props;
        this.client = client;
        this.keys = keys;
        this.master = master;
        this.masterInit = masterInit;
        this.callLog = callLog;
        this.snapshots = snapshots;
        this.alerts = alerts;
        this.zone = ZoneId.of(props.zone());
    }

    public record CycleResult(String job, int calls, int failures, int locationRows, int arrivalRows,
                              int skipped, long elapsedMs) {
    }

    public CycleResult runCycle(Job job) {
        long t0 = System.currentTimeMillis();
        // 다음 주기와 겹치지 않도록 주기 길이 - 15초를 예산으로 둔다
        long budgetMs = Math.max(30_000L, job.intervalMinutes() * 60_000L - 15_000L);
        Counter c = new Counter();

        String key = keys.keyFor(job);
        if (key == null) {
            log.error("[{}] 인증키 환경변수 {} 가 비어 있어 수집 불가", job.name(), job.apiKeyEnv());
            callLog.insertSkipped(job.name(), ApiType.LOCATION, null, now(), "인증키 없음: " + job.apiKeyEnv());
            alerts.onMissingKey(job);
            return c.result(job, t0);
        }

        List<Target> routes = master.routes(job.name());
        List<Target> stations = master.stations(job.name());
        boolean needRoutes = job.collect().contains(ApiType.LOCATION) && !job.routes().isEmpty();
        boolean needStations = job.collect().contains(ApiType.ARRIVAL) && !job.stations().isEmpty();
        if ((needRoutes && routes.isEmpty()) || (needStations && stations.isEmpty())) {
            log.warn("[{}] master 비어 있음 → ID 변환 먼저 수행", job.name());
            masterInit.initIfEmpty(job);
            routes = master.routes(job.name());
            stations = master.stations(job.name());
        }

        if (job.collect().contains(ApiType.LOCATION)) {
            for (Target route : routes) {
                if (overBudget(job, t0, budgetMs, ApiType.LOCATION, route.id(), c)) {
                    continue;
                }
                collectLocation(job, key, route, c);
            }
        }
        if (job.collect().contains(ApiType.ARRIVAL)) {
            for (Target station : stations) {
                if (overBudget(job, t0, budgetMs, ApiType.ARRIVAL, station.id(), c)) {
                    continue;
                }
                collectArrival(job, key, station, c);
            }
        }
        CycleResult r = c.result(job, t0);
        log.info("[{}] 주기 완료: 호출 {} 실패 {} 건너뜀 {} | 위치 {}행 도착 {}행 | {}ms",
                job.name(), r.calls(), r.failures(), r.skipped(), r.locationRows(), r.arrivalRows(), r.elapsedMs());
        return r;
    }

    private boolean overBudget(Job job, long t0, long budgetMs, ApiType type, String target, Counter c) {
        if (System.currentTimeMillis() - t0 > budgetMs) {
            callLog.insertSkipped(job.name(), type, target, now(), "주기 시간 초과로 건너뜀");
            c.skipped++;
            return true;
        }
        int used = callLog.attemptsOn(jobsSharingKey(job), type, now().toLocalDate());
        if (used >= job.dailyLimit() * 0.9) {
            alerts.onQuotaHigh(job, type, used, job.dailyLimit());
        }
        if (used >= job.dailyLimit()) {
            log.warn("[{}] {} 일일 한도 {} 도달(같은 인증키 job 합산), 건너뜀", job.name(), type, job.dailyLimit());
            alerts.onLimitReached(job, type, job.dailyLimit());
            callLog.insertSkipped(job.name(), type, target, now(), "일일 한도 도달");
            c.skipped++;
            return true;
        }
        return false;
    }

    private void collectLocation(Job job, String key, Target route, Counter c) {
        ApiResponse r = client.get(props.api().paths().busLocation(), Map.of("routeId", route.id()), key);
        long logId = save(job, ApiType.LOCATION, route.id(), r, c);
        alerts.onCall(job, ApiType.LOCATION, route.id(), route.name() + "번", r);
        if (!r.ok()) {
            return;
        }
        try {
            List<Object[]> rows = new ArrayList<>();
            for (JsonNode n : r.items()) {
                rows.add(new Object[]{logId, ts(r.calledAt()), ts(r.queryTime()),
                        firstNonNull(str(n, "routeId"), route.id()), route.name(),
                        str(n, "plateNo"), str(n, "vehId"), integer(n, "stationSeq"), str(n, "stationId"),
                        integer(n, "stateCd"), integer(n, "remainSeatCnt"), integer(n, "lowPlate"),
                        integer(n, "crowded"), integer(n, "routeTypeCd"), integer(n, "taglessCd")});
            }
            snapshots.insertLocations(rows);
            callLog.finish(logId, rows.size(), null);
            c.locationRows += rows.size();
        } catch (Exception e) {
            log.error("[{}] 위치 저장 실패 route={}", job.name(), route.id(), e);
            callLog.finish(logId, 0, e.toString());
            c.failures++;
        }
    }

    private void collectArrival(Job job, String key, Target station, Counter c) {
        ApiResponse r = client.get(props.api().paths().busArrival(), Map.of("stationId", station.id()), key);
        long logId = save(job, ApiType.ARRIVAL, station.id(), r, c);
        alerts.onCall(job, ApiType.ARRIVAL, station.id(), station.name(), r);
        if (!r.ok()) {
            return;
        }
        try {
            List<Object[]> rows = new ArrayList<>();
            for (JsonNode n : r.items()) {
                for (int order = 1; order <= 2; order++) {
                    String plate = str(n, "plateNo" + order);
                    Integer predict = integer(n, "predictTime" + order);
                    if (plate == null && predict == null) {
                        continue;
                    }
                    rows.add(new Object[]{logId, ts(r.calledAt()), ts(r.queryTime()),
                            firstNonNull(str(n, "stationId"), station.id()), str(n, "routeId"), str(n, "routeName"),
                            integer(n, "routeTypeCd"), order, plate, str(n, "vehId" + order), predict,
                            integer(n, "predictTimeSec" + order), integer(n, "locationNo" + order),
                            integer(n, "remainSeatCnt" + order), integer(n, "lowPlate" + order),
                            integer(n, "crowded" + order), integer(n, "stateCd" + order),
                            str(n, "stationNm" + order), str(n, "flag"), integer(n, "staOrder"),
                            str(n, "routeDestName"), integer(n, "taglessCd" + order)});
                }
            }
            snapshots.insertArrivals(rows);
            callLog.finish(logId, rows.size(), null);
            c.arrivalRows += rows.size();
        } catch (Exception e) {
            log.error("[{}] 도착 저장 실패 station={}", job.name(), station.id(), e);
            callLog.finish(logId, 0, e.toString());
            c.failures++;
        }
    }

    private long save(Job job, ApiType type, String target, ApiResponse r, Counter c) {
        c.calls++;
        if (!r.ok()) {
            c.failures++;
            log.warn("[{}] {} {} 실패 (시도 {}회): {}", job.name(), type, target, r.attempts(), r.error());
        }
        return callLog.insert(job.name(), type, target, r);
    }

    /** 같은 인증키를 쓰는 job 이름들. 공공데이터포털 한도는 키·API 단위라 합산해야 한다 */
    public List<String> jobsSharingKey(Job job) {
        return props.jobs().stream().filter(j -> j.apiKeyEnv().equals(job.apiKeyEnv())).map(Job::name).toList();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static final class Counter {
        int calls;
        int failures;
        int locationRows;
        int arrivalRows;
        int skipped;

        CycleResult result(Job job, long t0) {
            return new CycleResult(job.name(), calls, failures, locationRows, arrivalRows, skipped,
                    System.currentTimeMillis() - t0);
        }
    }
}
