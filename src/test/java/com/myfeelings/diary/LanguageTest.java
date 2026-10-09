package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("the reply language is the owner's choice")
class LanguageTest {

    @TempDir
    Path dir;

    private Path db() {
        return dir.resolve("diary.db");
    }

    /** The names are set so that only the language is still unchosen. */
    private BotHarness namedButLanguageless() throws Exception {
        BotHarness h = new BotHarness(db());
        h.repository().settings().saveOwnerName("Миша");
        h.repository().settings().savePersonName("К.");
        h.reopen();
        return h;
    }

    @Test
    @DisplayName("the first thought is kept, then the picker is offered in both languages")
    void firstContact() throws Exception {
        try (BotHarness h = namedButLanguageless()) {
            h.say("первая мысль ещё до выбора языка");

            assertTrue(h.replies.get(0).contains("Записал за") && h.replies.get(0).contains("Saved for"),
                    "the first entry must be acknowledged in both languages: " + h.replies.get(0));
            assertEquals(1, h.repository().count(), "a settings screen must not cost the first thought");

            String picker = h.replies.get(1);
            assertEquals(2, picker.split("\n").length, "the picker must not repeat itself: " + picker);
            assertEquals(List.of("🇺🇸 English=lang:en", "🇷🇺 Русский=lang:ru"),
                    h.buttons, "a flag and the language's own name on each button");
        }
    }

    @Test
    @DisplayName("a command before the choice re-offers the picker and does nothing else")
    void commandsWait() throws Exception {
        try (BotHarness h = namedButLanguageless()) {
            h.say("/summary 7");
            assertTrue(h.lastReply().contains("Choose the language"), h.lastReply());
            assertEquals(0, h.repository().count());
        }
    }

    @Test
    @DisplayName("the choice is stored, survives a restart, and /language changes it")
    void choiceSticks() throws Exception {
        try (BotHarness h = namedButLanguageless()) {
            h.tap("lang:ru");
            assertTrue(h.lastReply().contains("Говорю по-русски"), h.lastReply());
            assertEquals("ru", h.repository().settings().language().orElseThrow().code());

            h.say("/help");
            assertTrue(h.lastReply().startsWith("Дневник."), h.lastReply());

            h.reopen();
            h.clear();
            h.say("/help");
            assertTrue(h.lastReply().startsWith("Дневник."), "the language did not survive a restart");

            h.clear();
            h.say("/language");
            assertEquals(2, h.buttons.size());
            assertEquals("Выбери язык, на котором мне с тобой говорить.", h.lastReply(),
                    "after a choice the question is asked once, in that language");

            h.tap("lang:en");
            assertTrue(h.lastReply().contains("Speaking English"), h.lastReply());
            h.say("/help");
            assertTrue(h.lastReply().startsWith("Diary."), h.lastReply());
            assertEquals("en", h.repository().settings().language().orElseThrow().code());
        }
    }

    @Test
    @DisplayName("the day question follows the chosen language")
    void dayQuestionFollows() throws Exception {
        try (BotHarness h = BotHarness.configured(db(), Lang.RU, "Миша", "К.")) {
            h.say("/new");
            assertTrue(h.lastReply().contains("Миша, что осталось от сегодняшнего дня?"), h.lastReply());
            h.say("/cancel");

            h.tap("lang:en");
            h.say("/new");
            assertTrue(h.lastReply().contains("Миша, what is left of today?"), h.lastReply());
        }
    }

    @Test
    @DisplayName("a stranger cannot change the language")
    void strangerCannotChange() throws Exception {
        try (BotHarness h = BotHarness.configured(db(), Lang.RU, "Миша", "К.")) {
            h.clear();
            h.tap(BotHarness.STRANGER, "lang:en");
            assertTrue(h.replies.isEmpty(), "a stranger was answered: " + h.replies);
            assertEquals("ru", h.repository().settings().language().orElseThrow().code());
        }
    }
}
