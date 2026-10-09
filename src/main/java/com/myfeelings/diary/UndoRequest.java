package com.myfeelings.diary;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * The two steps of {@code /undo}: the offer with two buttons, then the tap on one of them.
 *
 * <p>The offer lives in memory only, so a restart cancels it rather than leaving a button that would
 * later delete whatever happens to be last by then. The tap deletes the entry that was offered, by
 * id, never "whatever is last now".
 */
class UndoRequest {

    /** Callback data prefix of the two buttons. */
    static final String CALLBACK = "undo:";

    private static final String DELETE = CALLBACK + "yes";
    private static final String KEEP = CALLBACK + "no";

    /** How long an offer stays valid. */
    private static final Duration WINDOW = Duration.ofMinutes(2);

    private final DiaryRepository repository;
    private final Messages messages;
    private final Telegram telegram;

    private Long offeredId;
    private Instant offerExpiresAt;

    UndoRequest(DiaryRepository repository, Messages messages, Telegram telegram) {
        this.repository = repository;
        this.messages = messages;
        this.telegram = telegram;
    }

    /** Whether a callback belongs to this conversation. */
    static boolean owns(String callbackData) {
        return callbackData.startsWith(CALLBACK);
    }

    /** {@code /undo}: shows the last entry and asks, with buttons, whether to delete it. */
    void offer(Message message) throws SQLException {
        Optional<Entry> last = repository.findLast();
        if (last.isEmpty()) {
            telegram.reply(message, messages.get("undo.diary.empty"));
            return;
        }
        Entry entry = last.get();
        offeredId = entry.id();
        offerExpiresAt = Instant.now().plus(WINDOW);

        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text(messages.get("button.undo.delete")).callbackData(DELETE).build());
        row.add(InlineKeyboardButton.builder()
                .text(messages.get("button.undo.keep")).callbackData(KEEP).build());
        telegram.sendWithButtons(message.getChatId(), messages.get("undo.confirm", entry.shown(messages),
                messages.plural("within-minute", WINDOW.toMinutes())), row);
    }

    /**
     * A tap on one of the buttons. The offer message is edited into the outcome and loses its
     * buttons, so it cannot be tapped twice.
     */
    void handleCallback(Message offer, String data) throws SQLException {
        boolean pending = offeredId != null && !Instant.now().isAfter(offerExpiresAt);
        Long id = offeredId;
        forget();

        String outcome;
        if (!pending) {
            outcome = messages.get("undo.nothing.pending");
        } else if (data.equals(DELETE)) {
            // Deleting by the remembered id, not by "whatever is last now": the diary may have moved on.
            outcome = messages.get(repository.deleteById(id) ? "undo.done" : "undo.gone");
        } else {
            outcome = messages.get("undo.kept");
        }
        if (!telegram.edit(offer.getChatId(), offer.getMessageId(), outcome, List.of())) {
            telegram.reply(offer, outcome);
        }
    }

    private void forget() {
        offeredId = null;
        offerExpiresAt = null;
    }
}
