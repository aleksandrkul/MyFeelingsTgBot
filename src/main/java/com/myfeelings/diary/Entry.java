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

    /** How this entry reads when shown on its own, by {@code /last} and by {@code /undo}. */
    String shown(Messages messages) {
        return messages.get("entry.shown", entryDate,
                createdAt.atZone(Config.ZONE).format(TIME), text);
    }
}
