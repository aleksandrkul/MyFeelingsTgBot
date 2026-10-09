package com.myfeelings.diary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Decides which diary day a moment belongs to.
 *
 * <p>The diary day starts at {@link Config#DIARY_DAY_START_HOUR}, not at midnight: an entry written
 * at 00:40 is about the evening that just ended, so it belongs to the previous calendar date.
 */
public final class DiaryDay {

    /** The diary date of a given moment. */
    public static LocalDate of(Instant moment, ZoneId zone) {
        return moment.atZone(zone).minusHours(Config.DIARY_DAY_START_HOUR).toLocalDate();
    }

    /** The diary date right now. */
    public static LocalDate today() {
        return of(Instant.now(), Config.ZONE);
    }

    private DiaryDay() {
    }
}
