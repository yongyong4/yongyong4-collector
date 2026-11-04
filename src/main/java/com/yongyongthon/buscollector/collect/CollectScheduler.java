package com.yongyongthon.buscollector.collect;

import com.yongyongthon.buscollector.alert.AlertService;
import com.yongyongthon.buscollector.config.CollectorProperties;
import com.yongyongthon.buscollector.config.CollectorProperties.Job;
import com.yongyongthon.buscollector.config.SchedulePolicy;
import com.yongyongthon.buscollector.master.MasterInitService;
import com.yongyongthon.buscollector.store.CallLogRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 매 분 0초에 깨어나 각 job 의 요일·시간대·간격을 확인하고 해당되면 별도 스레드에서 한 주기를 돌린다.
 * job 마다 동시에 1주기만 실행되며, 어떤 예외도 스케줄러 밖으로 새지 않는다.
 */
@Component
public class CollectScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectScheduler.class);

    private final CollectorProperties props;
    private final CollectService collector;
    private final MasterInitService masterInit;
    private final CallLogRepository callLog;
    private final AlertService alerts;
    private final ZoneId zone;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    /** job → 실행 중인 주기의 시작 시각(ms). 0 이면 쉬는 중 */
    private final Map<String, AtomicLong> running = new ConcurrentHashMap<>();

    public CollectScheduler(CollectorProperties props, CollectService collector, MasterInitService masterInit,
                            CallLogRepository callLog, AlertService alerts) {
        this.props = props;
        this.collector = collector;
        this.masterInit = masterInit;
        this.callLog = callLog;
        this.alerts = alerts;
        this.zone = ZoneId.of(props.zone());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        alerts.onStartup();
        for (Job job : props.jobs()) {
            log.info("job [{}] enabled={} {} {}~{} 매 {}분 {} | 노선 {}개, 정류소 {}개", job.name(), job.enabled(),
                    job.days(), job.start(), job.end(), job.intervalMinutes(), job.collect(),
                    job.routes().size(), job.stations().size());
        }
        if (!props.initOnStartup()) {
            return;
        }
        pool.submit(() -> {
            for (Job job : props.jobs()) {
                if (!job.enabled()) {
                    continue;
                }
                try {
                    masterInit.initIfEmpty(job);
                } catch (Exception e) {
                    log.error("[{}] 기동 시 master 초기화 실패", job.name(), e);
                }
            }
        });
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void tick() {
        LocalDateTime now = LocalDateTime.now(zone);
        for (Job job : props.jobs()) {
            if (job.enabled() && SchedulePolicy.isDue(job, now)) {
                trigger(job, now);
            }
        }
    }

    /**
     * 수동 실행에도 쓰인다. 이미 돌고 있으면 false.
     * 단, 한 주기가 간격×3(최소 5분)을 넘게 끝나지 않으면 멈춘 것으로 보고 새 주기를 시작한다 — 수집이 영원히 막히지 않게.
     */
    public boolean trigger(Job job) {
        return trigger(job, null);
    }

    /** scheduledAt 이 있으면 정해진 시각의 주기 → 시작·종료 알림 대상. 수동 실행은 null */
    private boolean trigger(Job job, LocalDateTime scheduledAt) {
        AtomicLong startedAt = running.computeIfAbsent(job.name(), k -> new AtomicLong());
        long now = System.currentTimeMillis();
        long prev = startedAt.get();
        if (prev != 0) {
            long stuckMs = Math.max(5 * 60_000L, job.intervalMinutes() * 3 * 60_000L);
            if (now - prev < stuckMs) {
                log.warn("[{}] 이전 주기가 아직 실행 중이라 이번 주기 건너뜀", job.name());
                return false;
            }
            log.error("[{}] 이전 주기가 {}초째 끝나지 않음 → 멈춘 것으로 보고 새 주기 시작", job.name(), (now - prev) / 1000);
            alerts.onStuck(job, (now - prev) / 1000);
        }
        if (!startedAt.compareAndSet(prev, now)) {
            return false;
        }
        pool.submit(() -> {
            try {
                CollectService.CycleResult result = collector.runCycle(job);
                if (scheduledAt != null) {
                    alerts.onScheduledCycle(job, scheduledAt, result);
                }
            } catch (Throwable t) {
                log.error("[{}] 수집 주기 중 예외", job.name(), t);
            } finally {
                // 멈췄다가 늦게 끝난 옛 주기가 새 주기의 표시를 지우지 않도록 자기 시작 시각일 때만 해제
                startedAt.compareAndSet(now, 0);
            }
        });
        return true;
    }

    public Optional<Job> job(String name) {
        return props.jobs().stream().filter(j -> j.name().equals(name)).findFirst();
    }

    @Scheduled(cron = "${collector.summary-cron}", zone = "Asia/Seoul")
    public void dailySummary() {
        try {
            LocalDate today = LocalDate.now(zone);
            StringBuilder sb = new StringBuilder("\n===== 일일 요약 " + today + " =====\n");
            sb.append(String.format("  %-18s %-15s %6s %8s %6s %8s%n", "job", "api", "calls", "attempts", "fail", "rows"));
            for (Map<String, Object> r : callLog.dailySummary(today)) {
                sb.append(String.format("  %-18s %-15s %6s %8s %6s %8s%n", r.get("job_name"), r.get("api_type"),
                        r.get("calls"), r.get("attempts"), r.get("failures"), r.get("saved_rows")));
            }
            sb.append("==============================");
            log.info(sb.toString());
        } catch (Exception e) {
            log.error("일일 요약 실패", e);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
