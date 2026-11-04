package com.yongyongthon.buscollector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

@ConfigurationProperties("collector")
public record CollectorProperties(
        String zone,
        boolean initOnStartup,
        String adminToken,
        String summaryCron,
        Api api,
        List<Job> jobs
) {

    public record Api(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            int retries,
            Duration retryDelay,
            Set<Integer> okResultCodes,
            Paths paths
    ) {
    }

    public record Paths(
            String busLocation,
            String busArrival,
            String routeList,
            String routeStations,
            String stationSearch
    ) {
    }

    /** 수집 작업 1개. 인증키·시간대·간격·대상이 job 단위로 독립적이다. */
    public record Job(
            String name,
            boolean enabled,
            String apiKeyEnv,
            int dailyLimit,
            Set<DayOfWeek> days,
            String start,
            String end,
            int intervalMinutes,
            Set<ApiType> collect,
            List<RouteTarget> routes,
            List<StationTarget> stations
    ) {
        public LocalTime startTime() {
            return LocalTime.parse(start);
        }

        public LocalTime endTime() {
            return LocalTime.parse(end);
        }

        public List<RouteTarget> routes() {
            return routes == null ? List.of() : routes;
        }

        public List<StationTarget> stations() {
            return stations == null ? List.of() : stations;
        }
    }

    /** routeId 를 직접 주면 조회를 건너뛴다. startStation / region 은 '포함' 조건. */
    public record RouteTarget(String name, String routeId, String startStation, String region) {
    }

    /** stationId 를 직접 주면 조회를 건너뛴다. */
    public record StationTarget(String mobileNo, String stationId, String name) {
    }
}
