package com.yongyongthon.buscollector.alert;

import com.yongyongthon.buscollector.client.ApiResponse;
import com.yongyongthon.buscollector.collect.CollectService.CycleResult;
import com.yongyongthon.buscollector.config.ApiType;
import com.yongyongthon.buscollector.config.CollectorProperties;
import com.yongyongthon.buscollector.config.CollectorProperties.Job;
import com.yongyongthon.buscollector.config.SchedulePolicy;
import com.yongyongthon.buscollector.store.CallLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 수집 시작·종료·오류를 텔레그램으로 알린다. 알림 폭탄을 막기 위해
 * 같은 대상이 연속 3회 실패할 때 1번, 회복 시 1번, 같은 경고는 30분에 1번만 보낸다.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final String TAG = "[버스수집] ";
    private static final int FAIL_THRESHOLD = 3;
    private static final Duration COOLDOWN = Duration.ofMinutes(30);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final TelegramNotifier telegram;
    private final CallLogRepository callLog;
    private final CollectorProperties props;
    private final ZoneId zone;

    private final Map<String, FailState> failures = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastSent = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> startedOn = new ConcurrentHashMap<>();

    public AlertService(TelegramNotifier telegram, CallLogRepository callLog, CollectorProperties props) {
        this.telegram = telegram;
        this.callLog = callLog;
        this.props = props;
        this.zone = ZoneId.of(props.zone());
    }

    private static final class FailState {
        int count;
        LocalDateTime firstAt;
        String lastError;
        boolean alerted;
    }

    /** 앱 기동 */
    public void onStartup() {
        List<String> lines = new ArrayList<>();
        LocalDateTime now = now();
        LocalDateTime next = null;
        for (Job j : props.jobs()) {
            if (!j.enabled()) {
                continue;
            }
            lines.add("· " + j.name() + " " + j.start() + "~" + j.end() + " " + j.intervalMinutes() + "분 간격 " + j.collect());
            LocalDateTime n = SchedulePolicy.nextRun(j, now);
            if (n != null && (next == null || n.isBefore(next))) {
                next = n;
            }
        }
        log.info("텔레그램 알림 {}", telegram.enabled() ? "켜짐" : "꺼짐 (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID 미설정)");
        telegram.send(TAG + "🔄 수집기 시작됨\n" + String.join("\n", lines)
                + "\n다음 수집: " + (next == null ? "없음" : when(next, now)));
    }

    /** 호출 1건 결과. 연속 실패·회복·즉시 대응 오류를 판단한다 */
    public void onCall(Job job, ApiType type, String target, String targetName, ApiResponse r) {
        String key = job.name() + "|" + type + "|" + target;
        String label = targetName + " " + label(type);
        if (r.ok()) {
            FailState f = failures.remove(key);
            if (f != null && f.alerted) {
                long min = Duration.between(f.firstAt, now()).toMinutes();
                telegram.send(TAG + "✅ " + label + " 회복 (" + now().format(HM) + ") · 중단 약 " + min + "분, 실패 " + f.count + "회");
            }
            return;
        }
        if (r.error() != null && r.error().startsWith("게이트웨이 오류")) {
            // 인증키 미등록(30)·한도 초과(22) 등은 재시도로 풀리지 않으니 바로 알린다
            once("gw|" + job.name() + "|" + r.resultCode(), "🚨 공공데이터포털 오류 — 수집이 막힘\n" + label + ": " + r.error()
                    + "\n(30 인증키 미등록 / 22 호출 한도 초과 / 20 서비스 접근 거부)");
        }
        FailState f = failures.computeIfAbsent(key, k -> new FailState());
        if (f.count == 0) {
            f.firstAt = now();
        }
        f.count++;
        f.lastError = r.error();
        if (f.count >= FAIL_THRESHOLD && !f.alerted) {
            f.alerted = true;
            telegram.send(TAG + "⚠️ " + label + " " + f.count + "회 연속 실패 (" + f.firstAt.format(HM) + "~" + now().format(HM) + ")\n"
                    + abbreviate(f.lastError));
        }
    }

    /** 정해진 시각에 돈 주기 1회가 끝났을 때. 그날 첫 주기면 시작, 마지막 주기면 하루 요약 */
    public void onScheduledCycle(Job job, LocalDateTime cycleAt, CycleResult result) {
        telegram.ping();
        LocalDate today = cycleAt.toLocalDate();
        if (!today.equals(startedOn.get(job.name()))) {
            startedOn.put(job.name(), today);
            boolean late = cycleAt.toLocalTime().isAfter(job.startTime().plusMinutes(job.intervalMinutes()));
            telegram.send(TAG + "▶️ " + job.name() + " 수집 시작 " + cycleAt.format(HM) + (late ? " (재시작 후 첫 수집)" : "")
                    + "\n호출 " + result.calls() + "회 · 실패 " + result.failures() + "회 · 위치 " + result.locationRows()
                    + "행 · 도착 " + result.arrivalRows() + "행");
        }
        LocalTime t = cycleAt.toLocalTime().withSecond(0).withNano(0);
        if (!t.isBefore(job.endTime())) {
            sendDaySummary(job, today);
        }
    }

    private void sendDaySummary(Job job, LocalDate day) {
        Map<String, Integer> expected = SchedulePolicy.expectedCalls(job);
        StringBuilder sb = new StringBuilder(TAG + "✅ " + job.name() + " " + day.format(DateTimeFormatter.ofPattern("MM/dd"))
                + "(" + day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN) + ") 수집 종료");
        boolean shortfall = false;
        for (Map<String, Object> r : callLog.dailySummary(day)) {
            if (!job.name().equals(r.get("job_name")) || !expected.containsKey(String.valueOf(r.get("api_type")))) {
                continue;
            }
            int calls = ((Number) r.get("calls")).intValue();
            int exp = expected.get(String.valueOf(r.get("api_type")));
            int fail = ((Number) r.get("failures")).intValue();
            shortfall |= calls < exp || fail > 0;
            sb.append("\n· ").append(label(ApiType.valueOf(String.valueOf(r.get("api_type")))))
                    .append(" ").append(calls).append("/").append(exp).append("회 · 실패 ").append(fail)
                    .append(" · ").append(String.format("%,d", ((Number) r.get("saved_rows")).longValue())).append("행");
        }
        if (shortfall) {
            sb.insert(TAG.length(), "⚠️ ");
            sb.append("\n예상보다 적거나 실패가 있었습니다. 대시보드의 최근 오류를 확인하세요.");
        }
        telegram.send(sb.toString());
    }

    public void onQuotaHigh(Job job, ApiType type, int used, int limit) {
        once("quota|" + job.apiKeyEnv() + "|" + type + "|" + now().toLocalDate(),
                "⚠️ " + label(type) + " API 오늘 " + used + "/" + limit + "회 사용 (" + job.apiKeyEnv() + ")", Duration.ofDays(1));
    }

    public void onLimitReached(Job job, ApiType type, int limit) {
        once("limit|" + job.apiKeyEnv() + "|" + type + "|" + now().toLocalDate(),
                "🚨 " + label(type) + " API 일일 한도 " + limit + "회 도달 — 오늘 남은 수집 중단 (" + job.apiKeyEnv() + ")", Duration.ofDays(1));
    }

    public void onStuck(Job job, long seconds) {
        once("stuck|" + job.name(), "🚨 " + job.name() + " 수집 주기가 " + seconds + "초째 끝나지 않아 새 주기를 시작합니다. 서버 로그 확인 필요");
    }

    public void onMissingKey(Job job) {
        once("nokey|" + job.name(), "🚨 " + job.name() + " 인증키 환경변수 " + job.apiKeyEnv() + " 가 비어 있어 수집 불가");
    }

    public void test() {
        telegram.send(TAG + "🔔 테스트 알림입니다. 이 메시지가 보이면 연동 완료.");
    }

    public boolean enabled() {
        return telegram.enabled();
    }

    private void once(String key, String text) {
        once(key, text, COOLDOWN);
    }

    private void once(String key, String text, Duration cooldown) {
        LocalDateTime now = now();
        LocalDateTime prev = lastSent.get(key);
        if (prev != null && Duration.between(prev, now).compareTo(cooldown) < 0) {
            return;
        }
        lastSent.put(key, now);
        telegram.send(TAG + text);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    private static String label(ApiType type) {
        return switch (type) {
            case LOCATION -> "위치";
            case ARRIVAL -> "도착";
            default -> type.name();
        };
    }

    private static String when(LocalDateTime t, LocalDateTime now) {
        long days = Duration.between(now.toLocalDate().atStartOfDay(), t.toLocalDate().atStartOfDay()).toDays();
        String day = days == 0 ? "오늘" : days == 1 ? "내일" : t.format(DateTimeFormatter.ofPattern("MM/dd"));
        return day + "(" + t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN) + ") " + t.format(HM);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
