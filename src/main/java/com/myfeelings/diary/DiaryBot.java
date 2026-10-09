package com.myfeelings.diary;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Handles incoming updates. Everything from an account other than {@code ALLOWED_USER_ID} is
 * dropped without a reply.
 *
 * <p>Replies are in the language the owner picked on first contact; code, logs and the summary
 * prompt stay in English. The reply language is independent of the language entries are written in.
 *
 * <p>Entry text is never logged: only message ids and lengths.
 */
public class DiaryBot implements LongPollingSingleThreadUpdateConsumer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiaryBot.class);

    /** Callback data prefix of the language buttons; the day's own prefixes live in {@link DayFlow}. */
    private static final String LANGUAGE_CALLBACK = "lang:";

    private final Config config;
    private final Telegram telegram;
    private final DiaryRepository repository;
    private final Settings settings;
    private final SummaryService summaries;
    private final Messages messages;
    private final Feed feed;
    private final Reminder reminder;
    private final Backup backup;
    private final UndoRequest undo;
    private final Onboarding onboarding;
    private final DayFlow day;

    /** False until the owner has picked a language; the first thing the bot does is ask. */
    private boolean languageChosen;

    /**
     * Model work runs here, not on the polling thread: the library hands every update to a single
     * thread, so a summary running there would block saving entries for minutes.
     */
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "model-worker"));

    private final ScheduledExecutorService typingScheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> new Thread(runnable, "typing-indicator"));

    /** Only one model request at a time; a second one is refused rather than queued silently. */
    private final Semaphore modelSlot = new Semaphore(1);

    public DiaryBot(Config config, TelegramClient telegram, DiaryRepository repository,
            SummaryService summaries, Messages messages) throws SQLException {
        this.config = config;
        this.telegram = new Telegram(telegram);
        this.repository = repository;
        this.settings = repository.settings();
        this.summaries = summaries;
        this.messages = messages;
        this.feed = new Feed(repository, messages);
        this.backup = new Backup(repository, config.backupDir());
        this.reminder = new Reminder(repository, this::remind, backup::runIfDue);
        this.undo = new UndoRequest(repository, messages, this.telegram);
        this.onboarding = new Onboarding(repository, messages, this.telegram);
        this.day = new DayFlow(repository, messages, this.telegram);

        Optional<Lang> stored = settings.language();
        stored.ifPresent(messages::use);
        this.languageChosen = stored.isPresent();
        log.info("Reply language: {}, names: {}",
                languageChosen ? messages.lang().code() : "not chosen yet",
                onboarding.isDone() ? "known" : "not asked yet");
    }

    @Override
    public void consume(Update update) {
        try {
            handle(update);
        } catch (SQLException e) {
            log.error("Database error while handling an update", e);
            replyToOwnerOf(update, messages.get("db.unreachable"));
        } catch (RuntimeException e) {
            // One bad update must not take the bot down; the next one is handled normally.
            log.error("Failed to handle an update", e);
        }
    }

    private void handle(Update update) throws SQLException {
        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
            return;
        }
        // An edited message arrives as editedMessage, so editing in Telegram does not change a
        // stored entry. That is deliberate: /undo and /date are the ways to fix an entry.
        if (!update.hasMessage()) {
            return;
        }
        Message message = update.getMessage();
        Long senderId = message.getFrom() == null ? null : message.getFrom().getId();
        if (senderId == null || senderId != config.allowedUserId()) {
            log.info("Ignored a message from user {}", senderId);
            return;
        }

        rememberChat(message.getChatId());

        if (!message.hasText()) {
            telegram.reply(message, messages.get("entry.text.only"));
            return;
        }

        String text = message.getText().strip();
        log.info("Message {} from the owner, {} chars", message.getMessageId(), text.length());

        // Before a language is chosen the bot asks in both. A plain message is still stored first,
        // so the very first thought is never lost to a settings screen.
        if (!languageChosen) {
            if (!text.startsWith("/")) {
                Entry entry = repository.save(DiaryDay.today(), text);
                telegram.reply(message, Messages.bilingual("entry.saved", entry.entryDate()));
            }
            askForLanguage(message);
            return;
        }

        day.dropIfStale();

        // The names are asked once, right after the language. /language is the one command that
        // still makes sense here: the question may be in a language the owner mistapped.
        if (!onboarding.isDone()) {
            if (commandOf(text).equals("/language")) {
                askForLanguage(message);
            } else {
                onboarding.handle(message, text);
            }
            return;
        }

        // A bare word the owner uses as a command — "лента" — is rewritten to its slash form, so it
        // goes through exactly the same path, including being held back while a day is being closed.
        String asCommand = asCommand(text);
        if (asCommand != null) {
            text = asCommand;
        }

        if (text.startsWith("/")) {
            handleCommand(message, text);
        } else if (day.isActive()) {
            day.handleText(text);
        } else {
            saveEntry(message, DiaryDay.today(), text);
        }
    }

    private void handleCommand(Message message, String text) throws SQLException {
        String command = commandOf(text);
        String[] parts = text.split("\\s+", 2);
        String argument = parts.length > 1 ? parts[1].strip() : "";

        if (day.isActive()) {
            day.handleCommand(message, command);
            return;
        }

        switch (command) {
            case "/help", "/start" -> telegram.reply(message, messages.get("help"));
            case "/language" -> askForLanguage(message);
            case "/who" -> onboarding.restart(message);
            case "/remind" -> handleRemind(message, argument);
            case "/new" -> day.start(message.getChatId());
            case "/feed" -> handleFeed(message, argument);
            case "/last" -> handleLast(message);
            case "/undo" -> undo.offer(message);
            case "/date" -> handleDate(message, argument);
            case "/summary" -> handleSummary(message, argument);
            case "/cancel" -> telegram.reply(message, messages.get("day.nothing.to.cancel"));
            default -> telegram.reply(message, messages.get("command.unknown"));
        }
    }

    /** Starts the reminder's own schedule. Separate from the constructor: it starts a thread. */
    public void startReminders() {
        reminder.start();
    }

    /**
     * The evening question, sent by the reminder rather than asked for. It opens the same closing
     * conversation {@code /new} does, so the answer walks the same path.
     */
    private void remind(long chatId) {
        try {
            if (day.isActive()) {
                // Already in the middle of it; asking again would only interrupt.
                return;
            }
            day.start(chatId);
        } catch (SQLException e) {
            log.error("Could not send the evening reminder", e);
        }
    }

    /** Remembers where to send the evening question. Written only when it actually changes. */
    private void rememberChat(Long chatId) throws SQLException {
        if (chatId == null) {
            return;
        }
        if (!settings.chatId().filter(chatId::equals).isPresent()) {
            settings.saveChatId(chatId);
        }
    }

    /** Shows or changes the time of the evening question. */
    private void handleRemind(Message message, String argument) throws SQLException {
        if (argument.isBlank()) {
            telegram.reply(message, settings.remindAt()
                    .map(time -> messages.get("remind.current", time))
                    .orElseGet(() -> messages.get("remind.current.off")));
            return;
        }
        if (argument.equalsIgnoreCase(Settings.OFF)) {
            settings.saveRemindOff();
            telegram.reply(message, messages.get("remind.off"));
            return;
        }
        Optional<LocalTime> parsed = Settings.parseTime(argument);
        if (parsed.isEmpty()) {
            telegram.reply(message, messages.get("remind.usage"));
            return;
        }
        settings.saveRemindAt(parsed.get());
        telegram.reply(message, messages.get("remind.set", parsed.get()));
    }

    private void saveEntry(Message message, LocalDate date, String text) throws SQLException {
        Entry entry = repository.save(date, text);
        telegram.reply(message, messages.get("entry.saved", entry.entryDate()));
    }

    /** The command word of a message, without its arguments and without a group mention. */
    private static String commandOf(String text) {
        return text.split("\\s+", 2)[0].split("@", 2)[0].toLowerCase();
    }

    /**
     * A plain word that stands for a command, in the language the bot is speaking. Only an exact
     * match counts, so "лента вчера" stays a diary entry rather than becoming a command.
     */
    private String asCommand(String text) {
        if (text.equalsIgnoreCase(messages.get("trigger.feed"))) {
            return "/feed";
        }
        if (text.equalsIgnoreCase(messages.get("trigger.summary"))) {
            return "/summary";
        }
        if (text.equalsIgnoreCase(messages.get("trigger.close"))) {
            return "/new";
        }
        return null;
    }

    /** The whole feed, or the last N days of it. */
    private void handleFeed(Message message, String argument) throws SQLException {
        LocalDate today = DiaryDay.today();
        LocalDate from;
        if (argument.isBlank()) {
            from = repository.earliestDate().orElse(today);
        } else {
            Optional<Integer> days = SummaryService.parseDays(argument);
            if (days.isEmpty()) {
                telegram.reply(message, messages.get("feed.usage"));
                return;
            }
            from = today.minusDays(days.get() - 1L);
        }

        List<String> parts = feed.render(from, today);
        if (parts.isEmpty()) {
            telegram.reply(message, messages.get("feed.empty"));
            return;
        }
        log.info("Feed from {} to {}: {} messages", from, today, parts.size());
        for (String part : parts) {
            telegram.send(message.getChatId(), part);
        }
    }

    private void handleLast(Message message) throws SQLException {
        Optional<Entry> last = repository.findLast();
        if (last.isEmpty()) {
            telegram.reply(message, messages.get("diary.empty"));
            return;
        }
        telegram.reply(message, last.get().shown(messages));
    }

    private void handleDate(Message message, String argument) throws SQLException {
        String[] parts = argument.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            telegram.reply(message, messages.get("date.usage"));
            return;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(parts[0]);
        } catch (DateTimeParseException e) {
            telegram.reply(message, messages.get("date.unparsable", parts[0]));
            return;
        }
        if (date.isAfter(DiaryDay.today())) {
            telegram.reply(message, messages.get("date.future"));
            return;
        }
        saveEntry(message, date, parts[1].strip());
    }

    /**
     * Starts a summary on the model thread and returns at once, so the bot keeps answering while it
     * runs. A second request during that time is refused: waiting minutes in silence is worse than
     * being told to wait.
     */
    private void handleSummary(Message message, String argument) {
        boolean all = argument.equalsIgnoreCase("all");
        Optional<Integer> days = all ? Optional.empty() : SummaryService.parseDays(argument);
        if (!all && days.isEmpty()) {
            telegram.reply(message, messages.get("summary.usage"));
            return;
        }

        if (!modelSlot.tryAcquire()) {
            telegram.reply(message, messages.get("summary.busy"));
            return;
        }

        modelExecutor.execute(() -> {
            try (TypingIndicator ignored = TypingIndicator.start(telegram, message.getChatId(), typingScheduler)) {
                SummaryService.Period period = all ? summaries.everything() : SummaryService.lastDays(days.get());
                SummaryService.Result result = summaries.summarize(period);
                telegram.reply(message, messages.get("summary.header", summaries.format(result.period()),
                        messages.plural("entry", result.entries()), result.text()));
            } catch (SummaryService.SummaryException e) {
                telegram.reply(message, e.getMessage());
            } catch (SQLException e) {
                log.error("Database error while building a summary", e);
                telegram.reply(message, messages.get("summary.db.failed"));
            } catch (RuntimeException e) {
                log.error("Unexpected failure while building a summary", e);
                telegram.reply(message, messages.get("summary.broke"));
            } finally {
                modelSlot.release();
            }
        });
    }

    /**
     * Offers the two languages as buttons, labelled with a flag and the language's own name.
     *
     * <p>Before a language is chosen the question itself is shown in both; afterwards one is enough,
     * since the flags on the buttons say the rest.
     */
    private void askForLanguage(Message message) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (Lang lang : Lang.values()) {
            row.add(InlineKeyboardButton.builder()
                    .text(lang.buttonLabel())
                    .callbackData(LANGUAGE_CALLBACK + lang.code())
                    .build());
        }
        telegram.sendWithButtons(message.getChatId(),
                languageChosen ? messages.get("language.pick") : Messages.bilingual("language.pick"), row);
    }

    /** Handles a tap on a language button. */
    private void handleCallback(CallbackQuery query) throws SQLException {
        Long senderId = query.getFrom() == null ? null : query.getFrom().getId();
        if (senderId == null || senderId != config.allowedUserId()) {
            log.info("Ignored a callback from user {}", senderId);
            return;
        }
        String data = query.getData() == null ? "" : query.getData();
        Message origin = query.getMessage() instanceof Message m ? m : null;
        if (data.startsWith(LANGUAGE_CALLBACK)) {
            Lang chosen = Lang.byCode(data.substring(LANGUAGE_CALLBACK.length())).orElse(null);
            if (chosen != null) {
                settings.saveLanguage(chosen);
                messages.use(chosen);
                languageChosen = true;
                Long chatId = query.getMessage().getChatId();
                if (onboarding.isDone()) {
                    // The labels are localized, so a new language means a new keyboard.
                    telegram.sendWithKeyboard(chatId, messages.get("language.set"), Menu.keyboard(messages));
                } else {
                    telegram.send(chatId, messages.get("language.set"));
                    onboarding.ask(chatId);
                }
            }
        } else if (origin != null && DayFlow.owns(data)) {
            day.handleCallback(origin, data);
        } else if (origin != null && UndoRequest.owns(data)) {
            undo.handleCallback(origin, data);
        }
        telegram.answerCallback(query.getId());
    }

    /** Best-effort error reply; used when handling already failed, so a second failure is ignored. */
    private void replyToOwnerOf(Update update, String text) {
        if (update.hasMessage() && update.getMessage().getFrom() != null
                && update.getMessage().getFrom().getId() == config.allowedUserId()) {
            telegram.reply(update.getMessage(), text);
        }
    }

    /** Stops accepting model work and waits briefly for a summary in flight. */
    @Override
    public void close() throws InterruptedException {
        reminder.close();
        modelExecutor.shutdown();
        if (!modelExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("A summary was still running at shutdown; dropping it");
            modelExecutor.shutdownNow();
        }
        typingScheduler.shutdownNow();
        // A last copy on the way out, so a stop never loses the day's final entries to the backup.
        backup.run();
    }

}
