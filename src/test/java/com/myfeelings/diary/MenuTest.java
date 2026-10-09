package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("the persistent keyboard puts the three main actions one tap away")
class MenuTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("it appears when the setup finishes, not before")
    void attachedAfterOnboarding() throws Exception {
        try (BotHarness h = new BotHarness(dir.resolve("diary.db"))) {
            h.say("привет");
            h.tap("lang:ru");
            h.say("Миша");
            assertEquals(0, h.keyboardsAttached.get(), "no menu while the diary is still asking who it is for");

            h.say("К.");
            assertEquals(1, h.keyboardsAttached.get());
            assertEquals(List.of("Итог", "Лента", "Закрыть день"), h.keyboard);
            assertTrue(h.keyboardPersistent, "persistent and resized");
            assertFalse(h.keyboardPlaceholder.isBlank(), "the input field invites the owner to write");
        }
    }

    @Test
    @DisplayName("it is replaced when the language changes, because the labels are localized")
    void reattachedOnLanguageChange() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "К.")) {
            h.say("/language");
            h.tap("lang:en");

            assertEquals(1, h.keyboardsAttached.get());
            assertEquals(List.of("Summary", "Feed", "Close the day"), h.keyboard);
        }
    }

    @Test
    @DisplayName("a label is just text: each one reaches the handler of its command")
    void labelsReachTheCommands() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "К.")) {
            h.say("Лента");
            assertTrue(h.lastReply().contains("Дневник пуст"), h.lastReply());

            h.say("Закрыть день");
            assertTrue(h.lastReply().contains("Миша, что осталось от сегодняшнего дня?"), h.lastReply());
            h.say("/cancel");

            h.say("Итог");
            String answer = h.awaitReply(reply -> reply.startsWith("Нет записей за"), Duration.ofSeconds(5));
            assertTrue(answer != null, "the summary handler was not reached: " + h.replies);
        }
    }
}
