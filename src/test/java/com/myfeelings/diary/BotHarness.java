package com.myfeelings.diary;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.lang.reflect.Proxy;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Drives a real {@link DiaryBot} against a temporary database, with a recording Telegram client in
 * place of the network. No token, no polling, no Telegram.
 *
 * <p>Everything is per instance: tests must not be able to disturb each other.
 */
class BotHarness implements AutoCloseable {

    static final long OWNER = 42L;
    static final long CHAT = 4242L;
    static final long STRANGER = 999L;

    /**
     * Every message the bot sent, oldest first, as it reads now: a message the bot later edited
     * shows its latest text here, the way it looks in the chat.
     */
    final List<String> replies = Collections.synchronizedList(new ArrayList<>());

    /** The Telegram id of each entry of {@link #replies}, same order. */
    private final List<Integer> replyIds = Collections.synchronizedList(new ArrayList<>());

    /** Every edit the bot made, oldest first, as "messageId: new text". */
    final List<String> edits = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger nextMessageId = new AtomicInteger(100);

    /** Buttons of the most recent message that carried any, as "label=callbackData". */
    final List<String> buttons = Collections.synchronizedList(new ArrayList<>());

    final AtomicInteger callbacksAnswered = new AtomicInteger();
    final AtomicInteger typingActions = new AtomicInteger();

    final Config config;
    final TelegramClient telegram;

    private final Path db;
    private DiaryRepository repository;
    private Messages messages;
    private SummaryService summaries;
    private DiaryBot bot;

    BotHarness(Path db) throws Exception {
        this.db = db;
        this.config = new Config("", OWNER, ollamaUrl(), "qwen2.5:7b", 16384, db);
        this.telegram = recordingClient();
        open();
    }

    /** A harness past the one-time setup: language and both names already chosen. */
    static BotHarness configured(Path db, Lang lang, String owner, String person) throws Exception {
        BotHarness harness = new BotHarness(db);
        harness.repository.settings().saveLanguage(lang);
        harness.repository.settings().saveOwnerName(owner);
        harness.repository.settings().savePersonName(person);
        harness.reopen();
        return harness;
    }

    /** Closes the bot and the database and opens them again: what a restart does to state in memory. */
    void reopen() throws Exception {
        closeParts();
        open();
    }

    private void open() throws Exception {
        this.repository = new DiaryRepository(db);
        Lang stored = repository.settings().language().orElse(Lang.EN);
        this.messages = new Messages(stored);
        this.summaries = new SummaryService(repository, new OllamaClient(config), config, messages);
        this.bot = new DiaryBot(config, telegram, repository, summaries, messages);
    }

    DiaryRepository repository() {
        return repository;
    }

    Messages messages() {
        return messages;
    }

    SummaryService summaries() {
        return summaries;
    }

    DiaryBot bot() {
        return bot;
    }

    /** A text message from the owner. */
    void say(String text) {
        say(OWNER, text);
    }

    void say(long userId, String text) {
        Message message = Message.builder()
                .messageId(1)
                .date((int) (System.currentTimeMillis() / 1000))
                .chat(Chat.builder().id(userId == OWNER ? CHAT : userId).type("private").build())
                .from(User.builder().id(userId).isBot(false).firstName("Test").build())
                .text(text)
                .build();
        Update update = new Update();
        update.setMessage(message);
        bot.consume(update);
    }

    /** A tap on an inline button. */
    void tap(String callbackData) {
        tap(OWNER, callbackData);
    }

    void tap(long userId, String callbackData) {
        CallbackQuery query = new CallbackQuery();
        query.setId("callback");
        query.setData(callbackData);
        query.setFrom(User.builder().id(userId).isBot(false).firstName("Test").build());
        query.setMessage(Message.builder().messageId(2).date(0)
                .chat(Chat.builder().id(userId == OWNER ? CHAT : userId).type("private").build()).build());
        Update update = new Update();
        update.setCallbackQuery(query);
        bot.consume(update);
    }

    String lastReply() {
        synchronized (replies) {
            return replies.isEmpty() ? "(nothing was sent)" : replies.get(replies.size() - 1);
        }
    }

    /** The reply before the last one, for the steps that answer with two messages. */
    String previousReply() {
        synchronized (replies) {
            return replies.size() < 2 ? "(nothing was sent)" : replies.get(replies.size() - 2);
        }
    }

    void clear() {
        replies.clear();
        replyIds.clear();
        edits.clear();
        buttons.clear();
    }

    /** Waits for a reply the model is still working on. Returns it, or null on timeout. */
    String awaitReply(Predicate<String> matching, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (replies) {
                for (int i = replies.size() - 1; i >= 0; i--) {
                    if (matching.test(replies.get(i))) {
                        return replies.get(i);
                    }
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    /** One reminder tick at a chosen moment, as the scheduler would at that time. */
    void fireReminder(java.time.LocalDateTime now) throws Exception {
        var field = DiaryBot.class.getDeclaredField("reminder");
        field.setAccessible(true);
        Object reminder = field.get(bot);
        var tick = reminder.getClass().getDeclaredMethod("tick", java.time.LocalDateTime.class);
        tick.setAccessible(true);
        tick.invoke(reminder, now);
    }

    private TelegramClient recordingClient() {
        return (TelegramClient) Proxy.newProxyInstance(
                BotHarness.class.getClassLoader(), new Class<?>[]{TelegramClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("execute") && args != null && args.length > 0) {
                        return record(args[0]);
                    }
                    return null;
                });
    }

    /** Records one request and answers it the way Telegram would: a sent message comes back with an id. */
    private Object record(Object request) {
        if (request instanceof SendMessage send) {
            int id = nextMessageId.incrementAndGet();
            replies.add(send.getText());
            replyIds.add(id);
            if (send.getReplyMarkup() instanceof InlineKeyboardMarkup keyboard) {
                showButtons(keyboard);
            }
            return Message.builder().messageId(id).date(0)
                    .chat(Chat.builder().id(Long.parseLong(send.getChatId())).type("private").build())
                    .text(send.getText()).build();
        } else if (request instanceof EditMessageText edit) {
            synchronized (replies) {
                int index = replyIds.indexOf(edit.getMessageId());
                if (index < 0) {
                    throw new AssertionError("edit of a message the bot never sent: " + edit.getMessageId());
                }
                replies.set(index, edit.getText());
            }
            edits.add(edit.getMessageId() + ": " + edit.getText());
            if (edit.getReplyMarkup() instanceof InlineKeyboardMarkup keyboard) {
                showButtons(keyboard);
            } else {
                buttons.clear();
            }
        } else if (request instanceof AnswerCallbackQuery) {
            callbacksAnswered.incrementAndGet();
        } else if (request instanceof SendChatAction) {
            typingActions.incrementAndGet();
        }
        return null;
    }

    private void showButtons(InlineKeyboardMarkup keyboard) {
        buttons.clear();
        keyboard.getKeyboard().forEach(row -> row.forEach(button ->
                buttons.add(button.getText() + "=" + button.getCallbackData())));
    }

    private void closeParts() throws Exception {
        if (bot != null) {
            bot.close();
        }
        if (repository != null) {
            repository.close();
        }
    }

    @Override
    public void close() throws Exception {
        closeParts();
    }

    static String ollamaUrl() {
        String fromEnv = System.getenv("OLLAMA_URL");
        return fromEnv == null || fromEnv.isBlank() ? "http://localhost:11434" : fromEnv.trim();
    }

    /**
     * Whether a local Ollama with the model is reachable. Tests that need it are skipped rather than
     * failed when it is not: the build has to pass on a machine that is not running a model.
     */
    static boolean ollamaReady() {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("qwen2.5");
        } catch (Exception e) {
            return false;
        }
    }
}
