package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("the feed: today on top, earlier grouped by month")
class FeedTest {

    @TempDir
    Path dir;

    private BotHarness harness() throws Exception {
        return BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "К.");
    }

    @Test
    @DisplayName("an empty diary says so")
    void empty() throws Exception {
        try (BotHarness h = harness()) {
            h.say("лента");
            assertTrue(h.lastReply().contains("Дневник пуст"), h.lastReply());
        }
    }

    @Test
    @DisplayName("today first, then the month, with the marks on one line")
    void shortFeed() throws Exception {
        try (BotHarness h = harness()) {
            LocalDate today = DiaryDay.today();
            h.repository().save(today.minusDays(2), "позавчера обсуждали планы");
            h.repository().save(today.minusDays(1), "вчера перенесли разговор");
            h.repository().setFeeling(today.minusDays(1), Feeling.ANXIOUS);
            h.repository().setTalked(today.minusDays(1), false);
            h.repository().save(today, "сегодня наконец поговорили");
            h.repository().setFeeling(today, Feeling.CALM);
            h.repository().setTalked(today, true);

            h.clear();
            h.say("лента");
            assertEquals(1, h.replies.size(), "a short feed is one message");
            String feed = h.lastReply();

            assertTrue(feed.startsWith("Сегодня, "), feed);
            assertTrue(feed.indexOf("сегодня наконец") < feed.indexOf("вчера перенесли"),
                    "newest text must come first");
            assertTrue(feed.contains("спокойно · разговор был"), feed);
            assertTrue(feed.contains("тревожно · разговора не было"), feed);
            assertTrue(feed.contains("Это всё: 3 дня, 3 записи"), "wrong plural form: " + feed);
        }
    }

    @Test
    @DisplayName("the trigger word is exact: a sentence starting with it stays an entry")
    void triggerIsExact() throws Exception {
        try (BotHarness h = harness()) {
            h.say("лента вчера была другая");
            assertTrue(h.lastReply().contains("Записал за"), h.lastReply());
            assertEquals(1, h.repository().count());
        }
    }

    @Test
    @DisplayName("/feed N narrows it and a bad argument shows the usage")
    void arguments() throws Exception {
        try (BotHarness h = harness()) {
            LocalDate today = DiaryDay.today();
            h.repository().save(today.minusDays(5), "давняя запись");
            h.repository().save(today, "сегодняшняя запись");

            h.clear();
            h.say("/feed 2");
            assertFalse(h.lastReply().contains("давняя запись"), "/feed 2 must leave out older days");

            h.say("/feed завтра");
            assertTrue(h.lastReply().contains("Формат: лента"), h.lastReply());
        }
    }

    @Test
    @DisplayName("70 days split across messages without cutting a day in half")
    void longFeed() throws Exception {
        try (BotHarness h = harness()) {
            LocalDate today = DiaryDay.today();
            for (int back = 0; back < 70; back++) {
                LocalDate date = today.minusDays(back);
                h.repository().save(date, "день " + back + ": "
                        + "обсуждали договорённости и реакцию на перенос планов. ".repeat(3));
                h.repository().save(date, "день " + back + " вторая запись: что я почувствовал.");
                h.repository().setFeeling(date, Feeling.values()[back % 5]);
                h.repository().setTalked(date, back % 2 == 0);
            }

            h.clear();
            h.say("лента");

            assertTrue(h.replies.size() > 1, "a long feed must be split");
            for (String message : h.replies) {
                assertTrue(message.length() <= Telegram.MESSAGE_LIMIT,
                        "a message exceeds the Telegram limit: " + message.length());
            }

            for (int back = 0; back < 70; back++) {
                int first = messageContaining(h.replies, "день " + back + ": ");
                int second = messageContaining(h.replies, "день " + back + " вторая запись");
                assertTrue(first >= 0 && first == second,
                        "day " + back + " was cut across messages (" + first + " and " + second + ")");
            }

            List<String> headers = monthHeaders(h.replies);
            assertEquals(headers.size(), new LinkedHashSet<>(headers).size(),
                    "a month appears twice: " + headers);
            assertTrue(h.replies.get(0).startsWith("Сегодня,"), "today must stay at the very top");
        }
    }

    @Test
    @DisplayName("in English the months are English")
    void englishMonths() throws Exception {
        try (BotHarness h = BotHarness.configured(dir.resolve("en.db"), Lang.EN, "Misha", "K.")) {
            LocalDate today = DiaryDay.today();
            h.repository().save(today, "today");
            h.repository().save(today.minusDays(40), "forty days ago");

            h.clear();
            h.say("feed");
            assertTrue(h.replies.get(0).startsWith("Today,"), h.replies.get(0));
            assertTrue(String.join("\n", h.replies).matches("(?s).*\\b(August|September|October)\\s\\d{4}\\b.*"),
                    "no English month header: " + h.replies);
        }
    }

    private static int messageContaining(List<String> messages, String needle) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> monthHeaders(List<String> messages) {
        List<String> headers = new ArrayList<>();
        for (String message : messages) {
            for (String line : message.split("\n")) {
                if (line.matches("^\\p{Lu}\\p{Ll}+ \\d{4}$")) {
                    headers.add(line);
                }
            }
        }
        return headers;
    }
}
