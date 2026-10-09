package com.myfeelings.diary;

import java.util.Locale;
import java.util.Optional;

/** The language the bot talks in. It is the owner's choice and says nothing about entry language. */
public enum Lang {

    EN("en", "🇺🇸 English"),
    RU("ru", "🇷🇺 Русский");

    private final String code;
    private final String buttonLabel;

    Lang(String code, String buttonLabel) {
        this.code = code;
        this.buttonLabel = buttonLabel;
    }

    public String code() {
        return code;
    }

    /** Used for month names in the feed, which Java declines correctly per language. */
    public Locale locale() {
        return Locale.of(code);
    }

    /** Flag plus the language's own name, so the choice reads the same whichever language you know. */
    public String buttonLabel() {
        return buttonLabel;
    }

    public static Optional<Lang> byCode(String code) {
        for (Lang lang : values()) {
            if (lang.code.equals(code)) {
                return Optional.of(lang);
            }
        }
        return Optional.empty();
    }
}
