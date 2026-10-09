package com.myfeelings.diary;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
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
     * Sends text, split into chunks Telegram accepts. Returns the last message sent, or null when
     * nothing went through.
     *
     * <p>No parse mode is set on purpose: model output contains stray {@code *} and {@code _}, and
     * Telegram rejects the whole request when they do not form valid markup.
     */
    Message send(Long chatId, String text) {
        Message last = null;
        for (String chunk : split(text, MESSAGE_LIMIT)) {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(chunk)
                    .build();
            last = attempt(message, "a reply to chat " + chatId);
            if (last == null) {
                return null;
            }
        }
        return last;
    }

    /** One message carrying a single row of inline buttons. */
    Message sendWithButtons(Long chatId, String text, InlineKeyboardRow row) {
        return sendWithButtons(chatId, text, List.of(row));
    }

    /**
     * Text with inline buttons, laid out in the given rows. Text longer than Telegram accepts is
     * split like any other reply and the buttons go under its last part. Returns that last message,
     * or null when it was not sent.
     */
    Message sendWithButtons(Long chatId, String text, List<InlineKeyboardRow> rows) {
        List<String> chunks = split(text, MESSAGE_LIMIT);
        if (chunks.isEmpty()) {
            return null;
        }
        for (String chunk : chunks.subList(0, chunks.size() - 1)) {
            if (attempt(SendMessage.builder().chatId(chatId).text(chunk).build(),
                    "a reply to chat " + chatId) == null) {
                return null;
            }
        }
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(chunks.get(chunks.size() - 1))
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                .build();
        return attempt(message, "buttons for chat " + chatId);
    }

    /**
     * Replaces the text of a message already sent. An empty list of rows removes its buttons: a
     * request without a keyboard is how Telegram clears one.
     *
     * <p>Returns false when the message could not be changed, so the caller can send a new one.
     */
    boolean edit(Long chatId, Integer messageId, String text, List<InlineKeyboardRow> rows) {
        EditMessageText.EditMessageTextBuilder<?, ?> edit = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text);
        if (!rows.isEmpty()) {
            edit.replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build());
        }
        try {
            client.execute(edit.build());
            return true;
        } catch (TelegramApiException e) {
            // Asking for the text a message already has is not a failure: nothing needed changing.
            if (e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                return true;
            }
            log.error("Could not edit message {} in chat {}", messageId, chatId, e);
            return false;
        }
    }

    /** One message that also puts (or replaces) the persistent keyboard under the input field. */
    Message sendWithKeyboard(Long chatId, String text, ReplyKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(keyboard)
                .build();
        return attempt(message, "a keyboard for chat " + chatId);
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
     * Sends one request and returns what Telegram answered, or null when it did not go through.
     *
     * <p>A failure is logged rather than thrown: a handler that has already decided what to say
     * cannot do anything useful about the network, and the next update must still be served.
     */
    private <T extends Serializable> T attempt(BotApiMethod<T> request, String what) {
        try {
            return client.execute(request);
        } catch (TelegramApiException e) {
            log.error("Could not send {}", what, e);
            return null;
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
