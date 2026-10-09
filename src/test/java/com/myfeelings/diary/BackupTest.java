package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("the daily database copy")
class BackupTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a copy restores to the same diary: entries, card and settings")
    void restores() throws Exception {
        Path copies = dir.resolve("backups");
        try (DiaryRepository repository = new DiaryRepository(dir.resolve("diary.db"))) {
            repository.save(DiaryDay.today(), "запись для копии");
            repository.setFeeling(DiaryDay.today(), Feeling.WARM);
            repository.settings().saveOwnerName("Миша");

            new Backup(repository, copies).run();
        }

        Path copy = copies.resolve("diary-" + DiaryDay.today() + ".db");
        assertTrue(Files.exists(copy));
        try (DiaryRepository restored = new DiaryRepository(copy)) {
            assertEquals(1, restored.count());
            assertEquals("запись для копии", restored.findLast().orElseThrow().text());
            assertEquals(Feeling.WARM, restored.card(DiaryDay.today()).feeling());
            assertEquals("Миша", restored.settings().ownerName().orElseThrow());
        }
        assertEquals(List.of(), Files.list(copies).filter(f -> f.toString().endsWith(".part")).toList(),
                "no half-written file may be left behind");
    }

    @Test
    @DisplayName("one copy a day is enough for the tick, and the closing copy replaces it")
    void oncePerDay() throws Exception {
        Path copies = dir.resolve("backups");
        try (DiaryRepository repository = new DiaryRepository(dir.resolve("diary.db"))) {
            Backup backup = new Backup(repository, copies);
            repository.save(DiaryDay.today(), "первая");
            backup.runIfDue();
            repository.save(DiaryDay.today(), "вторая");
            backup.runIfDue();

            Path copy = copies.resolve("diary-" + DiaryDay.today() + ".db");
            try (DiaryRepository first = new DiaryRepository(copy)) {
                assertEquals(1, first.count(), "the tick must not copy again the same day");
            }

            backup.run();
            try (DiaryRepository last = new DiaryRepository(copy)) {
                assertEquals(2, last.count(), "the closing copy must hold the day's final state");
            }
            assertEquals(1, backup.copies().size());
        }
    }

    @Test
    @DisplayName("only the newest copies are kept")
    void prunes() throws Exception {
        Path copies = Files.createDirectories(dir.resolve("backups"));
        for (int back = 1; back <= Backup.KEEP + 5; back++) {
            Files.writeString(copies.resolve("diary-" + DiaryDay.today().minusDays(back) + ".db"), "old");
        }
        try (DiaryRepository repository = new DiaryRepository(dir.resolve("diary.db"))) {
            Backup backup = new Backup(repository, copies);
            backup.run();

            List<Path> kept = backup.copies();
            assertEquals(Backup.KEEP, kept.size());
            assertEquals("diary-" + DiaryDay.today() + ".db", kept.get(0).getFileName().toString());
        }
    }

    @Test
    @DisplayName("a path with a quote in it still works")
    void quotedPath() throws Exception {
        Path copies = dir.resolve("owner's backups");
        try (DiaryRepository repository = new DiaryRepository(dir.resolve("diary.db"))) {
            new Backup(repository, copies).run();
            assertEquals(1, new Backup(repository, copies).copies().size());
        }
    }

    @Test
    @DisplayName("a failing backup does not stop the diary")
    void failureIsContained() throws Exception {
        Path blocked = Files.writeString(dir.resolve("not-a-directory"), "x");
        try (DiaryRepository repository = new DiaryRepository(dir.resolve("diary.db"))) {
            new Backup(repository, blocked.resolve("backups")).run();
            repository.save(DiaryDay.today(), "всё ещё пишется");
            assertEquals(1, repository.count());
        }
    }
}
