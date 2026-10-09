package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("the one-time setup: language, then the two names")
class OnboardingTest {

    @TempDir
    Path dir;

    private Path db() {
        return dir.resolve("diary.db");
    }

    @Test
    @DisplayName("a fresh diary asks for the language, then both names, then stores entries")
    void freshDiary() throws Exception {
        try (BotHarness h = new BotHarness(db())) {
            h.say("/start");
            assertTrue(h.lastReply().contains("Choose the language"), h.lastReply());

            h.tap("lang:ru");
            assertTrue(h.previousReply().contains("Говорю по-русски"), h.previousReply());
            assertTrue(h.lastReply().contains("Как мне к тебе обращаться?"), h.lastReply());
            assertEquals(1, h.callbacksAnswered.get(), "the button must stop spinning");

            h.say("/summary 7");
            // One message carries both the refusal and the repeated question.
            assertTrue(h.lastReply().contains("Сначала закончим настройку"), h.lastReply());
            assertTrue(h.lastReply().contains("Как мне к тебе обращаться?"), h.lastReply());

            h.say("   ");
            assertTrue(h.lastReply().contains("Не понял имя"), "a blank name is refused");
            h.say("x".repeat(100));
            assertTrue(h.lastReply().contains("Не понял имя"), "an overlong name is refused");

            h.say("Миша");
            assertTrue(h.lastReply().contains("О каком человеке этот дневник?"), h.lastReply());
            h.say("К.");
            assertTrue(h.lastReply().contains("Готово, Миша. Дневник про К."), h.lastReply());

            assertEquals("Миша", h.repository().settings().ownerName().orElseThrow());
            assertEquals("К.", h.repository().settings().personName().orElseThrow());

            h.say("первая настоящая запись");
            assertTrue(h.lastReply().contains("Записал за"), h.lastReply());
            assertEquals(1, h.repository().count(), "the names must not become entries");
        }
    }

    @Test
    @DisplayName("the setup is not repeated after a restart")
    void notRepeated() throws Exception {
        try (BotHarness h = BotHarness.configured(db(), Lang.RU, "Миша", "К.")) {
            h.say("мысль");
            assertTrue(h.lastReply().contains("Записал за"), h.lastReply());
            h.reopen();
            h.clear();
            h.say("мысль после рестарта");
            assertTrue(h.lastReply().contains("Записал за"), "it asked again after a restart");
        }
    }

    @Test
    @DisplayName("/who shows both names and asks them again")
    void whoReAsks() throws Exception {
        try (BotHarness h = BotHarness.configured(db(), Lang.RU, "Миша", "К.")) {
            h.say("/who");
            assertTrue(h.previousReply().contains("Ты — Миша. Дневник про К."), h.previousReply());
            assertTrue(h.lastReply().contains("Как мне к тебе обращаться?"), h.lastReply());

            h.say("Михаил");
            assertTrue(h.lastReply().contains("О каком человеке этот дневник?"), h.lastReply());
            h.say("Т.");
            assertTrue(h.lastReply().contains("Готово, Михаил. Дневник про Т."), h.lastReply());
            assertEquals("Т.", h.repository().settings().personName().orElseThrow());
        }
    }

    @Test
    @DisplayName("a restart in the middle of /who leaves the old pair untouched")
    void whoIsAtomic() throws Exception {
        try (BotHarness h = BotHarness.configured(db(), Lang.RU, "Миша", "К.")) {
            h.say("/who");
            h.say("Михаил");
            h.reopen();

            assertEquals("Миша", h.repository().settings().ownerName().orElseThrow(),
                    "half of the change was applied");
            assertEquals("К.", h.repository().settings().personName().orElseThrow());

            h.clear();
            h.say("обычная запись");
            assertTrue(h.lastReply().contains("Записал за"), "a stale question survived the restart");
        }
    }

    @Test
    @DisplayName("an older diary with a language but no names keeps the message it was sent")
    void upgradedDiaryKeepsTheMessage() throws Exception {
        try (BotHarness h = new BotHarness(db())) {
            h.repository().settings().saveLanguage(Lang.RU);
            h.reopen();

            h.say("настоящая запись, а не имя");
            assertTrue(h.previousReply().contains("Записал за"),
                    "the message was eaten as a name: " + h.previousReply());
            assertTrue(h.lastReply().contains("Как мне к тебе обращаться?"), h.lastReply());
            assertEquals(1, h.repository().count());

            h.say("Миша");
            assertTrue(h.lastReply().contains("О каком человеке этот дневник?"), h.lastReply());

            h.reopen();
            h.clear();
            h.say("ещё запись");
            assertTrue(h.lastReply().contains("О каком человеке этот дневник?"),
                    "the setup must resume at the second question");
            assertEquals(2, h.repository().count(), "and keep that message too");

            h.say("К.");
            assertTrue(h.lastReply().contains("Готово, Миша. Дневник про К."), h.lastReply());
            assertEquals(2, h.repository().count(), "the name must not become an entry");
        }
    }
}
