package com.myfeelings.diary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One copy of the database per diary day, the last {@link #KEEP} of them kept.
 *
 * <p>A backup that fails is logged and nothing more: the diary keeps working, and the next tick
 * tries again. Only file names, counts and sizes are logged.
 */
class Backup {

    private static final Logger log = LoggerFactory.getLogger(Backup.class);

    /** How many daily copies are kept. */
    static final int KEEP = 14;

    private static final String PREFIX = "diary-";
    private static final String SUFFIX = ".db";

    private final DiaryRepository repository;
    private final Path directory;

    Backup(DiaryRepository repository, Path directory) {
        this.repository = repository;
        this.directory = directory;
    }

    /** Takes today's copy unless there already is one. Cheap enough to call on every tick. */
    void runIfDue() {
        if (Files.exists(fileFor(DiaryDay.today()))) {
            return;
        }
        run();
    }

    /** Takes today's copy, replacing an earlier one from the same day. Returns whether it succeeded. */
    boolean run() {
        LocalDate day = DiaryDay.today();
        Path target = fileFor(day);
        Path partial = directory.resolve(target.getFileName() + ".part");
        try {
            Files.createDirectories(directory);
            Files.deleteIfExists(partial);
            repository.backupTo(partial);
            // Moved into place only when complete, so a crash never leaves a half-written "backup".
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Backup written: {}, {} bytes", target.getFileName(), Files.size(target));
            prune();
            return true;
        } catch (SQLException | IOException e) {
            log.error("Backup failed", e);
            return false;
        }
    }

    /** The copies on disk, newest first. */
    List<Path> copies() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            // ISO dates sort as text, so the name order is the date order.
            return files.filter(file -> file.getFileName().toString().startsWith(PREFIX)
                            && file.getFileName().toString().endsWith(SUFFIX))
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();
        }
    }

    private void prune() throws IOException {
        List<Path> copies = copies();
        for (Path old : copies.subList(Math.min(KEEP, copies.size()), copies.size())) {
            Files.delete(old);
            log.info("Old backup removed: {}", old.getFileName());
        }
    }

    private Path fileFor(LocalDate day) {
        return directory.resolve(PREFIX + day + SUFFIX);
    }
}
