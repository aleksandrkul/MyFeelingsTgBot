package com.myfeelings.diary;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
/**
 * Keeps the "typing…" indicator alive while the model works.
 *
 * <p>Telegram clears the indicator after five seconds, so a single sendChatAction would cover five
 * seconds of a request that can run for minutes. This resends it until closed.
 */
public class TypingIndicator implements AutoCloseable {

    private static final long INTERVAL_SECONDS = 4;

    private final ScheduledFuture<?> task;

    private TypingIndicator(ScheduledFuture<?> task) {
        this.task = task;
    }

    public static TypingIndicator start(Telegram telegram, Long chatId, ScheduledExecutorService scheduler) {
        return new TypingIndicator(scheduler.scheduleAtFixedRate(
                () -> telegram.typing(chatId), 0, INTERVAL_SECONDS, TimeUnit.SECONDS));
    }

    @Override
    public void close() {
        task.cancel(false);
    }
}
