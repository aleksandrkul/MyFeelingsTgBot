package com.myfeelings.diary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * What the day holds beyond its text: the feeling, whether there was a conversation, and whether the
 * owner has confirmed the card.
 *
 * <p>The day's text is not here — it is that day's rows in {@code entries}, read together.
 */
public record DayCard(LocalDate date, Feeling feeling, Boolean talked, Instant confirmedAt) {

    public static DayCard empty(LocalDate date) {
        return new DayCard(date, null, null, null);
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    public Optional<Feeling> feelingIfSet() {
        return Optional.ofNullable(feeling);
    }
}
