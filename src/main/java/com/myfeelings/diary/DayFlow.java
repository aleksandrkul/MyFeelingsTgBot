package com.myfeelings.diary;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Closing a day: what is left of it, the feeling, whether there was a conversation, then the card
 * for confirmation.
 *
 * <p>Started by {@code /new} or by the evening reminder — the same conversation either way, so the
 * answer walks the same path whichever asked.
 *
 * <p>Entry text is never logged here.
 */
class DayFlow {

    /** Callback data prefixes of the buttons this conversation puts up. */
    static final String FEELING_CALLBACK = "feel:";
    static final String TALKED_CALLBACK = "talk:";
    static final String CARD_CALLBACK = "card:";

    private final DiaryRepository repository;
    private final Settings settings;
    private final Messages messages;
    private final Telegram telegram;

    /** The day being closed right now, if any. One owner means at most one at a time. */
    private DayClosing closing;

    DayFlow(DiaryRepository repository, Messages messages, Telegram telegram) {
        this.repository = repository;
        this.settings = repository.settings();
        this.messages = messages;
        this.telegram = telegram;
    }

    boolean isActive() {
        return closing != null;
    }

    /**
     * Drops a closing left open on an earlier diary day.
     *
     * <p>Every piece of text was stored as an entry as it arrived, so this loses nothing and needs no
     * apology; what it protects is tomorrow's first thought being its own entry.
     */
    void dropIfStale() {
        if (closing != null && closing.isExpired()) {
            closing = null;
        }
    }

    /** Opens the conversation and asks the evening question. */
    void start(Long chatId) throws SQLException {
        closing = new DayClosing(DiaryDay.today());
        telegram.send(chatId, messages.get("day.ask.text", settings.ownerName().orElse("")));
    }

    /** Only /cancel and /help make sense mid-closing; the rest would lose the thread. */
    void handleCommand(Message message, String command) {
        switch (command) {
            case "/cancel" -> {
                closing = null;
                telegram.reply(message, messages.get("day.cancelled"));
            }
            case "/help" -> telegram.reply(message, messages.get("help") + "\n\n"
                    + messages.get("day.in.progress"));
            default -> telegram.reply(message, messages.get("day.in.progress"));
        }
    }

    /**
     * Any text sent while the day is being closed is stored as an entry of that day first, and only
     * then does the conversation move on. Text sent at a later step joins the day too, and the
     * question is simply asked again instead of the text being taken for a mistyped button.
     */
    void handleText(Message message, String text) throws SQLException {
        repository.save(closing.date(), text);
        if (closing.step() == DayClosing.Step.TEXT) {
            closing.moveTo(DayClosing.Step.FEELING);
            askFeeling(message);
            return;
        }
        switch (closing.step()) {
            case TALKED -> askTalked(message);
            case CONFIRM -> showCard(message);
            default -> askFeeling(message);
        }
    }

    /** Whether a callback belongs to this conversation. */
    static boolean owns(String callbackData) {
        return callbackData.startsWith(FEELING_CALLBACK)
                || callbackData.startsWith(TALKED_CALLBACK)
                || callbackData.startsWith(CARD_CALLBACK);
    }

    /**
     * A tap on one of the buttons. A tap arriving after the closing has expired or been cancelled is
     * answered plainly rather than silently doing nothing to a day that is no longer open.
     */
    void handleCallback(Message origin, String data) throws SQLException {
        if (closing == null || closing.isExpired()) {
            closing = null;
            telegram.reply(origin, messages.get("day.stale"));
            return;
        }

        if (data.startsWith(FEELING_CALLBACK)) {
            Feeling feeling = Feeling.byCode(data.substring(FEELING_CALLBACK.length())).orElse(null);
            if (feeling == null) {
                return;
            }
            repository.setFeeling(closing.date(), feeling);
            closing.moveTo(DayClosing.Step.TALKED);
            askTalked(origin);
        } else if (data.startsWith(TALKED_CALLBACK)) {
            repository.setTalked(closing.date(), data.endsWith("yes"));
            closing.moveTo(DayClosing.Step.CONFIRM);
            showCard(origin);
        } else if (data.endsWith("ok")) {
            LocalDate date = closing.date();
            closing = null;
            repository.confirmCard(date);
            telegram.reply(origin, messages.get("day.confirmed", date));
        } else {
            // Keep writing: the card stays a draft and /new picks it up again later.
            closing = null;
            telegram.reply(origin, messages.get("day.editing"));
        }
    }

    private void askFeeling(Message message) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (Feeling feeling : Feeling.values()) {
            row.add(InlineKeyboardButton.builder()
                    .text(messages.get(feeling.messageKey()))
                    .callbackData(FEELING_CALLBACK + feeling.code())
                    .build());
        }
        telegram.sendWithButtons(message.getChatId(), messages.get("day.ask.feeling"), row);
    }

    private void askTalked(Message message) throws SQLException {
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text(messages.get("button.yes")).callbackData(TALKED_CALLBACK + "yes").build());
        row.add(InlineKeyboardButton.builder()
                .text(messages.get("button.no")).callbackData(TALKED_CALLBACK + "no").build());
        telegram.sendWithButtons(message.getChatId(),
                messages.get("day.ask.talked", settings.personName().orElse("")), row);
    }

    /** Shows the assembled card, with a way to confirm it or to go back to writing. */
    private void showCard(Message message) throws SQLException {
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text(messages.get("button.save")).callbackData(CARD_CALLBACK + "ok").build());
        row.add(InlineKeyboardButton.builder()
                .text(messages.get("button.edit")).callbackData(CARD_CALLBACK + "edit").build());
        telegram.sendWithButtons(message.getChatId(),
                renderCard(closing.date()) + "\n\n" + messages.get("day.card.ask"), row);
    }

    /** The card as the owner sees it: the day's text in the order written, then the two marks. */
    String renderCard(LocalDate date) throws SQLException {
        List<Entry> entries = repository.findBetween(date, date);
        DayCard card = repository.card(date);

        StringBuilder out = new StringBuilder(messages.get("day.card.header", date)).append("\n\n");
        if (entries.isEmpty()) {
            out.append(messages.get("day.card.no.text")).append('\n');
        } else {
            for (Entry entry : entries) {
                out.append(entry.time())
                        .append("  ").append(entry.text()).append('\n');
            }
        }
        card.feelingIfSet().ifPresent(feeling -> out.append('\n')
                .append(messages.get("day.card.feeling", messages.get(feeling.messageKey()))));
        if (card.talked() != null) {
            out.append('\n').append(messages.get("day.card.talked",
                    messages.get(card.talked() ? "talked.yes" : "talked.no")));
        }
        return out.toString().stripTrailing();
    }
}
