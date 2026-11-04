package com.yongyongthon.buscollector.config;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulePolicyTest {

    private final CollectorProperties.Job job = new CollectorProperties.Job("t", true, "K", 1000,
            Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            "06:00", "10:00", 2, Set.of(ApiType.LOCATION), List.of(), List.of());

    // 2026-10-28 은 수요일, 2026-10-31 은 토요일
    @Test
    void 평일_시간대_안에서_2분_간격만() {
        assertThat(SchedulePolicy.isDue(job, LocalDateTime.of(2026, 10, 28, 6, 0, 0))).isTrue();
        assertThat(SchedulePolicy.isDue(job, LocalDateTime.of(2026, 10, 28, 6, 2, 0, 900_000_000))).isTrue();
        assertThat(SchedulePolicy.isDue(job, LocalDateTime.of(2026, 10, 28, 6, 3, 0))).isFalse();
        assertThat(SchedulePolicy.isDue(job, LocalDateTime.of(2026, 10, 28, 10, 0, 0))).isTrue();
        assertThat(SchedulePolicy.isDue(job, LocalDateTime.of(2026, 10, 28, 10, 2, 0))).isFalse();
        assertThat(SchedulePolicy.isDue(job, LocalDateTime.of(2026, 10, 28, 5, 58, 0))).isFalse();
    }

    @Test
    void 다음_수집_시각() {
        // 수요일 13:14 → 목요일 06:00
        assertThat(SchedulePolicy.nextRun(job, LocalDateTime.of(2026, 10, 28, 13, 14, 5)))
                .isEqualTo(LocalDateTime.of(2026, 10, 29, 6, 0));
        // 수요일 06:01:30 → 06:02
        assertThat(SchedulePolicy.nextRun(job, LocalDateTime.of(2026, 10, 28, 6, 1, 30)))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 6, 2));
        // 금요일 10:00 → 다음 월요일 06:00
        assertThat(SchedulePolicy.nextRun(job, LocalDateTime.of(2026, 10, 30, 10, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 6, 0));
    }

    @Test
    void 주말은_수집하지_않는다() {
        assertThat(SchedulePolicy.isDue(job, LocalDateTime.of(2026, 10, 31, 6, 0, 0))).isFalse();
    }

    private final CollectorProperties.Job weekend = new CollectorProperties.Job("w", true, "K", 1000,
            Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            "10:00", "15:00", 2, Set.of(ApiType.LOCATION), List.of(), List.of());

    @Test
    void 주말_job_은_토일_10시부터_15시까지() {
        assertThat(SchedulePolicy.isDue(weekend, LocalDateTime.of(2026, 10, 31, 10, 0))).isTrue();
        assertThat(SchedulePolicy.isDue(weekend, LocalDateTime.of(2026, 11, 1, 15, 0))).isTrue();
        assertThat(SchedulePolicy.isDue(weekend, LocalDateTime.of(2026, 10, 31, 15, 2))).isFalse();
        assertThat(SchedulePolicy.isDue(weekend, LocalDateTime.of(2026, 10, 30, 10, 0))).isFalse();
        assertThat(SchedulePolicy.cyclesPerDay(weekend)).isEqualTo(151);
        // 수요일 → 토요일 10:00, 일요일 15:00 → 다음 토요일 10:00
        assertThat(SchedulePolicy.nextRun(weekend, LocalDateTime.of(2026, 10, 28, 12, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 31, 10, 0));
        assertThat(SchedulePolicy.nextRun(weekend, LocalDateTime.of(2026, 11, 1, 15, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 7, 10, 0));
    }
}
