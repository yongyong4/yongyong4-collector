package com.yongyongthon.buscollector.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yongyongthon.buscollector.config.CollectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 경기도 버스정보(GBIS) 공공데이터 API 클라이언트.
 * HTTP 200 이어도 resultCode 로 오류를 주므로 둘 다 검사하고, 실패 시 설정된 횟수만큼 재시도한다.
 * 인증키는 URL 에만 들어가며 로그에는 남기지 않는다.
 */
@Component
public class GbisClient {

    private static final Logger log = LoggerFactory.getLogger(GbisClient.class);

    private final CollectorProperties.Api api;
    private final ObjectMapper mapper;
    private final ZoneId zone;
    private final HttpClient http;

    public GbisClient(CollectorProperties props, ObjectMapper mapper) {
        this.api = props.api();
        this.mapper = mapper;
        this.zone = ZoneId.of(props.zone());
        this.http = HttpClient.newBuilder()
                .connectTimeout(api.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public ApiResponse get(String path, Map<String, String> params, String serviceKey) {
        LocalDateTime calledAt = LocalDateTime.now(zone);
        long t0 = System.currentTimeMillis();
        int maxAttempts = 1 + Math.max(0, api.retries());
        Attempt last = null;
        int attempts = 0;
        List<String> earlier = new ArrayList<>();
        while (attempts < maxAttempts) {
            attempts++;
            last = attempt(path, params, serviceKey);
            if (last.ok || !last.retryable) {
                break;
            }
            earlier.add(attempts + "차: " + last.error);
            if (attempts < maxAttempts) {
                log.warn("API 실패, 재시도 {} {}: {}", path, params, last.error);
                sleep(api.retryDelay().toMillis());
            }
        }
        // 재시도 끝에 성공해도 앞선 실패 사유는 남긴다 (원인 추적용)
        String error = last.ok && !earlier.isEmpty() ? "재시도 후 성공 (" + String.join(" / ", earlier) + ")" : last.error;
        return new ApiResponse(calledAt, last.httpStatus, last.resultCode, last.resultMsg, last.queryTime,
                last.ok, attempts, System.currentTimeMillis() - t0, last.rawJson, last.rawText,
                last.items, error);
    }

    private Attempt attempt(String path, Map<String, String> params, String serviceKey) {
        Attempt a = new Attempt();
        try {
            HttpRequest req = HttpRequest.newBuilder(buildUri(path, params, serviceKey))
                    .timeout(api.readTimeout())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            // HttpRequest.timeout 은 헤더 수신까지만 적용된다. 본문이 멈춰도 주기가 끝나도록 전체 시간에 상한을 둔다
            CompletableFuture<HttpResponse<String>> future =
                    http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> res;
            try {
                res = future.get(api.connectTimeout().plus(api.readTimeout()).toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                a.error = "응답 시간 초과 (" + api.connectTimeout().plus(api.readTimeout()).toSeconds() + "초)";
                return a;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                a.error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                return a;
            }
            a.httpStatus = res.statusCode();
            parse(res.body(), a);
            if (a.httpStatus != 200) {
                a.ok = false;
                a.error = prefix("HTTP " + a.httpStatus, a.error);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            a.error = "interrupted";
            a.retryable = false;
        } catch (Exception e) {
            a.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return a;
    }

    /** 응답 본문을 해석해 resultCode·queryTime·items 를 채운다. 테스트를 위해 패키지 공개. */
    void parse(String body, Attempt a) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            a.rawText = body;
            a.error = "JSON 아닌 응답: " + abbreviate(body, 300);
            return;
        }
        a.rawJson = root.toString();

        // data.go.kr 게이트웨이 오류 (인증키 미등록, 한도 초과 등)
        JsonNode gw = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        if (!gw.isMissingNode()) {
            a.resultCode = toInt(gw.path("returnReasonCode").asText(null));
            a.resultMsg = gw.path("errMsg").asText("") + " / " + gw.path("returnAuthMsg").asText("");
            a.error = "게이트웨이 오류 " + a.resultCode + ": " + a.resultMsg;
            a.retryable = false;
            return;
        }

        JsonNode resp = root.has("response") ? root.path("response") : root;
        JsonNode header = resp.path("msgHeader");
        a.resultCode = toInt(header.path("resultCode").asText(null));
        a.resultMsg = header.path("resultMessage").asText(null);
        a.queryTime = TimeParser.parse(header.path("queryTime").asText(null));

        JsonNode body2 = resp.path("msgBody");
        if (body2.isObject()) {
            for (JsonNode child : body2) {
                if (child.isArray()) {
                    child.forEach(a.items::add);
                    break;
                }
                if (child.isObject()) {
                    a.items.add(child);
                    break;
                }
            }
        }

        if (a.resultCode == null) {
            a.error = "resultCode 없음: " + abbreviate(body, 300);
        } else if (api.okResultCodes().contains(a.resultCode)) {
            a.ok = true;
        } else {
            a.error = "resultCode " + a.resultCode + ": " + a.resultMsg;
        }
    }

    private URI buildUri(String path, Map<String, String> params, String serviceKey) {
        // 디코딩 키는 인코딩해서, 이미 인코딩된 키(% 포함)는 그대로 붙인다
        String key = serviceKey.contains("%") ? serviceKey : URLEncoder.encode(serviceKey, StandardCharsets.UTF_8);
        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return URI.create(api.baseUrl() + path + "?" + query + "&format=json&serviceKey=" + key);
    }

    static Integer toInt(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String prefix(String p, String rest) {
        return rest == null ? p : p + " / " + rest;
    }

    private static String abbreviate(String s, int n) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ");
        return one.length() <= n ? one : one.substring(0, n) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static final class Attempt {
        Integer httpStatus;
        Integer resultCode;
        String resultMsg;
        LocalDateTime queryTime;
        boolean ok;
        boolean retryable = true;
        String rawJson;
        String rawText;
        final List<JsonNode> items = new ArrayList<>();
        String error;
    }
}
