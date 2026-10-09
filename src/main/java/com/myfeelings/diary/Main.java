package com.myfeelings.diary;

import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Entry point.
 *
 * <p>{@code java -jar diary-bot.jar} starts the bot, {@code java -jar diary-bot.jar check} only
 * verifies that Ollama answers and exits, which needs no Telegram token.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("check")) {
                System.exit(checkOllama());
            }
            run();
        } catch (Config.ConfigException e) {
            // A missing variable is a setup mistake, not a bug: report it as one line.
            log.error("Configuration error: {}", e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            log.error("Could not start the bot", e);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        Config config = Config.fromEnv();
        log.info("Starting. {}", config);

        OllamaClient ollama = new OllamaClient(config);
        // Not fatal: the bot has to keep saving entries while Ollama is down.
        log.info("Ollama check: {}", ollama.describeAvailability());

        TelegramClient telegram = new OkHttpTelegramClient(config.telegramBotToken());
        User me = telegram.execute(new GetMe());
        log.info("Connected to Telegram as @{} (id {})", me.getUserName(), me.getId());

        CountDownLatch shutdown = new CountDownLatch(1);

        // The owner picks the reply language on first contact; until then English is only a seed.
        Messages messages = new Messages(Lang.EN);

        try (DiaryRepository repository = new DiaryRepository(config.dbPath());
                DiaryBot bot = new DiaryBot(config, telegram, repository,
                        new SummaryService(repository, ollama, config, messages), messages);
                TelegramBotsLongPollingApplication application = new TelegramBotsLongPollingApplication()) {
            application.registerBot(config.telegramBotToken(), bot);
            bot.startReminders();
            log.info("Long polling started. Serving owner id {} only.", config.allowedUserId());

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down");
                shutdown.countDown();
            }));
            shutdown.await();
        }
        log.info("Stopped");
    }

    /** Round-trips one tiny request through Ollama. Returns the process exit code. */
    private static int checkOllama() {
        Config config = Config.fromEnvForOllamaCheck();
        OllamaClient ollama = new OllamaClient(config);
        log.info("{}", ollama.describeAvailability());
        try {
            String reply = ollama.chat(
                    "You are a test harness. Answer with exactly one word.",
                    "Reply with the word: ready").text();
            log.info("Model replied: {}", reply.strip());
            log.info("Ollama check passed.");
            return 0;
        } catch (OllamaClient.OllamaException e) {
            log.error("Ollama check failed: {}", e.getMessage());
            return 1;
        }
    }

    private Main() {
    }
}
