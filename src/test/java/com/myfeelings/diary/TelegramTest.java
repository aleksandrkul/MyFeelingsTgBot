package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("splitting a reply into messages Telegram accepts")
class TelegramTest {

    @Test
    @DisplayName("short text stays one message")
    void shortText() {
        assertEquals(List.of("short text"), Telegram.split("short text", 4096));
    }

    @Test
    @DisplayName("blank text produces nothing to send")
    void blank() {
        assertTrue(Telegram.split("   ", 4096).isEmpty());
    }

    @Test
    @DisplayName("a paragraph break is preferred to any other cut")
    void breaksAtParagraph() {
        String text = "a".repeat(30) + "\n\n" + "b".repeat(30);
        List<String> chunks = Telegram.split(text, 40);
        assertEquals(2, chunks.size(), chunks.toString());
        assertEquals("a".repeat(30), chunks.get(0));
        assertEquals("b".repeat(30), chunks.get(1));
    }

    @Test
    @DisplayName("a line break is next, then a space, and no word is cut in half")
    void breaksAtLineThenSpace() {
        List<String> lines = Telegram.split("line\n".repeat(30), 50);
        assertTrue(lines.stream().allMatch(chunk -> chunk.length() <= 50), lines.toString());
        assertTrue(lines.stream().noneMatch(chunk -> chunk.contains("lin\n") || chunk.endsWith("lin")),
                "a word was cut: " + lines);

        List<String> words = Telegram.split("слово ".repeat(40), 50);
        assertTrue(words.stream().noneMatch(chunk -> chunk.endsWith("слов")), "a word was cut: " + words);
    }

    @Test
    @DisplayName("one very long word is cut hard, because there is no other option")
    void oneLongWord() {
        List<String> chunks = Telegram.split("w".repeat(130), 50);
        assertEquals(3, chunks.size(), chunks.toString());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 50));
        assertEquals(130, chunks.stream().mapToInt(String::length).sum(), "characters were lost");
    }

    @Test
    @DisplayName("nothing but whitespace is lost at the seams")
    void lossless() {
        String text = ("Абзац про договорённости и то, как я реагирую на перенос планов. ".repeat(20)
                + "\n\n").repeat(5);
        List<String> chunks = Telegram.split(text, 500);
        assertTrue(chunks.size() > 1, "the text should have been split");
        assertEquals(text.replaceAll("\\s+", ""),
                String.join("", chunks).replaceAll("\\s+", ""));
    }

    @Test
    @DisplayName("every chunk fits the limit, even at the real 4096")
    void realLimit() {
        String text = "Запись дня. ".repeat(2000);
        List<String> chunks = Telegram.split(text, Telegram.MESSAGE_LIMIT);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= Telegram.MESSAGE_LIMIT));
    }
}
