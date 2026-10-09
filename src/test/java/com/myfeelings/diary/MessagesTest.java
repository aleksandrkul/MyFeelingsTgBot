package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MessagesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "1, 1 день", "2, 2 дня", "4, 4 дня", "5, 5 дней",
            "11, 11 дней", "12, 12 дней", "14, 14 дней",
            "21, 21 день", "22, 22 дня", "25, 25 дней",
            "101, 101 день", "111, 111 дней",
    })
    @DisplayName("Russian picks one of three forms by arithmetic")
    void russianDays(long count, String expected) {
        assertEquals(expected, new Messages(Lang.RU).plural("day", count));
    }

    @Test
    @DisplayName("every noun has its three forms")
    void russianNouns() {
        Messages ru = new Messages(Lang.RU);
        assertEquals("1 запись", ru.plural("entry", 1));
        assertEquals("3 записи", ru.plural("entry", 3));
        assertEquals("70 записей", ru.plural("entry", 70));
    }

    @Test
    @DisplayName("the within-minute forms are genitive, as \"в течение\" requires")
    void genitiveAfterWithin() {
        Messages ru = new Messages(Lang.RU);
        assertEquals("1 минуты", ru.plural("within-minute", 1));
        assertEquals("2 минут", ru.plural("within-minute", 2));
        assertEquals("5 минут", ru.plural("within-minute", 5));
    }

    @Test
    @DisplayName("English needs only singular and plural")
    void english() {
        Messages en = new Messages(Lang.EN);
        assertEquals("1 day", en.plural("day", 1));
        assertEquals("2 days", en.plural("day", 2));
        assertEquals("1 entry", en.plural("entry", 1));
    }

    @Test
    @DisplayName("a bilingual message is two different lines, not one text twice")
    void bilingualIsNotDoubled() {
        String picker = Messages.bilingual("language.pick");
        String[] lines = picker.split("\n");
        assertEquals(2, lines.length, picker);
        assertTrue(lines[0].contains("Выбери язык"), picker);
        assertTrue(lines[1].contains("Choose the language"), picker);
        assertEquals(2, java.util.Set.of(lines[0], lines[1]).size(), "the two lines must differ");
    }

    @Test
    @DisplayName("a key missing from either language is refused, not discovered months later")
    void missingKeyIsRefused() {
        assertThrows(IllegalStateException.class, () -> new Messages(Lang.RU).get("no.such.key"));
    }
}
