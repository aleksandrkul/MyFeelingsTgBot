package com.myfeelings.diary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores entries in a single local SQLite file.
 *
 * <p>One connection, all methods synchronized: the bot has one owner, and the only concurrency is a
 * long summary request running while a new entry arrives.
 *
 * <p>Entry text is never logged: only ids, dates and lengths.
 */
public class DiaryRepository implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiaryRepository.class);

    private final Connection connection;
    private final Settings settings;

    public DiaryRepository(Path dbPath) throws SQLException, IOException {
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        this.settings = new Settings(this);
        configure();
        createSchema();
        log.info("Database ready at {}, {} entries stored", dbPath.toAbsolutePath(), count());
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // WAL survives a crash without losing committed entries and keeps reads from blocking writes.
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    /** Mirrors the schema in instruction.md. Safe to run on every startup. */
    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS entries (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      entry_date TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      text TEXT NOT NULL
                    )""");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_entries_date ON entries(entry_date)");
            // No summaries table: the cache belongs to stage 5 (instruction.md has its schema) and is
            // created together with the code that uses it.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS day_cards (
                      entry_date TEXT PRIMARY KEY,
                      feeling TEXT,
                      talked INTEGER,
                      confirmed_at TEXT
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS settings (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )""");
        }
    }

    /** The typed view over the settings table: one instance, so the keys live in one place. */
    public Settings settings() {
        return settings;
    }

    /**
     * A raw stored preference. Deliberately not public: everything outside the storage layer goes
     * through {@link Settings}, so a new key cannot quietly appear in the middle of some handler.
     */
    synchronized Optional<String> setting(String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        }
    }

    synchronized void putSetting(String key, String value) throws SQLException {
        String sql = "INSERT INTO settings (key, value) VALUES (?, ?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
            // The value may be a person's name, so only its length goes to the log.
            log.info("Setting {} updated, {} chars", key, value.length());
        }
    }

    /** Saves one entry and returns it with the id assigned by SQLite. */
    public synchronized Entry save(LocalDate entryDate, String text) throws SQLException {
        Instant createdAt = Instant.now();
        String sql = "INSERT INTO entries (entry_date, created_at, text) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, entryDate.toString());
            statement.setString(2, createdAt.toString());
            statement.setString(3, text);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                log.info("Saved entry {} for {}, {} chars", id, entryDate, text.length());
                return new Entry(id, entryDate, createdAt, text);
            }
        }
    }

    /** The entry added last, by insertion order rather than by diary date. */
    public synchronized Optional<Entry> findLast() throws SQLException {
        String sql = "SELECT id, entry_date, created_at, text FROM entries ORDER BY id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? Optional.of(read(rows)) : Optional.empty();
        }
    }

    /** All entries of a period, oldest first, ready to be grouped by date for a summary. */
    public synchronized List<Entry> findBetween(LocalDate from, LocalDate to) throws SQLException {
        String sql = "SELECT id, entry_date, created_at, text FROM entries "
                + "WHERE entry_date BETWEEN ? AND ? ORDER BY entry_date, id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, from.toString());
            statement.setString(2, to.toString());
            try (ResultSet rows = statement.executeQuery()) {
                List<Entry> entries = new ArrayList<>();
                while (rows.next()) {
                    entries.add(read(rows));
                }
                return entries;
            }
        }
    }

    /** The card of one day, or an empty one when that day has no card row yet. */
    public synchronized DayCard card(LocalDate date) throws SQLException {
        String sql = "SELECT feeling, talked, confirmed_at FROM day_cards WHERE entry_date = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, date.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return DayCard.empty(date);
                }
                String feeling = rows.getString("feeling");
                Object talked = rows.getObject("talked");
                String confirmedAt = rows.getString("confirmed_at");
                return new DayCard(date,
                        feeling == null ? null : Feeling.byCode(feeling).orElse(null),
                        talked == null ? null : rows.getInt("talked") == 1,
                        confirmedAt == null ? null : Instant.parse(confirmedAt));
            }
        }
    }

    /** Cards of a period, keyed by date, so the feed does not query once per day. */
    public synchronized Map<LocalDate, DayCard> cardsBetween(LocalDate from, LocalDate to)
            throws SQLException {
        String sql = "SELECT entry_date, feeling, talked, confirmed_at FROM day_cards "
                + "WHERE entry_date BETWEEN ? AND ?";
        Map<LocalDate, DayCard> cards = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, from.toString());
            statement.setString(2, to.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    LocalDate date = LocalDate.parse(rows.getString("entry_date"));
                    String feeling = rows.getString("feeling");
                    Object talked = rows.getObject("talked");
                    String confirmedAt = rows.getString("confirmed_at");
                    cards.put(date, new DayCard(date,
                            feeling == null ? null : Feeling.byCode(feeling).orElse(null),
                            talked == null ? null : rows.getInt("talked") == 1,
                            confirmedAt == null ? null : Instant.parse(confirmedAt)));
                }
            }
        }
        return cards;
    }

    public synchronized void setFeeling(LocalDate date, Feeling feeling) throws SQLException {
        upsert(date, "feeling", feeling.code());
    }

    public synchronized void setTalked(LocalDate date, boolean talked) throws SQLException {
        upsert(date, "talked", talked ? 1 : 0);
    }

    /** Marks the card as confirmed; the evening reminder leaves a confirmed day alone. */
    public synchronized void confirmCard(LocalDate date) throws SQLException {
        upsert(date, "confirmed_at", Instant.now().toString());
        log.info("Card {} confirmed", date);
    }

    private void upsert(LocalDate date, String column, Object value) throws SQLException {
        String sql = "INSERT INTO day_cards (entry_date, " + column + ") VALUES (?, ?) "
                + "ON CONFLICT(entry_date) DO UPDATE SET " + column + " = excluded." + column;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, date.toString());
            statement.setObject(2, value);
            statement.executeUpdate();
        }
    }

    /** The diary date of the oldest entry, used as the start of {@code /summary all}. */
    public synchronized Optional<LocalDate> earliestDate() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT MIN(entry_date) FROM entries")) {
            String value = rows.next() ? rows.getString(1) : null;
            return value == null ? Optional.empty() : Optional.of(LocalDate.parse(value));
        }
    }

    /** Deletes one entry. Returns false when it is already gone. */
    public synchronized boolean deleteById(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM entries WHERE id = ?")) {
            statement.setLong(1, id);
            boolean deleted = statement.executeUpdate() > 0;
            log.info("Delete entry {}: {}", id, deleted ? "done" : "not found");
            return deleted;
        }
    }

    public synchronized int count() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM entries")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Writes a consistent copy of the whole database to {@code target}, which must not exist.
     *
     * <p>{@code VACUUM INTO} rather than copying the file: the database runs in WAL mode while the
     * bot is up, and a file copy taken mid-write can be corrupt.
     */
    public synchronized void backupTo(Path target) throws SQLException {
        String quoted = target.toAbsolutePath().toString().replace("'", "''");
        try (Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + quoted + "'");
        }
    }

    private static Entry read(ResultSet rows) throws SQLException {
        return new Entry(
                rows.getLong("id"),
                LocalDate.parse(rows.getString("entry_date")),
                Instant.parse(rows.getString("created_at")),
                rows.getString("text"));
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
        log.info("Database closed");
    }
}
