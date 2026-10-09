package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@DisplayName("what the owner writes never reaches the log")
class LogHygieneTest {

    private static final String ENTRY = "секретнаямысльодин";
    private static final String DATED = "секретнаямыслидва";
    private static final String CLOSING = "секретнаямыслитри";
    private static final String OWNER_NAME = "Секретнаяимявладельца";
    private static final String PERSON_NAME = "Секретнаяимядругого";

    @TempDir
    Path dir;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger root;
    private Level previousLevel;

    @BeforeEach
    void capture() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        previousLevel = root.getLevel();
        // The most verbose level: a careless debug line is as much a leak as an info one.
        root.setLevel(Level.ALL);
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void release() {
        root.detachAppender(captured);
        root.setLevel(previousLevel);
        captured.stop();
    }

    @Test
    @DisplayName("entries, names and a whole conversation leave only ids, dates and lengths")
    void nothingPersonalIsLogged() throws Exception {
        try (BotHarness h = new BotHarness(dir.resolve("diary.db"))) {
            // The first run: language, then both names.
            h.say("привет");
            h.tap("lang:ru");
            h.say(OWNER_NAME);
            h.say(PERSON_NAME);

            h.say(ENTRY);
            h.say("/date 2026-01-05 " + DATED);
            h.say("/new");
            h.say(CLOSING);
            h.tap("feel:calm");
            h.tap("talk:yes");
            h.tap("card:ok");
            h.say("/last");
            h.say("лента");
            h.say("/undo");
            h.say("/undo yes");
            // The model may be down on this machine; either answer ends the request.
            h.say("/summary");
            h.awaitReply(reply -> reply.contains("ummary") || reply.contains("водк"), Duration.ofSeconds(30));
        }

        assertTrue(captured.list.size() > 5, "the test must actually have captured the bot's logging");
        for (String secret : List.of(ENTRY, DATED, CLOSING, OWNER_NAME, PERSON_NAME)) {
            for (ILoggingEvent event : captured.list) {
                for (String where : visibleParts(event)) {
                    assertFalse(where.contains(secret),
                            "a log event carries personal text: " + event.getLoggerName() + " / "
                                    + event.getLevel());
                }
            }
        }
    }

    /** Everything an appender could print for an event: the message, its arguments, the exceptions. */
    private static List<String> visibleParts(ILoggingEvent event) {
        List<String> parts = new ArrayList<>();
        parts.add(event.getFormattedMessage());
        parts.add(String.valueOf(event.getMessage()));
        if (event.getArgumentArray() != null) {
            for (Object argument : event.getArgumentArray()) {
                parts.add(String.valueOf(argument));
            }
        }
        for (IThrowableProxy throwable = event.getThrowableProxy(); throwable != null;
                throwable = throwable.getCause()) {
            parts.add(String.valueOf(throwable.getMessage()));
        }
        return parts;
    }
}
