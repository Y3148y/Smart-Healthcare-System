package com.aihospital.shared.infrastructure.mybatis;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

/** Converts MyBatis map rows consistently across H2 and MySQL column-label casing. */
public final class RowValues {
    private RowValues() {}

    public static Object value(Map<String, Object> row, String column) {
        if (row.containsKey(column)) return row.get(column);
        for (Map.Entry<String, Object> entry : row.entrySet())
            if (entry.getKey().equalsIgnoreCase(column)) return entry.getValue();
        return null;
    }

    public static String string(Map<String, Object> row, String column) {
        Object value = value(row, column);
        return value == null ? null : value.toString();
    }

    public static int integer(Map<String, Object> row, String column) {
        Object value = value(row, column);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    public static LocalDateTime dateTime(Map<String, Object> row, String column) {
        Object value = value(row, column);
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        throw new IllegalStateException("Missing timestamp column: " + column);
    }
}
