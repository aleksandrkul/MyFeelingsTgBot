package com.myfeelings.diary;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks in the evening what is left of the day.
 *
 * <p>The decision whether it is time is {@link #decide}, a pure function: the schedule is the part
 * worth being sure about, and waiting until 21:00 is no way to find out.
 */
public class Reminder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Reminder.class);

    /**
     * How late the reminder may still arrive. A machine asleep at 21:00 and woken at 23:00 should not
     * ask about the evening as if nothing happened, so past this the day is marked and left alone.
     */
    static final Duration WINDOW = Duration.ofMinutes(60);

    private static final long TICK_SECONDS = 60;

    /** What the tick decided to do. */
    enum Action {
        /** Not yet. */
        WAIT,
        /** Ask now. */
        SEND,
        /** Too late to ask; record the day as handled so it stays quiet. */
        MARK_SKIPPED,
        /** Nothing to do: switched off, already handled, already confirmed, or no chat known. */
        NOTHING
    }

    private final DiaryRepository repository;
    private final Settings settings;
    private final LongConsumer ask;
    private final Runnable everyTick;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "reminder"));

    public Reminder(DiaryRepository repository, LongConsumer ask) {
        this(repository, ask, () -> { });
    }

    /**
     * @param everyTick also run on each tick of the schedule, after the reminder's own check: the
     *     daily backup rides the thread that is already there rather than starting a second one
     */
    public Reminder(DiaryRepository repository, LongConsumer ask, Runnable everyTick) {
        this.repository = repository;
        this.settings = repository.settings();
        this.ask = ask;
        this.everyTick = everyTick;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(() -> {
            tick(LocalDateTime.now(Config.ZONE));
            try {
                everyTick.run();
            } catch (RuntimeException e) {
                log.error("A scheduled task failed", e);
            }
        }, 0, TICK_SECONDS, TimeUnit.SECONDS);
        log.info("Reminder watching, every {}s", TICK_SECONDS);
    }

    /**
     * One check. The current time is a parameter rather than read inside: that is what makes the
     * whole schedule, and not only {@link #decide}, testable without waiting for the evening.
     */
    void tick(LocalDateTime now) {
        try {
            LocalDate diaryDay = DiaryDay.of(now.atZone(Config.ZONE).toInstant(), Config.ZONE);
            Optional<LocalTime> at = settings.remindAt();
            Optional<Long> chatId = settings.chatId();

            Action action = decide(
                    now,
                    at.map(time -> momentFor(diaryDay, time)).orElse(null),
                    settings.remindedOn().filter(diaryDay::equals).isPresent(),
                    repository.card(diaryDay).isConfirmed(),
                    chatId.isPresent());

            switch (action) {
                case SEND -> {
                    settings.saveRemindedOn(diaryDay);
                    log.info("Reminding about {}", diaryDay);
                    ask.accept(chatId.orElseThrow());
                }
                case MARK_SKIPPED -> {
                    settings.saveRemindedOn(diaryDay);
                    log.info("Reminder for {} skipped: too late to ask", diaryDay);
                }
                default -> { }
            }
        } catch (SQLException e) {
            log.error("Reminder tick failed", e);
        } catch (RuntimeException e) {
            // A failing tick must not kill the schedule.
            log.error("Reminder tick failed unexpectedly", e);
        }
    }

    /**
     * Whether to ask now.
     *
     * @param moment when the reminder for the current diary day is due, or null when switched off
     * @param handled the current diary day has already been reminded about or deliberately skipped
     * @param confirmed the day's card is already confirmed, so there is nothing to ask
     */
    static Action decide(LocalDateTime now, LocalDateTime moment, boolean handled, boolean confirmed,
            boolean chatKnown) {
        if (moment == null || handled || confirmed || !chatKnown) {
            return Action.NOTHING;
        }
        if (now.isBefore(moment)) {
            return Action.WAIT;
        }
        return now.isAfter(moment.plus(WINDOW)) ? Action.MARK_SKIPPED : Action.SEND;
    }

    /**
     * When the reminder for a diary day is due.
     *
     * <p>A diary day runs from 04:00 to 04:00, so a time earlier than that falls on the next calendar
     * date: a reminder set for 02:00 asks about the day that is just ending, not the one ahead.
     */
    static LocalDateTime momentFor(LocalDate diaryDay, LocalTime at) {
        return at.isBefore(LocalTime.of(Config.DIARY_DAY_START_HOUR, 0))
                ? LocalDateTime.of(diaryDay.plusDays(1), at)
                : LocalDateTime.of(diaryDay, at);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
