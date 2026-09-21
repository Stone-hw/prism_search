package com.prismsearch.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Time parsing helpers tolerant to the various formats returned by upstream providers.
 */
public final class TimeUtil {

    private static final DateTimeFormatter[] FORMATTERS = new DateTimeFormatter[]{
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_INSTANT,
            DateTimeFormatter.RFC_1123_DATE_TIME
    };

    private TimeUtil() {
    }

    /**
     * Parse an ISO 8601 / RFC 3339 / RFC 1123 timestamp.
     *
     * @return parsed {@link LocalDateTime} in system default zone, or {@code null} when unparseable.
     */
    public static LocalDateTime parseIso(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        for (DateTimeFormatter fmt : FORMATTERS) {
            try {
                if (fmt == DateTimeFormatter.ISO_INSTANT) {
                    Instant instant = Instant.from(fmt.parse(text));
                    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
                }
                if (fmt == DateTimeFormatter.ISO_OFFSET_DATE_TIME || fmt == DateTimeFormatter.RFC_1123_DATE_TIME) {
                    OffsetDateTime odt = OffsetDateTime.parse(text, fmt);
                    return odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
                }
                if (fmt == DateTimeFormatter.ISO_ZONED_DATE_TIME) {
                    return java.time.ZonedDateTime.parse(text, fmt)
                            .withZoneSameInstant(ZoneId.systemDefault())
                            .toLocalDateTime();
                }
                return LocalDateTime.parse(text, fmt);
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }
        return null;
    }
}
