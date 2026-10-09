package com.myfeelings.diary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * One diary entry, stored exactly as written.
 *
 * @param entryDate the diary day it belongs to (see {@link DiaryDay})
 * @param createdAt when the message actually arrived
 */
public record Entry(long id, LocalDate entryDate, Instant createdAt, String text) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** The time of day the entry was written, in the diary's timezone. The one place it is formatted. */
    String time() {
        return createdAt.atZone(Config.ZONE).format(TIME);
    }

    /** How this entry reads when shown on its own, by {@code /last} and by {@code /undo}. */
    String shown(Messages messages) {
        return messages.get("entry.shown", entryDate, time(), text);
    }
}
