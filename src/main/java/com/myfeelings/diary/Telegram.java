package com.myfeelings.diary;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Everything the bot sends, in one place: the splitting, the keyboards, the chat action, and the
 * decision not to let a send failure propagate into the handler that made it.
 *
 * <p>Message text is never logged here, only chat ids.
 */
public class Telegram {

    private static final Logger log = LoggerFactory.getLogger(Telegram.class);

    /** Telegram rejects a message longer than this. */
    static final int MESSAGE_LIMIT = 4096;

    private final TelegramClient client;

    public Telegram(TelegramClient client) {
        this.client = client;
    }

    /** A reply in the chat a message came from. */
    void reply(Message message, String text) {
        send(message.getChatId(), text);
    }

    /**
     * Sends text, split into chunks Telegram accepts.
     *
     * <p>No parse mode is set on purpose: model output contains stray {@code *} and {@code _}, and
     * Telegram rejects the whole request when they do not form valid markup.
     */
    void send(Long chatId, String text) {
        for (String chunk : split(text, MESSAGE_LIMIT)) {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(chunk)
                    .build();
            if (!attempt(message, "a reply to chat " + chatId)) {
                return;
            }
        }
    }

    /** One message carrying a single row of inline buttons. */
    void sendWithButtons(Long chatId, String text, InlineKeyboardRow row) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(row).build())
                .build();
        attempt(message, "buttons for chat " + chatId);
    }

    /** Telegram keeps a spinner on a button until its callback is answered. */
    void answerCallback(String callbackQueryId) {
        attempt(AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build(),
                "a callback answer");
    }

    /** The "typing…" indicator, which Telegram clears after five seconds. */
    void typing(Long chatId) {
        attempt(SendChatAction.builder()
                .chatId(chatId)
                .action(ActionType.TYPING.toString())
                .build(), "a typing action");
    }

    /**
     * Sends one request and reports whether it went through.
     *
     * <p>A failure is logged rather than thrown: a handler that has already decided what to say
     * cannot do anything useful about the network, and the next update must still be served.
     */
    private boolean attempt(
            org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> request,
            String what) {
        try {
            client.execute(request);
            return true;
        } catch (TelegramApiException e) {
            log.error("Could not send {}", what, e);
            return false;
        }
    }

    /**
     * Splits text into chunks of at most {@code limit} characters, breaking at a paragraph, then a
     * line, then a space, so a summary is not cut mid-word.
     */
    static List<String> split(String text, int limit) {
        List<String> chunks = new ArrayList<>();
        String rest = text;
        while (rest.length() > limit) {
            String window = rest.substring(0, limit);
            int cut = window.lastIndexOf("\n\n");
            if (cut < limit / 2) {
                cut = window.lastIndexOf('\n');
            }
            if (cut < limit / 2) {
                cut = window.lastIndexOf(' ');
            }
            if (cut < limit / 2) {
                cut = limit; // one very long word: a hard cut is the only option
            }
            chunks.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).stripLeading();
        }
        if (!rest.isBlank()) {
            chunks.add(rest);
        }
        return chunks;
    }
}
