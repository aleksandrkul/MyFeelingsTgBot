package com.myfeelings.diary;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

/**
 * The persistent keyboard under the input field: the three things the diary is used for, one tap
 * away. A button sends its label as ordinary text, which the bot's trigger words turn into the
 * command, so there is no second path for the buttons.
 */
final class Menu {

    static ReplyKeyboardMarkup keyboard(Messages messages) {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(
                        messages.get("menu.summary"),
                        messages.get("menu.feed"),
                        messages.get("menu.close")))
                .isPersistent(true)
                .resizeKeyboard(true)
                .inputFieldPlaceholder(messages.get("menu.placeholder"))
                .build();
    }

    private Menu() {
    }
}
