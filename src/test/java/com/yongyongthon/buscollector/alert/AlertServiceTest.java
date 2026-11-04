package com.yongyongthon.buscollector.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yongyongthon.buscollector.client.ApiResponse;
import com.yongyongthon.buscollector.collect.CollectService.CycleResult;
import com.yongyongthon.buscollector.config.ApiType;
import com.yongyongthon.buscollector.config.CollectorProperties;
import com.yongyongthon.buscollector.store.CallLogRepository;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제로 보내지 않고 어떤 메시지가 언제 나가는지만 확인 */
class AlertServiceTest {

    private final List<String> sent = new ArrayList<>();

    private final TelegramNotifier fake = new TelegramNotifier("t", "c", "", new ObjectMapper()) {
        @Override
        public void send(String text) {
            sent.add(text);
        }

        @Override
        public void ping() {
        }
    };

    private final CallLogRepository callLog = new CallLogRepository(null) {
        @Override
        public List<Map<String, Object>> dailySummary(LocalDate day) {
            return List.of(
                    Map.of("job_name", "m", "api_type", "LOCATION", "calls", 605, "failures", 0, "saved_rows", 7000L),
                    Map.of("job_name", "m", "api_type", "ARRIVAL", "calls", 600, "failures", 5, "saved_rows", 11000L));
        }
    };

    private final CollectorProperties.Job job = new CollectorProperties.Job("m", true, "KEY", 1000,
            Set.of(DayOfWeek.values()), "06:00", "10:00", 2, Set.of(ApiType.LOCATION, ApiType.ARRIVAL),
            List.of(new CollectorProperties.RouteTarget("5000", null, null, null)),
            List.of(new CollectorProperties.StationTarget("29307", null, "강남대역")));

    private final AlertService alerts = new AlertService(fake, callLog,
            new CollectorProperties("Asia/Seoul", false, "", "", null, List.of(job)));

    private static ApiResponse ok() {
        return new ApiResponse(LocalDateTime.now(), 200, 0, null, null, true, 1, 10, "{}", null, List.of(), null);
    }

    private static ApiResponse fail(String error) {
        return new ApiResponse(LocalDateTime.now(), 500, null, null, null, false, 2, 10, null, "x", List.of(), error);
    }

    @Test
    void 연속_3회_실패에_한번_알리고_회복도_한번() {
        alerts.onCall(job, ApiType.LOCATION, "r1", "5000번", fail("HTTP 500"));
        alerts.onCall(job, ApiType.LOCATION, "r1", "5000번", fail("HTTP 500"));
        assertThat(sent).isEmpty();
        alerts.onCall(job, ApiType.LOCATION, "r1", "5000번", fail("HTTP 500"));
        alerts.onCall(job, ApiType.LOCATION, "r1", "5000번", fail("HTTP 500"));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("⚠️", "5000번 위치", "3회 연속 실패", "HTTP 500");
        alerts.onCall(job, ApiType.LOCATION, "r1", "5000번", ok());
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).contains("✅", "회복", "실패 4회");
        // 회복 뒤 다시 성공해도 조용
        alerts.onCall(job, ApiType.LOCATION, "r1", "5000번", ok());
        assertThat(sent).hasSize(2);
    }

    @Test
    void 짧은_실패는_조용히_넘어간다() {
        alerts.onCall(job, ApiType.ARRIVAL, "s1", "강남대역", fail("timeout"));
        alerts.onCall(job, ApiType.ARRIVAL, "s1", "강남대역", ok());
        assertThat(sent).isEmpty();
    }

    @Test
    void 게이트웨이_오류는_즉시_한번만() {
        ApiResponse gw = fail("게이트웨이 오류 30: SERVICE_KEY_IS_NOT_REGISTERED_ERROR");
        alerts.onCall(job, ApiType.LOCATION, "r1", "5000번", gw);
        alerts.onCall(job, ApiType.ARRIVAL, "s1", "강남대역", gw);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("🚨", "공공데이터포털");
    }

    @Test
    void 하루_첫_주기에_시작_마지막_주기에_요약() {
        LocalDate d = LocalDate.of(2026, 10, 28);
        CycleResult r = new CycleResult("m", 10, 0, 50, 90, 0, 1000);
        alerts.onScheduledCycle(job, d.atTime(6, 0), r);
        alerts.onScheduledCycle(job, d.atTime(6, 2), r);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("▶️", "수집 시작 06:00").doesNotContain("재시작");

        alerts.onScheduledCycle(job, d.atTime(10, 0), r);
        assertThat(sent).hasSize(2);
        // 도착 600/605, 실패 5 → 경고 표시
        assertThat(sent.get(1)).contains("수집 종료", "10/28(수)", "위치 605/121회", "도착 600/121회", "⚠️");
    }

    @Test
    void 재시작으로_중간에_시작하면_표시() {
        alerts.onScheduledCycle(job, LocalDate.of(2026, 10, 28).atTime(7, 30), new CycleResult("m", 10, 0, 1, 1, 0, 1));
        assertThat(sent.get(0)).contains("재시작 후 첫 수집");
    }
}
