package com.myfeelings.diary;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * A typed view over the {@code settings} table.
 *
 * <p>The keys are strings in one place only, here. Before this they were spread over the classes
 * that happened to need them first, which made {@code SummaryService} read a key off {@code
 * DiaryBot} — a service reaching into the bot for a name.
 */
public class Settings {

    private static final String LANGUAGE = "language";
    private static final String OWNER_NAME = "owner_name";
    private static final String PERSON_NAME = "person_name";
    private static final String REMIND_AT = "remind_at";
    private static final String REMINDED_ON = "reminded_on";
    private static final String CHAT_ID = "chat_id";
    private static final String MENU_SHOWN = "menu_shown";

    /** The stored value that turns the evening reminder off. */
    static final String OFF = "off";

    /** Used until the owner changes it. */
    static final LocalTime DEFAULT_REMIND_AT = LocalTime.of(21, 0);

    private final DiaryRepository repository;

    Settings(DiaryRepository repository) {
        this.repository = repository;
    }

    /** Whether the persistent keyboard has been put under the input field at least once. */
    public boolean menuShown() throws SQLException {
        return repository.setting(MENU_SHOWN).isPresent();
    }

    public void saveMenuShown() throws SQLException {
        repository.putSetting(MENU_SHOWN, "1");
    }

    /** The reply language, or empty while the owner has not chosen one. */
    public Optional<Lang> language() throws SQLException {
        return repository.setting(LANGUAGE).flatMap(Lang::byCode);
    }

    public void saveLanguage(Lang lang) throws SQLException {
        repository.putSetting(LANGUAGE, lang.code());
    }

    /** How to address the owner, or empty before the setup has asked. */
    public Optional<String> ownerName() throws SQLException {
        return repository.setting(OWNER_NAME);
    }

    public void saveOwnerName(String name) throws SQLException {
        repository.putSetting(OWNER_NAME, name);
    }

    /** Who the diary is about, or empty before the setup has asked. */
    public Optional<String> personName() throws SQLException {
        return repository.setting(PERSON_NAME);
    }

    public void savePersonName(String name) throws SQLException {
        repository.putSetting(PERSON_NAME, name);
    }

    /**
     * When to ask about the day: the stored time, the default when nothing is stored, or empty when
     * the reminder is switched off.
     */
    public Optional<LocalTime> remindAt() throws SQLException {
        return parseTime(repository.setting(REMIND_AT).orElse(DEFAULT_REMIND_AT.toString()));
    }

    public void saveRemindAt(LocalTime at) throws SQLException {
        repository.putSetting(REMIND_AT, at.toString());
    }

    public void saveRemindOff() throws SQLException {
        repository.putSetting(REMIND_AT, OFF);
    }

    /** The diary day the reminder has already dealt with, sent or deliberately skipped. */
    public Optional<LocalDate> remindedOn() throws SQLException {
        return repository.setting(REMINDED_ON)
                .filter(value -> !value.isBlank())
                .map(LocalDate::parse);
    }

    public void saveRemindedOn(LocalDate day) throws SQLException {
        repository.putSetting(REMINDED_ON, day.toString());
    }

    /** Only tests need to undo this; the bot never forgets a day it has handled. */
    void clearRemindedOn() throws SQLException {
        repository.putSetting(REMINDED_ON, "");
    }

    /** Where to send the evening question, learned from the first message the owner sends. */
    public Optional<Long> chatId() throws SQLException {
        return repository.setting(CHAT_ID).map(Long::valueOf);
    }

    public void saveChatId(long chatId) throws SQLException {
        repository.putSetting(CHAT_ID, String.valueOf(chatId));
    }

    /** Parses {@code HH:MM}; empty for {@code off} and for anything unreadable. */
    static Optional<LocalTime> parseTime(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase(OFF)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.parse(value.strip()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
