package com.myfeelings.diary;

import java.nio.file.Path;
import java.time.ZoneId;

/**
 * All configuration comes from environment variables: nothing is read from files or command-line
 * arguments, and the token never appears in the project tree.
 */
public record Config(
        String telegramBotToken,
        long allowedUserId,
        String ollamaUrl,
        String ollamaModel,
        int ollamaNumCtx,
        Path dbPath) {

    /**
     * Timezone used to decide which diary day an entry belongs to: {@code DIARY_TIMEZONE} (an IANA
     * zone such as {@code Europe/Berlin}), or the system zone when unset.
     */
    public static final ZoneId ZONE = zoneFromEnv();

    /**
     * The diary day starts at this local hour. An entry written between 00:00 and 03:59 belongs to
     * the previous calendar date, because the diary is usually written late at night about the
     * evening that just ended.
     */
    public static final int DIARY_DAY_START_HOUR = 4;

    /** Full configuration for running the bot. */
    public static Config fromEnv() {
        return fromEnv(true);
    }

    /** Ollama-only configuration for the {@code check} command, which needs no Telegram token. */
    public static Config fromEnvForOllamaCheck() {
        return fromEnv(false);
    }

    private static Config fromEnv(boolean requireTelegram) {
        return new Config(
                requireTelegram ? required("TELEGRAM_BOT_TOKEN") : "",
                requireTelegram ? requiredLong("ALLOWED_USER_ID") : 0L,
                optional("OLLAMA_URL", "http://localhost:11434"),
                optional("OLLAMA_MODEL", "qwen2.5:7b"),
                optionalInt("OLLAMA_NUM_CTX", 16384),
                Path.of(optional("DB_PATH", "./data/diary.db")));
    }

    private static ZoneId zoneFromEnv() {
        String value = System.getenv("DIARY_TIMEZONE");
        return value == null || value.isBlank() ? ZoneId.systemDefault() : ZoneId.of(value.trim());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new ConfigException(name + " is not set");
        }
        return value.trim();
    }

    private static long requiredLong(String name) {
        String value = required(name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ConfigException(name + " must be a number, got: " + value);
        }
    }

    private static String optional(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int optionalInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(name + " must be a number, got: " + value);
        }
    }

    /**
     * Where the daily database copies go: {@code BACKUP_DIR} when set (point it at another disk),
     * otherwise a {@code backups} folder next to the database.
     */
    public Path backupDir() {
        String value = System.getenv("BACKUP_DIR");
        return value == null || value.isBlank() ? dbPath.toAbsolutePath().resolveSibling("backups")
                : Path.of(value.trim());
    }

    /** Deliberately leaves out the token: this value is written to the log on startup. */
    @Override
    public String toString() {
        return "Config[allowedUserId=%d, ollamaUrl=%s, ollamaModel=%s, ollamaNumCtx=%d, dbPath=%s]"
                .formatted(allowedUserId, ollamaUrl, ollamaModel, ollamaNumCtx, dbPath);
    }

    /** Thrown when the environment is incomplete; reported as a plain message, not a stack trace. */
    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }
}
