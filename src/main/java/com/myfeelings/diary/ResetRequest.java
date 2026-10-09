package com.myfeelings.diary;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * {@code /reset}: erase the whole diary and start over. The one command that cannot be undone, so
 * it asks with buttons, takes a backup copy first, and refuses to erase if the copy fails.
 *
 * <p>The offer lives in memory only: a restart cancels it.
 */
class ResetRequest {

    static final String CALLBACK = "reset:";

    private static final String WIPE = CALLBACK + "yes";
    private static final Duration WINDOW = Duration.ofMinutes(2);

    private final DiaryRepository repository;
    private final Messages messages;
    private final Telegram telegram;
    private final Backup backup;

    private Instant offerExpiresAt;

    ResetRequest(DiaryRepository repository, Messages messages, Telegram telegram, Backup backup) {
        this.repository = repository;
        this.messages = messages;
        this.telegram = telegram;
        this.backup = backup;
    }

    static boolean owns(String callbackData) {
        return callbackData.startsWith(CALLBACK);
    }

    void offer(Message message) throws SQLException {
        offerExpiresAt = Instant.now().plus(WINDOW);
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text(messages.get("button.reset.yes")).callbackData(WIPE).build());
        row.add(InlineKeyboardButton.builder()
                .text(messages.get("button.reset.no")).callbackData(CALLBACK + "no").build());
        telegram.sendWithButtons(message.getChatId(),
                messages.get("reset.confirm", repository.count()), row);
    }

    /** Returns true when the diary was erased and the caller has to start the setup again. */
    boolean handleCallback(Message offer, String data) throws SQLException {
        boolean pending = offerExpiresAt != null && !Instant.now().isAfter(offerExpiresAt);
        offerExpiresAt = null;

        String outcome;
        boolean wiped = false;
        if (!pending) {
            outcome = messages.get("reset.nothing.pending");
        } else if (!data.equals(WIPE)) {
            outcome = messages.get("reset.kept");
        } else if (!backup.run()) {
            outcome = messages.get("reset.backup.failed");
        } else {
            repository.wipe();
            outcome = messages.get("reset.done");
            wiped = true;
        }
        if (!telegram.edit(offer.getChatId(), offer.getMessageId(), outcome, List.of())) {
            telegram.reply(offer, outcome);
        }
        return wiped;
    }
}
