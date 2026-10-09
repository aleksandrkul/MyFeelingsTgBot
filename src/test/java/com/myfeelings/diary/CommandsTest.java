package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("the everyday commands")
class CommandsTest {

    @TempDir
    Path dir;

    private BotHarness harness() throws Exception {
        return BotHarness.configured(dir.resolve("diary.db"), Lang.EN, "Misha", "K.");
    }

    @Test
    @DisplayName("a plain message is stored and /last shows it")
    void storeAndShow() throws Exception {
        try (BotHarness h = harness()) {
            h.say("/last");
            assertTrue(h.lastReply().contains("The diary is empty"), h.lastReply());

            h.say("we talked about boundaries today");
            assertTrue(h.lastReply().contains("Saved for "), h.lastReply());

            h.say("/last");
            assertTrue(h.lastReply().contains("we talked about boundaries today"), h.lastReply());
        }
    }

    @Test
    @DisplayName("/help lists the commands")
    void help() throws Exception {
        try (BotHarness h = harness()) {
            h.say("/help");
            for (String command : new String[]{"/new", "/date", "/last", "/undo", "/summary", "/feed",
                    "/remind", "/who", "/language"}) {
                assertTrue(h.lastReply().contains(command), command + " missing from /help");
            }
        }
    }

    @Test
    @DisplayName("/date backdates an entry and refuses nonsense")
    void date() throws Exception {
        try (BotHarness h = harness()) {
            h.say("/date 2026-10-01 an entry for last Wednesday");
            assertTrue(h.lastReply().contains("Saved for 2026-10-01"), h.lastReply());

            h.say("/date 2099-01-01 from the future");
            assertTrue(h.lastReply().contains("future"), h.lastReply());

            h.say("/date notadate some text");
            assertTrue(h.lastReply().contains("Expected YYYY-MM-DD"), h.lastReply());

            h.say("/date 2026-10-01");
            assertTrue(h.lastReply().contains("Usage: /date"), h.lastReply());

            assertEquals(1, h.repository().count(), "only the valid one may be stored");
        }
    }

    @Test
    @DisplayName("/undo asks with buttons before deleting and only then deletes")
    void undo() throws Exception {
        try (BotHarness h = harness()) {
            h.say("/undo");
            assertTrue(h.lastReply().contains("The diary is empty"), h.lastReply());

            h.say("an entry to remove");
            h.say("/undo");
            assertTrue(h.lastReply().contains("Delete this entry?"), h.lastReply());
            assertTrue(h.lastReply().contains("2 minutes"), h.lastReply());
            assertEquals(List.of("Delete=undo:yes", "Cancel=undo:no"), h.buttons);
            assertEquals(1, h.repository().count(), "asking must not delete");

            h.tap("undo:yes");
            assertTrue(h.lastReply().equals("Deleted."), "the offer becomes the outcome: " + h.lastReply());
            assertTrue(h.buttons.isEmpty(), "the buttons must be gone: " + h.buttons);
            assertEquals(0, h.repository().count());
        }
    }

    @Test
    @DisplayName("/undo on an entry near Telegram's length limit still offers its buttons")
    void undoLongEntry() throws Exception {
        try (BotHarness h = harness()) {
            h.say("x".repeat(4000));
            h.say("/undo");

            assertTrue(h.replies.stream().allMatch(r -> r.length() <= Telegram.MESSAGE_LIMIT), "a message was too long");
            assertEquals(List.of("Delete=undo:yes", "Cancel=undo:no"), h.buttons);
        }
    }

    @Test
    @DisplayName("Cancel leaves the entry, and a second tap on the same offer does nothing")
    void undoCancelled() throws Exception {
        try (BotHarness h = harness()) {
            h.say("keep me");
            h.say("/undo");
            h.tap("undo:no");
            assertTrue(h.lastReply().contains("Left the entry"), h.lastReply());
            assertEquals(1, h.repository().count());

            h.tap("undo:yes");
            assertTrue(h.lastReply().contains("no longer open"), h.lastReply());
            assertEquals(1, h.repository().count(), "an old button must not delete");
        }
    }

    @Test
    @DisplayName("the offer deletes the entry it showed, and a restart cancels it")
    void undoByIdAndRestart() throws Exception {
        try (BotHarness h = harness()) {
            h.say("first");
            h.say("/undo");
            h.say("written after the offer");
            h.tap("undo:yes");
            assertEquals(List.of("written after the offer"),
                    h.repository().findBetween(DiaryDay.today(), DiaryDay.today()).stream()
                            .map(Entry::text).toList(),
                    "the offered entry goes, not the newest one");

            h.say("/undo");
            h.reopen();
            h.tap("undo:yes");
            assertTrue(h.lastReply().contains("no longer open"), h.lastReply());
            assertEquals(1, h.repository().count(), "a restart must cancel the offer");
        }
    }

    @Test
    @DisplayName("unknown commands and non-text are answered plainly")
    void unknown() throws Exception {
        try (BotHarness h = harness()) {
            h.say("/nonsense");
            assertTrue(h.lastReply().contains("Unknown command"), h.lastReply());
            h.say("/skip");
            assertTrue(h.lastReply().contains("Unknown command"), "/skip no longer exists");
        }
    }

    @Test
    @DisplayName("a stranger gets no reply and nothing is stored")
    void stranger() throws Exception {
        try (BotHarness h = harness()) {
            h.clear();
            h.say(BotHarness.STRANGER, "I am not the owner, store me");
            assertTrue(h.replies.isEmpty(), "a stranger was answered: " + h.replies);
            assertEquals(0, h.repository().count());
        }
    }
}
