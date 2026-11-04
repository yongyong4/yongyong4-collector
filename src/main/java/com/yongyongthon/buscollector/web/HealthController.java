package com.yongyongthon.buscollector.web;

import com.yongyongthon.buscollector.config.CollectorProperties;
import com.yongyongthon.buscollector.store.CallLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final CallLogRepository callLog;
    private final ZoneId zone;

    public HealthController(CallLogRepository callLog, CollectorProperties props) {
        this.callLog = callLog;
        this.zone = ZoneId.of(props.zone());
    }

    /** 마지막 성공 수집 시각, 오늘 API 별 호출/실패/저장 행 수, 최근 오류 */
    @GetMapping("/health/collect")
    public Map<String, Object> collect() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("now", LocalDateTime.now(zone).toString());
        body.put("lastSuccess", callLog.lastSuccess());
        body.put("today", callLog.dailySummary(LocalDate.now(zone)));
        body.put("recentErrors", callLog.recentErrors(5));
        return body;
    }
}
