package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("the evening reminder")
class ReminderTest {

    private static final LocalDateTime DUE = LocalDateTime.of(2026, 10, 7, 21, 0);

    @TempDir
    Path dir;

    @ParameterizedTest(name = "{1} -> {0}")
    @CsvSource({
            "WAIT,         2026-10-07T20:59, false, false, true",
            "SEND,         2026-10-07T21:00, false, false, true",
            "SEND,         2026-10-07T21:30, false, false, true",
            "SEND,         2026-10-07T21:59, false, false, true",
            "MARK_SKIPPED, 2026-10-07T22:01, false, false, true",
            "MARK_SKIPPED, 2026-10-08T02:00, false, false, true",
            "NOTHING,      2026-10-07T21:10, true,  false, true",
            "NOTHING,      2026-10-07T21:10, false, true,  true",
            "NOTHING,      2026-10-07T21:10, false, false, false",
    })
    @DisplayName("whether it is time is decided without looking at the clock")
    void decide(String expected, String now, boolean handled, boolean confirmed, boolean chatKnown) {
        assertEquals(expected, Reminder.decide(
                LocalDateTime.parse(now), DUE, handled, confirmed, chatKnown).toString());
    }

    @Test
    @DisplayName("switched off means silent")
    void switchedOff() {
        assertEquals(Reminder.Action.NOTHING,
                Reminder.decide(DUE.plusMinutes(10), null, false, false, true));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({"21:00, true", "' 07:05 ', true", "off, false", "OFF, false", "25:00, false", "вечером, false", "'', false"})
    @DisplayName("the time is read strictly")
    void parsing(String value, boolean parses) {
        assertEquals(parses, Settings.parseTime(value).isPresent());
    }

    @Test
    @DisplayName("a time before 04:00 belongs to the night that ends the diary day")
    void diaryDayBoundary() {
        LocalDate day = LocalDate.of(2026, 10, 7);
        assertEquals("2026-10-07T21:00", Reminder.momentFor(day, LocalTime.of(21, 0)).toString());
        assertEquals("2026-10-08T02:00", Reminder.momentFor(day, LocalTime.of(2, 0)).toString());
    }

    @Test
    @DisplayName("/remind shows, changes and switches off the time")
    void remindCommand() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "Оля")) {
            h.say("/remind");
            assertTrue(h.lastReply().contains("Напоминаю в 21:00"), "21:00 is the default: " + h.lastReply());

            h.say("/remind 22:15");
            assertTrue(h.lastReply().contains("Буду напоминать в 22:15"), h.lastReply());
            assertEquals("22:15", h.repository().settings().remindAt().orElseThrow().toString());

            h.say("/remind вечером");
            assertTrue(h.lastReply().contains("Формат: /remind"), h.lastReply());
            assertEquals("22:15", h.repository().settings().remindAt().orElseThrow().toString(),
                    "a bad value must change nothing");

            h.say("/remind off");
            assertTrue(h.lastReply().contains("Напоминаний больше не будет"), h.lastReply());
            h.say("/remind");
            assertTrue(h.lastReply().contains("Напоминание выключено"), h.lastReply());
        }
    }

    @Test
    @DisplayName("the chat is learned from the first message")
    void learnsTheChat() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "Оля")) {
            assertTrue(h.repository().settings().chatId().isEmpty());
            h.say("первая мысль");
            assertEquals(BotHarness.CHAT, h.repository().settings().chatId().orElseThrow());
        }
    }

    @Test
    @DisplayName("the reminder opens the same conversation /new does, once per day")
    void opensTheDayClosing() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "Оля")) {
            LocalDate today = DiaryDay.today();
            h.say("первая мысль");
            LocalDateTime evening = LocalDateTime.of(today, LocalTime.of(21, 5));

            h.clear();
            h.fireReminder(evening);
            assertTrue(h.lastReply().contains("Миша, что осталось от сегодняшнего дня?"), h.lastReply());
            assertEquals(today.toString(),
                    h.repository().settings().remindedOn().orElseThrow().toString());

            h.say("осталось ощущение, что меня услышали");
            assertTrue(h.lastReply().contains("Какое чувство у дня?"),
                    "the answer must land in the closing: " + h.lastReply());
            h.tap("feel:calm");
            h.tap("talk:yes");
            h.tap("card:ok");
            assertTrue(h.repository().card(today).isConfirmed());

            h.clear();
            h.fireReminder(evening.plusMinutes(1));
            assertTrue(h.replies.isEmpty(), "it must not come back the same day: " + h.replies);

            h.repository().settings().clearRemindedOn();
            h.clear();
            h.fireReminder(evening.plusMinutes(2));
            assertTrue(h.replies.isEmpty(), "a confirmed card must keep it quiet: " + h.replies);
        }
    }

    @Test
    @DisplayName("asleep through the evening means skipped, not asked late")
    void asleep() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "Оля")) {
            h.say("первая мысль");
            LocalDate open = DiaryDay.today().plusDays(1);

            h.clear();
            h.fireReminder(LocalDateTime.of(open.plusDays(1), LocalTime.of(2, 30)));
            assertTrue(h.replies.isEmpty(), "it asked about an evening it slept through: " + h.replies);
            assertEquals(open.toString(), h.repository().settings().remindedOn().orElseThrow().toString(),
                    "the day must be marked so it never asks late");
        }
    }

    @Test
    @DisplayName("the mark survives a restart")
    void markSurvivesRestart() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "Оля")) {
            LocalDate today = DiaryDay.today();
            h.say("первая мысль");
            h.repository().settings().saveRemindedOn(today);
            h.reopen();

            h.clear();
            h.fireReminder(LocalDateTime.of(today, LocalTime.of(21, 10)));
            assertTrue(h.replies.isEmpty(), "it re-reminded after a restart: " + h.replies);
        }
    }
}
