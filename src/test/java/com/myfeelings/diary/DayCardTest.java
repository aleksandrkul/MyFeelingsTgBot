package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("a day's messages and two marks become one card")
class DayCardTest {

    @TempDir
    Path dir;

    private BotHarness harness() throws Exception {
        return BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "К.");
    }

    @Test
    @DisplayName("three messages plus a feeling and a mark end up as one confirmed card")
    void wholeDay() throws Exception {
        try (BotHarness h = harness()) {
            LocalDate today = DiaryDay.today();

            h.say("сказала, что перенесём разговор");
            h.say("всё ещё думаю об этом");
            h.say("в итоге поговорили вечером");
            assertEquals(3, h.repository().count());
            assertFalse(h.repository().card(today).isConfirmed());

            h.say("/new");
            assertTrue(h.lastReply().contains("Миша, что осталось от сегодняшнего дня?"), h.lastReply());

            h.say("осталось ощущение, что меня услышали");
            assertTrue(h.lastReply().contains("Какое чувство у дня?"), h.lastReply());
            assertEquals(4, h.repository().count(), "the answer joins the day");
            assertEquals(List.of("спокойно=feel:calm", "тревожно=feel:anxious", "тепло=feel:warm",
                            "усталость=feel:tired", "радостно=feel:glad"),
                    h.buttons);

            h.tap("feel:calm");
            assertTrue(h.lastReply().contains("Был сегодня разговор с К.?"), h.lastReply());
            assertEquals(Feeling.CALM, h.repository().card(today).feeling());

            h.tap("talk:yes");
            String card = h.lastReply();
            assertTrue(card.contains("Карточка за " + today), card);
            assertTrue(card.contains("сказала, что перенесём разговор"), card);
            assertTrue(card.contains("осталось ощущение"), card);
            assertTrue(card.contains("Чувство: спокойно"), card);
            assertTrue(card.contains("Разговор: был"), card);
            assertTrue(card.endsWith("Сохранить так?"), card);
            assertEquals(List.of("Сохранить=card:ok", "Дописать=card:edit"), h.buttons);
            assertFalse(h.repository().card(today).isConfirmed(), "showing is not confirming");

            h.tap("card:ok");
            assertTrue(h.lastReply().contains("Сохранил карточку за " + today), h.lastReply());
            assertTrue(h.repository().card(today).isConfirmed());
            assertEquals(4, h.repository().count(), "one card, four entries");
        }
    }

    @Test
    @DisplayName("the whole closing is one message that changes as the owner answers")
    void oneMessageChangesInPlace() throws Exception {
        try (BotHarness h = harness()) {
            LocalDate today = DiaryDay.today();
            h.say("мысль до закрытия");
            h.clear();

            h.say("/new");
            h.say("что осталось");
            h.tap("feel:warm");
            h.tap("talk:no");
            h.tap("card:ok");

            // Entries are acknowledged separately; the closing itself is one message from start to end.
            long closingMessages = h.replies.stream().filter(r -> !r.startsWith("Записал за")).count();
            assertEquals(1, closingMessages, h.replies.toString());
            assertEquals(4, h.edits.size(), "feeling, talked, card, saved: " + h.edits);

            String last = h.lastReply();
            assertTrue(last.contains("Карточка за " + today), "the saved card stays on screen: " + last);
            assertTrue(last.contains("Чувство: тепло"), last);
            assertTrue(last.endsWith("Сохранил карточку за " + today + "."), last);
            assertTrue(h.buttons.isEmpty(), "saving removes the buttons: " + h.buttons);
            assertTrue(h.repository().card(today).isConfirmed());
        }
    }

    @Test
    @DisplayName("text sent instead of a button is kept and the question asked again")
    void strayTextIsKept() throws Exception {
        try (BotHarness h = harness()) {
            h.say("/new");
            h.say("что осталось");
            assertTrue(h.lastReply().contains("Какое чувство у дня?"), h.lastReply());

            h.say("а, и ещё одна мысль");
            assertEquals(2, h.repository().count(), "the stray text must be kept");
            assertTrue(h.lastReply().contains("Какое чувство у дня?"), "the question must be asked again");
        }
    }

    @Test
    @DisplayName("keep-writing leaves a draft that /new picks up again")
    void keepWriting() throws Exception {
        try (BotHarness h = harness()) {
            LocalDate today = DiaryDay.today();
            h.say("/new");
            h.say("первый заход");
            h.tap("feel:tired");
            h.tap("talk:yes");
            h.tap("card:edit");
            assertTrue(h.lastReply().contains("Хорошо, дописывай"), h.lastReply());
            assertFalse(h.repository().card(today).isConfirmed());

            h.say("дописал строчку");
            assertTrue(h.lastReply().contains("Записал за"), "plain text must work again at once");

            h.say("/new");
            h.say("второй заход");
            h.tap("feel:warm");
            h.tap("talk:no");
            assertEquals(Feeling.WARM, h.repository().card(today).feeling(), "the feeling must be changeable");
            assertEquals(Boolean.FALSE, h.repository().card(today).talked());
            h.tap("card:ok");
            assertTrue(h.repository().card(today).isConfirmed());
        }
    }

    @Test
    @DisplayName("a message after confirmation joins the day and leaves the card confirmed")
    void laterMessageDoesNotReopen() throws Exception {
        try (BotHarness h = harness()) {
            LocalDate today = DiaryDay.today();
            h.say("/new");
            h.say("что осталось");
            h.tap("feel:calm");
            h.tap("talk:yes");
            h.tap("card:ok");

            h.say("ещё мысль после подтверждения");
            assertTrue(h.repository().card(today).isConfirmed(),
                    "a later message must not reopen a closed day");
            assertEquals(2, h.repository().count());
        }
    }

    @Test
    @DisplayName("other commands are held back, /cancel keeps the day's entries")
    void heldBackAndCancelled() throws Exception {
        try (BotHarness h = harness()) {
            h.say("/new");
            h.say("черновик перед отменой");
            h.say("/summary");
            assertTrue(h.lastReply().contains("Сейчас закрываем день"), h.lastReply());
            h.say("лента");
            assertTrue(h.lastReply().contains("Сейчас закрываем день"), "a trigger word is a command too");

            h.say("/cancel");
            assertTrue(h.lastReply().contains("Закрытие дня отменил"), h.lastReply());
            assertEquals(1, h.repository().count(), "the text written during it must stay");

            h.say("после отмены");
            assertTrue(h.lastReply().contains("Записал за"), h.lastReply());
        }
    }

    @Test
    @DisplayName("a tap on a card that is no longer open is answered")
    void staleTap() throws Exception {
        try (BotHarness h = harness()) {
            h.tap("feel:glad");
            assertTrue(h.lastReply().contains("Эта карточка больше не открыта"), h.lastReply());
        }
    }
}
