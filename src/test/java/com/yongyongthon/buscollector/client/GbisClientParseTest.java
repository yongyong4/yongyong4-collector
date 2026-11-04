package com.yongyongthon.buscollector.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yongyongthon.buscollector.config.CollectorProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 응답(2026-10-27 확인) 형태로 파싱 검증 */
class GbisClientParseTest {

    private final GbisClient client = new GbisClient(new CollectorProperties("Asia/Seoul", false, "", "0 0 0 * * *",
            new CollectorProperties.Api("http://x", Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                    Duration.ZERO, Set.of(0, 4), null), List.of()), new ObjectMapper());

    @Test
    void 정상_목록() {
        var a = new GbisClient.Attempt();
        client.parse("""
                {"response":{"comMsgHeader":"","msgHeader":{"queryTime":"2026-10-27 12:36:25.266","resultCode":0,
                "resultMessage":"정상적으로 처리되었습니다."},"msgBody":{"busLocationList":[
                {"crowded":0,"lowPlate":2,"plateNo":"경기78아1414","remainSeatCnt":69,"routeId":228000174,
                 "stateCd":0,"stationId":228002024,"stationSeq":9},{"plateNo":"경기78아1151","remainSeatCnt":""}]}}}
                """, a);
        assertThat(a.ok).isTrue();
        assertThat(a.queryTime).isEqualTo(LocalDateTime.of(2026, 10, 27, 12, 36, 25, 266_000_000));
        assertThat(a.items).hasSize(2);
        assertThat(Json.integer(a.items.get(0), "remainSeatCnt")).isEqualTo(69);
        assertThat(Json.str(a.items.get(0), "routeId")).isEqualTo("228000174");
        assertThat(Json.integer(a.items.get(1), "remainSeatCnt")).isNull();
    }

    @Test
    void 단건은_객체로_온다() {
        var a = new GbisClient.Attempt();
        client.parse("""
                {"response":{"msgHeader":{"queryTime":"2026-10-27 12:36:38.313","resultCode":0},
                "msgBody":{"busStationList":{"mobileNo":" 29307","stationId":228000684}}}}
                """, a);
        assertThat(a.items).hasSize(1);
    }

    @Test
    void 결과없음_코드4는_성공() {
        var a = new GbisClient.Attempt();
        client.parse("""
                {"response":{"msgHeader":{"queryTime":"2026-10-27 12:36:30.685","resultCode":4,
                "resultMessage":"결과가 존재하지 않습니다."},"msgBody":null}}
                """, a);
        assertThat(a.ok).isTrue();
        assertThat(a.items).isEmpty();
    }

    @Test
    void 게이트웨이_오류는_실패_재시도안함() {
        var a = new GbisClient.Attempt();
        client.parse("""
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
                "returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}
                """, a);
        assertThat(a.ok).isFalse();
        assertThat(a.retryable).isFalse();
        assertThat(a.resultCode).isEqualTo(30);
    }

    @Test
    void XML_응답은_raw_text() {
        var a = new GbisClient.Attempt();
        client.parse("<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>", a);
        assertThat(a.ok).isFalse();
        assertThat(a.rawText).startsWith("<OpenAPI");
        assertThat(a.rawJson).isNull();
    }
}
