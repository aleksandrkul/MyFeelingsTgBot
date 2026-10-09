package com.myfeelings.diary;

import java.util.Optional;

/**
 * The feeling of a day, chosen from a fixed five.
 *
 * <p>The code is what goes into the database. The label is looked up per language, so a card written
 * in Russian still reads correctly after the owner switches the bot to English.
 */
public enum Feeling {

    CALM("calm"),
    ANXIOUS("anxious"),
    WARM("warm"),
    TIRED("tired"),
    GLAD("glad");

    private final String code;

    Feeling(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public String messageKey() {
        return "feeling." + code;
    }

    public static Optional<Feeling> byCode(String code) {
        for (Feeling feeling : values()) {
            if (feeling.code.equals(code)) {
                return Optional.of(feeling);
            }
        }
        return Optional.empty();
    }
}
