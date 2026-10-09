package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("the typing indicator outlives Telegram's five seconds")
class TypingIndicatorTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("it is resent while open and stops when closed")
    void resentAndStopped() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.EN, "Misha", "K.")) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            try {
                try (TypingIndicator ignored = TypingIndicator.start(
                        new Telegram(h.telegram), BotHarness.CHAT, scheduler)) {
                    Thread.sleep(9_000);
                }
                int whileOpen = h.typingActions.get();
                assertTrue(whileOpen >= 3,
                        "Telegram clears it after 5s, so 9s needs at least three sends, got " + whileOpen);

                Thread.sleep(5_000);
                assertEquals(whileOpen, h.typingActions.get(), "it kept sending after being closed");
            } finally {
                scheduler.shutdownNow();
            }
        }
    }
}
