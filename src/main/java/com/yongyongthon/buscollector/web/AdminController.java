package com.yongyongthon.buscollector.web;

import com.yongyongthon.buscollector.alert.AlertService;
import com.yongyongthon.buscollector.collect.CollectScheduler;
import com.yongyongthon.buscollector.config.CollectorProperties;
import com.yongyongthon.buscollector.config.CollectorProperties.Job;
import com.yongyongthon.buscollector.master.MasterInitService;
import com.yongyongthon.buscollector.master.MasterInitService.InitReport;
import com.yongyongthon.buscollector.store.MasterRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final CollectorProperties props;
    private final MasterInitService masterInit;
    private final CollectScheduler scheduler;
    private final MasterRepository master;
    private final AlertService alerts;

    public AdminController(CollectorProperties props, MasterInitService masterInit, CollectScheduler scheduler,
                           MasterRepository master, AlertService alerts) {
        this.props = props;
        this.masterInit = masterInit;
        this.scheduler = scheduler;
        this.master = master;
        this.alerts = alerts;
    }

    /** 노선번호 → routeId, 정류소번호 → stationId 변환 후 master 저장. job 생략 시 활성 job 전부 */
    @PostMapping("/init")
    public List<InitReport> init(@RequestParam(required = false) String job) {
        List<InitReport> reports = new ArrayList<>();
        for (Job j : targets(job)) {
            reports.add(masterInit.init(j));
        }
        return reports;
    }

    /** 시간대와 무관하게 1주기 즉시 수집 (비동기). 진행 결과는 로그와 /health/collect 로 확인 */
    @PostMapping("/collect")
    public ResponseEntity<Map<String, Object>> collect(@RequestParam(required = false) String job) {
        List<String> started = new ArrayList<>();
        for (Job j : targets(job)) {
            if (scheduler.trigger(j)) {
                started.add(j.name());
            }
        }
        return ResponseEntity.accepted().body(Map.of("started", started));
    }

    /** 텔레그램 테스트 메시지 */
    @PostMapping("/alert-test")
    public Map<String, Object> alertTest() {
        if (!alerts.enabled()) {
            return Map.of("sent", false, "reason", "TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID 미설정");
        }
        alerts.test();
        return Map.of("sent", true);
    }

    @GetMapping("/master")
    public List<Map<String, Object>> master() {
        return master.overview();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private List<Job> targets(String job) {
        if (job != null) {
            return List.of(scheduler.job(job).orElseThrow(() -> new IllegalArgumentException("unknown job: " + job)));
        }
        return props.jobs().stream().filter(Job::enabled).toList();
    }
}
