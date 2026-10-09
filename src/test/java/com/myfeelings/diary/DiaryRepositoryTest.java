package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiaryRepositoryTest {

    @TempDir
    Path dir;

    private Path db() {
        return dir.resolve("diary.db");
    }

    @Test
    @DisplayName("entries survive a restart, text exactly as written")
    void survivesRestart() throws Exception {
        long kept;
        try (DiaryRepository repo = new DiaryRepository(db())) {
            repo.save(LocalDate.of(2026, 10, 5), "запись про понедельник");
            repo.save(LocalDate.of(2026, 10, 6), "запись про вторник");
            kept = repo.save(LocalDate.of(2026, 10, 7), "запись про среду").id();
            assertEquals(3, repo.count());
        }

        try (DiaryRepository repo = new DiaryRepository(db())) {
            assertEquals(3, repo.count(), "entries did not survive");
            Entry last = repo.findLast().orElseThrow();
            assertEquals(kept, last.id());
            assertEquals("запись про среду", last.text(), "text must be stored verbatim");
            assertEquals("2026-10-07", last.entryDate().toString());

            assertTrue(repo.deleteById(kept));
            assertFalse(repo.deleteById(kept), "a second delete reports nothing to do");
        }

        try (DiaryRepository repo = new DiaryRepository(db())) {
            assertEquals(2, repo.count(), "the delete did not survive");
            assertEquals("запись про вторник", repo.findLast().orElseThrow().text());
        }
    }

    @Test
    @DisplayName("a period is read oldest first, and the earliest date is known")
    void readsPeriods() throws Exception {
        try (DiaryRepository repo = new DiaryRepository(db())) {
            repo.save(LocalDate.of(2026, 10, 1), "первая");
            repo.save(LocalDate.of(2026, 10, 3), "третья");
            repo.save(LocalDate.of(2026, 10, 3), "третья, второе сообщение");
            repo.save(LocalDate.of(2026, 10, 9), "девятая");

            assertEquals(LocalDate.of(2026, 10, 1), repo.earliestDate().orElseThrow());

            List<Entry> window = repo.findBetween(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3));
            assertEquals(2, window.size());
            assertEquals("третья", window.get(0).text());
            assertEquals("третья, второе сообщение", window.get(1).text());
        }
    }

    @Test
    @DisplayName("a day card keeps the feeling, the mark and the confirmation")
    void dayCards() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 7);
        try (DiaryRepository repo = new DiaryRepository(db())) {
            DayCard fresh = repo.card(day);
            assertFalse(fresh.isConfirmed());
            assertTrue(fresh.feelingIfSet().isEmpty());

            repo.setFeeling(day, Feeling.TIRED);
            repo.setTalked(day, true);
            assertEquals(Feeling.TIRED, repo.card(day).feeling());
            assertEquals(Boolean.TRUE, repo.card(day).talked());
            assertFalse(repo.card(day).isConfirmed(), "setting marks does not confirm");

            repo.confirmCard(day);
            assertTrue(repo.card(day).isConfirmed());
        }
        try (DiaryRepository repo = new DiaryRepository(db())) {
            assertTrue(repo.card(day).isConfirmed(), "the card did not survive a restart");
            assertEquals(Feeling.TIRED, repo.card(day).feeling());
            assertEquals(1, repo.cardsBetween(day.minusDays(3), day).size());
        }
    }

    @Test
    @DisplayName("the schema is created with WAL and the date index")
    void schema() throws Exception {
        try (DiaryRepository repo = new DiaryRepository(db())) {
            assertEquals(0, repo.count());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db());
                Statement statement = connection.createStatement()) {
            ResultSet objects = statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name");
            List<String> names = new java.util.ArrayList<>();
            while (objects.next()) {
                names.add(objects.getString(1));
            }
            assertTrue(names.containsAll(List.of("entries", "day_cards", "settings")), names.toString());
            assertFalse(names.contains("summaries"), "the cache table waits for stage 5: " + names);
            assertTrue(names.contains("idx_entries_date"), "the date index is missing: " + names);

            ResultSet journal = statement.executeQuery("PRAGMA journal_mode");
            journal.next();
            assertEquals("wal", journal.getString(1));
        }
    }
}
