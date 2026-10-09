package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("the diary day starts at 04:00, not at midnight")
class DiaryDayTest {

    @ParameterizedTest(name = "{0} belongs to {1}")
    @CsvSource({
            "2026-10-06T23:30, 2026-10-06",
            "2026-10-07T00:00, 2026-10-06",
            "2026-10-07T00:40, 2026-10-06",
            "2026-10-07T03:59, 2026-10-06",
            "2026-10-07T04:00, 2026-10-07",
            "2026-10-07T12:00, 2026-10-07",
    })
    void boundary(String localTime, String expected) {
        ZoneId zone = Config.ZONE;
        Instant moment = LocalDateTime.parse(localTime).atZone(zone).toInstant();
        assertEquals(expected, DiaryDay.of(moment, zone).toString());
    }
}
