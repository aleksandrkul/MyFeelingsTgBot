package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("the settings table, read and written as types")
class SettingsTest {

    @TempDir
    Path dir;

    private Path db() {
        return dir.resolve("diary.db");
    }

    @Test
    @DisplayName("nothing is set on a fresh diary, except the reminder's default")
    void defaults() throws Exception {
        try (DiaryRepository repo = new DiaryRepository(db())) {
            Settings settings = repo.settings();
            assertTrue(settings.language().isEmpty());
            assertTrue(settings.ownerName().isEmpty());
            assertTrue(settings.personName().isEmpty());
            assertTrue(settings.remindedOn().isEmpty());
            assertTrue(settings.chatId().isEmpty());
            assertEquals(Optional.of(LocalTime.of(21, 0)), settings.remindAt(),
                    "an unset reminder means 21:00, not off");
        }
    }

    @Test
    @DisplayName("every value round-trips through a restart")
    void roundTrip() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 7);
        try (DiaryRepository repo = new DiaryRepository(db())) {
            Settings settings = repo.settings();
            settings.saveLanguage(Lang.RU);
            settings.saveOwnerName("Миша");
            settings.savePersonName("К.");
            settings.saveRemindAt(LocalTime.of(22, 15));
            settings.saveRemindedOn(day);
            settings.saveChatId(4242L);
        }
        try (DiaryRepository repo = new DiaryRepository(db())) {
            Settings settings = repo.settings();
            assertEquals(Optional.of(Lang.RU), settings.language());
            assertEquals(Optional.of("Миша"), settings.ownerName());
            assertEquals(Optional.of("К."), settings.personName());
            assertEquals(Optional.of(LocalTime.of(22, 15)), settings.remindAt());
            assertEquals(Optional.of(day), settings.remindedOn());
            assertEquals(Optional.of(4242L), settings.chatId());
        }
    }

    @Test
    @DisplayName("a value overwrites rather than piling up")
    void overwrites() throws Exception {
        try (DiaryRepository repo = new DiaryRepository(db())) {
            Settings settings = repo.settings();
            settings.saveLanguage(Lang.RU);
            settings.saveLanguage(Lang.EN);
            assertEquals(Optional.of(Lang.EN), settings.language());
        }
    }

    @Test
    @DisplayName("off is a state of its own, not an unreadable time")
    void switchedOff() throws Exception {
        try (DiaryRepository repo = new DiaryRepository(db())) {
            Settings settings = repo.settings();
            settings.saveRemindOff();
            assertTrue(settings.remindAt().isEmpty(), "off must read as no time at all");
            settings.saveRemindAt(LocalTime.of(7, 5));
            assertEquals(Optional.of(LocalTime.of(7, 5)), settings.remindAt(), "and it can come back on");
        }
    }

    @Test
    @DisplayName("a blank reminded-on day reads as none")
    void blankRemindedOn() throws Exception {
        try (DiaryRepository repo = new DiaryRepository(db())) {
            Settings settings = repo.settings();
            settings.saveRemindedOn(LocalDate.of(2026, 10, 7));
            settings.clearRemindedOn();
            assertTrue(settings.remindedOn().isEmpty());
        }
    }

    @Test
    @DisplayName("an unknown language code is not a language")
    void unknownLanguageCode() throws Exception {
        try (DiaryRepository repo = new DiaryRepository(db())) {
            repo.putSetting("language", "de");
            assertTrue(repo.settings().language().isEmpty(),
                    "a code the bot does not speak must not pass as a language");
        }
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({"21:00, true", "' 07:05 ', true", "00:00, true",
            "off, false", "OFF, false", "25:00, false", "9pm, false", "'', false"})
    @DisplayName("the time format is read strictly")
    void parsing(String value, boolean parses) {
        assertEquals(parses, Settings.parseTime(value).isPresent());
    }
}
