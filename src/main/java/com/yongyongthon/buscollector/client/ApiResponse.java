package com.yongyongthon.buscollector.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API 호출 1건(재시도 포함)의 결과.
 *
 * @param rawJson JSON 응답이면 압축된 원문, 아니면 null
 * @param rawText JSON 이 아닌 응답(XML·HTML 등) 원문
 * @param items   msgBody 안의 목록. 단건이면 1개짜리 리스트
 */
public record ApiResponse(
        LocalDateTime calledAt,
        Integer httpStatus,
        Integer resultCode,
        String resultMsg,
        LocalDateTime queryTime,
        boolean ok,
        int attempts,
        long durationMs,
        String rawJson,
        String rawText,
        List<JsonNode> items,
        String error
) {
}
