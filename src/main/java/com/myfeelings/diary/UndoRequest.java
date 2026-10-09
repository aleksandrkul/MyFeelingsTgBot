package com.myfeelings.diary;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * The two steps of {@code /undo}: the offer, then the confirmation.
 *
 * <p>The offer lives in memory only, so a restart cancels it rather than leaving a confirmation that
 * would later delete whatever happens to be last by then.
 */
class UndoRequest {

    /** How long an offer stays valid. */
    private static final Duration WINDOW = Duration.ofMinutes(2);

    private static final String CONFIRM = "yes";

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

    /** {@code /undo} offers the last entry; {@code /undo yes} deletes the one that was offered. */
    void handle(Message message, String argument) throws SQLException {
        if (argument.equalsIgnoreCase(CONFIRM)) {
            confirm(message);
        } else {
            offer(message);
        }
    }

    private void offer(Message message) throws SQLException {
        Optional<Entry> last = repository.findLast();
        if (last.isEmpty()) {
            telegram.reply(message, messages.get("undo.diary.empty"));
            return;
        }
        Entry entry = last.get();
        offeredId = entry.id();
        offerExpiresAt = Instant.now().plus(WINDOW);
        telegram.reply(message, messages.get("undo.confirm", entry.shown(messages),
                messages.plural("within-minute", WINDOW.toMinutes())));
    }

    private void confirm(Message message) throws SQLException {
        if (offeredId == null || Instant.now().isAfter(offerExpiresAt)) {
            forget();
            telegram.reply(message, messages.get("undo.nothing.pending"));
            return;
        }
        // Deleting by the remembered id, not by "whatever is last now": the diary may have moved on.
        long id = offeredId;
        forget();
        boolean deleted = repository.deleteById(id);
        telegram.reply(message, messages.get(deleted ? "undo.done" : "undo.gone"));
    }

    private void forget() {
        offeredId = null;
        offerExpiresAt = null;
    }
}
