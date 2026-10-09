package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("/reset erases the diary after a backup, and the keyboard reaches old diaries")
class ResetTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("nothing is erased until the button is tapped; then a copy is kept and the setup restarts")
    void reset() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.EN, "Misha", "K.")) {
            h.say("a thought");
            h.say("/reset");
            assertTrue(h.lastReply().contains("Erase the whole diary"), h.lastReply());
            assertEquals(1, h.repository().count(), "asking must not erase");

            h.tap("reset:no");
            assertEquals(1, h.repository().count());

            h.say("/reset");
            h.tap("reset:yes");
            assertEquals(0, h.repository().count());
            assertTrue(h.repository().settings().ownerName().isEmpty(), "the names are gone too");
            assertTrue(h.lastReply().contains("Choose the language") || h.lastReply().contains("Выбери"),
                    "the setup starts again: " + h.lastReply());
            try (var copies = Files.list(dir.resolve("backups"))) {
                assertEquals(1, copies.count(), "a copy must be kept");
            }

            h.tap("lang:en");
            h.say("Misha");
            h.say("K.");
            h.say("a new first thought");
            assertTrue(h.lastReply().contains("Saved for"), h.lastReply());
        }
    }

    @Test
    @DisplayName("a reset button that outlived a restart erases nothing")
    void staleTap() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.EN, "Misha", "K.")) {
            h.say("keep me");
            h.say("/reset");
            h.reopen();
            h.tap("reset:yes");
            assertTrue(h.lastReply().contains("no longer open"), h.lastReply());
            assertEquals(1, h.repository().count());
        }
    }

    @Test
    @DisplayName("a diary set up before the keyboard existed gets it once, on the next message")
    void keyboardForOldDiaries() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "К.", false)) {
            h.say("запись");
            assertEquals(1, h.keyboardsAttached.get());
            assertEquals(java.util.List.of("Итог", "Лента", "Закрыть день"), h.keyboard);
            h.say("ещё запись");
            assertEquals(1, h.keyboardsAttached.get(), "only once");
            assertEquals(2, h.repository().count());
        }
    }
}
