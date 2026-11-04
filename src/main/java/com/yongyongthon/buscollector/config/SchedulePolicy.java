package com.yongyongthon.buscollector.config;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** job 의 요일·시간대·간격으로 해당 분(minute)에 수집할지 판단한다. */
public final class SchedulePolicy {

    private SchedulePolicy() {
    }

    /** 그 날짜가 job 의 수집 요일인지 */
    public static boolean runsOn(CollectorProperties.Job job, LocalDate day) {
        return job.days() == null || job.days().contains(day.getDayOfWeek());
    }

    public static boolean isDue(CollectorProperties.Job job, LocalDateTime now) {
        if (!runsOn(job, now.toLocalDate())) {
            return false;
        }
        LocalTime t = now.toLocalTime().withSecond(0).withNano(0);
        LocalTime start = job.startTime();
        LocalTime end = job.endTime();
        if (t.isBefore(start) || t.isAfter(end)) {
            return false;
        }
        long minutes = Duration.between(start, t).toMinutes();
        return minutes % Math.max(1, job.intervalMinutes()) == 0;
    }

    /** 하루 주기 수 (시작·끝 포함) */
    public static int cyclesPerDay(CollectorProperties.Job job) {
        long span = Duration.between(job.startTime(), job.endTime()).toMinutes();
        return (int) (span / Math.max(1, job.intervalMinutes())) + 1;
    }

    /** API 종류별 하루 예상 호출 수 (재시도 제외) */
    public static java.util.Map<String, Integer> expectedCalls(CollectorProperties.Job job) {
        int cycles = cyclesPerDay(job);
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        if (job.collect().contains(ApiType.LOCATION)) {
            m.put(ApiType.LOCATION.name(), cycles * job.routes().size());
        }
        if (job.collect().contains(ApiType.ARRIVAL)) {
            m.put(ApiType.ARRIVAL.name(), cycles * job.stations().size());
        }
        return m;
    }

    /** from 이후(다음 분부터) 처음으로 수집이 도는 시각. 8일 안에 없으면 null */
    public static LocalDateTime nextRun(CollectorProperties.Job job, LocalDateTime from) {
        LocalDateTime t = from.withSecond(0).withNano(0).plusMinutes(1);
        for (int i = 0; i < 60 * 24 * 8; i++) {
            if (isDue(job, t)) {
                return t;
            }
            t = t.plusMinutes(1);
        }
        return null;
    }
}
