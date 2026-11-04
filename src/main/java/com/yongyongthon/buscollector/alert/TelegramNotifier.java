package com.yongyongthon.buscollector.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 텔레그램 봇으로 메시지를 보낸다. 토큰·chat_id 가 없으면 아무것도 하지 않는다.
 * 전송은 비동기이며 실패해도 수집에 영향을 주지 않는다. 토큰은 로그에 남기지 않는다.
 */
@Component
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final String token;
    private final String chatId;
    private final String healthcheckUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public TelegramNotifier(@Value("${TELEGRAM_BOT_TOKEN:}") String token,
                            @Value("${TELEGRAM_CHAT_ID:}") String chatId,
                            @Value("${HEALTHCHECK_URL:}") String healthcheckUrl,
                            ObjectMapper mapper) {
        this.token = token.trim();
        this.chatId = chatId.trim();
        this.healthcheckUrl = healthcheckUrl.trim();
        this.mapper = mapper;
    }

    public boolean enabled() {
        return !token.isEmpty() && !chatId.isEmpty();
    }

    public void send(String text) {
        if (!enabled()) {
            log.debug("텔레그램 미설정, 알림 생략: {}", text);
            return;
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "chat_id", chatId, "text", text, "disable_web_page_preview", true));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((res, err) -> {
                        if (err != null) {
                            log.warn("텔레그램 전송 실패: {}", err.toString());
                        } else if (res.statusCode() != 200) {
                            log.warn("텔레그램 전송 실패: HTTP {} {}", res.statusCode(), abbreviate(res.body()));
                        }
                    });
        } catch (Exception e) {
            log.warn("텔레그램 전송 실패: {}", e.toString());
        }
    }

    /** 외부 감시 서비스(healthchecks.io 등)에 '살아 있음' 신호. URL 이 없으면 생략 */
    public void ping() {
        if (healthcheckUrl.isEmpty()) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(healthcheckUrl)).timeout(Duration.ofSeconds(10)).GET().build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((res, err) -> {
                        if (err != null) {
                            log.debug("healthcheck ping 실패: {}", err.toString());
                        }
                    });
        } catch (Exception e) {
            log.debug("healthcheck ping 실패: {}", e.toString());
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
