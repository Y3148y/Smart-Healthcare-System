package com.aihospital.shared.infrastructure.mybatis;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RowValuesTest {
    @Test
    void parsesDatabaseFormattedTimestampWithoutApplyingJvmTimezoneConversion() {
        var expected = LocalDateTime.of(2026, 10, 18, 7, 58, 34);
        assertEquals(expected, RowValues.dateTime(Map.of("created_at", "2026-10-18 07:58:34"), "created_at"));
    }
}
