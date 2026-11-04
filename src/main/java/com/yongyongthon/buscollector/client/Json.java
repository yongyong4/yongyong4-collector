package com.yongyongthon.buscollector.client;

import com.fasterxml.jackson.databind.JsonNode;

/** 응답 필드는 숫자/문자열/빈 문자열이 섞여 오므로 관대하게 꺼낸다. */
public final class Json {

    private Json() {
    }

    public static String str(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    public static Integer integer(JsonNode n, String field) {
        String s = str(n, field);
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(s);
            } catch (NumberFormatException e2) {
                return null;
            }
        }
    }

    public static Double dbl(JsonNode n, String field) {
        String s = str(n, field);
        if (s == null) {
            return null;
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
