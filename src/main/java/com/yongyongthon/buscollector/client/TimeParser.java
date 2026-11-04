package com.yongyongthon.buscollector.client;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/** API queryTime ("2026-10-28 06:00:12.345" / "2026-10-28 06:00:12" / ISO) 을 KST LocalDateTime 으로. */
public final class TimeParser {

    private static final DateTimeFormatter FMT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().appendLiteral('T').optionalEnd()
            .appendPattern("HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .toFormatter();

    private TimeParser() {
    }

    public static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.trim(), FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
