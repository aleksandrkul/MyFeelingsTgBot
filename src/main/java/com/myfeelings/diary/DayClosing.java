package com.myfeelings.diary;

import java.time.LocalDate;

/**
 * The short conversation that closes a day: what is left of it, the feeling, whether there was a
 * conversation, then the card itself for confirmation.
 *
 * <p>Only the step lives here. Every piece of text the owner sends is already stored as an entry
 * before this moves on, so dropping an abandoned closing costs nothing.
 */
public class DayClosing {

    public enum Step { TEXT, FEELING, TALKED, CONFIRM }

    private final LocalDate date;
    private final Long chatId;
    private Step step = Step.TEXT;
    private Integer messageId;

    public DayClosing(LocalDate date, Long chatId) {
        this.date = date;
        this.chatId = chatId;
    }

    public Long chatId() {
        return chatId;
    }

    /** The one message the whole conversation is held in; null until it has been sent. */
    public Integer messageId() {
        return messageId;
    }

    public void trackMessage(Integer messageId) {
        this.messageId = messageId;
    }

    public LocalDate date() {
        return date;
    }

    public Step step() {
        return step;
    }

    public void moveTo(Step step) {
        this.step = step;
    }

    /**
     * A closing is for one diary day and expires when that day is no longer the current one.
     *
     * <p>It used to expire on a fixed timer, which was too short once the evening question comes from
     * the reminder: an answer two hours later is still an answer about that evening. Tying it to the
     * diary day keeps the only protection that mattered — tomorrow's first thought is its own entry.
     */
    public boolean isExpired() {
        return !DiaryDay.today().equals(date);
    }
}
