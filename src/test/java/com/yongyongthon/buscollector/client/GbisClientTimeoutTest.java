package com.yongyongthon.buscollector.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yongyongthon.buscollector.config.CollectorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 HTTP 서버로 타임아웃·재시도 동작 확인. 응답이 멈춰도 수집 주기가 끝나야 한다 */
class GbisClientTimeoutTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 헤더와 본문 일부만 보내고 멈춘다
        server.createContext("/stall", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            out.write("{\"response\":".getBytes(StandardCharsets.UTF_8));
            out.flush();
            sleep(20_000);
            ex.close();
        });
        // 첫 호출은 500, 두 번째는 정상 → 재시도로 성공
        server.createContext("/flaky", ex -> {
            int n = hits.incrementAndGet();
            byte[] body = (n == 1 ? "oops" : """
                    {"response":{"msgHeader":{"queryTime":"2026-10-28 06:00:01","resultCode":0},
                    "msgBody":{"busLocationList":[{"plateNo":"경기78아1414"}]}}}""").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(n == 1 ? 500 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GbisClient client() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GbisClient(new CollectorProperties("Asia/Seoul", false, "", "0 0 0 * * *",
                new CollectorProperties.Api(base, Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                        Duration.ofMillis(100), Set.of(0, 4), null), List.of()), new ObjectMapper());
    }

    @Test
    void 본문이_멈춰도_제한시간_안에_실패로_끝난다() {
        long t0 = System.currentTimeMillis();
        ApiResponse r = client().get("/stall", Map.of("routeId", "1"), "key");
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(r.ok()).isFalse();
        assertThat(r.attempts()).isEqualTo(2);
        // 시도당 (연결 1초 + 응답 1초) 이내 × 2회 + 재시도 대기 → 여유 있게 8초 안
        assertThat(elapsed).isLessThan(8_000);
    }

    @Test
    void 일시_오류는_재시도로_성공한다() {
        ApiResponse r = client().get("/flaky", Map.of("routeId", "1"), "key");
        assertThat(r.ok()).isTrue();
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.items()).hasSize(1);
        assertThat(r.error()).startsWith("재시도 후 성공").contains("HTTP 500");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
