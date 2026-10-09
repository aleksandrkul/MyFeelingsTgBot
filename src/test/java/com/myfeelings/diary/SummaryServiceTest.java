package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("the summary, in the parts that need no model")
class SummaryServiceTest {

    @TempDir
    Path dir;

    private SummaryService service(DiaryRepository repository, int numCtx, Lang lang) {
        Config config = new Config("", 1L, BotHarness.ollamaUrl(), "qwen2.5:7b", numCtx, dir.resolve("x.db"));
        return new SummaryService(repository, new OllamaClient(config), config, new Messages(lang));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({"'', 7", "7, 7", "30, 30", "3650, 3650"})
    @DisplayName("a day count is accepted")
    void acceptedDays(String argument, int expected) {
        assertEquals(Optional.of(expected), SummaryService.parseDays(argument));
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @CsvSource({"0", "-3", "3651", "abc", "tomorrow", "7.5"})
    @DisplayName("anything else is refused")
    void refusedDays(String argument) {
        assertEquals(Optional.empty(), SummaryService.parseDays(argument));
    }

    @Test
    @DisplayName("the last N days include today")
    void lastDays() {
        SummaryService.Period week = SummaryService.lastDays(7);
        assertEquals(DiaryDay.today(), week.to());
        assertEquals(DiaryDay.today().minusDays(6), week.from());
        assertEquals(DiaryDay.today(), SummaryService.lastDays(1).from(), "one day is today alone");
    }

    @Test
    @DisplayName("a period shown to the owner follows their language, the log form stays ISO")
    void periodIsLocalised() throws Exception {
        try (DiaryRepository repository = new DiaryRepository(dir.resolve("diary.db"))) {
            SummaryService.Period period = new SummaryService.Period(
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7));

            assertEquals("1 октября — 7 октября", service(repository, 16384, Lang.RU).format(period));
            assertEquals("1 October — 7 October", service(repository, 16384, Lang.EN).format(period));
            assertEquals("2026-10-01 to 2026-10-07", period.toString(), "toString belongs to the log");

            SummaryService.Period oneDay = new SummaryService.Period(
                    LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 7));
            assertEquals("7 октября", service(repository, 16384, Lang.RU).format(oneDay));
        }
    }

    @Test
    @DisplayName("an empty period is refused before the model is called")
    void noEntries() throws Exception {
        try (DiaryRepository repository = new DiaryRepository(dir.resolve("diary.db"))) {
            SummaryService service = service(repository, 16384, Lang.RU);
            SummaryService.SummaryException refused = assertThrows(SummaryService.SummaryException.class,
                    () -> service.summarize(SummaryService.lastDays(7)));
            assertTrue(refused.getMessage().startsWith("Нет записей за"), refused.getMessage());

            SummaryService.SummaryException empty = assertThrows(SummaryService.SummaryException.class,
                    service::everything);
            assertEquals("Дневник пуст.", empty.getMessage());
        }
    }

    @Test
    @DisplayName("a period too long for the window is refused, not silently truncated")
    void tooLongIsRefused() throws Exception {
        try (DiaryRepository repository = new DiaryRepository(dir.resolve("diary.db"))) {
            for (int back = 0; back < 7; back++) {
                repository.save(DiaryDay.today().minusDays(back),
                        "обсуждали договорённости и реакцию на перенос планов. ".repeat(3));
            }
            SummaryService service = service(repository, 1700, Lang.RU);

            SummaryService.SummaryException refused = assertThrows(SummaryService.SummaryException.class,
                    () -> service.summarize(SummaryService.lastDays(7)));
            assertTrue(refused.getMessage().contains("не влезает в контекст"), refused.getMessage());
            assertTrue(refused.getMessage().contains("OLLAMA_NUM_CTX"),
                    "the message must say what to do: " + refused.getMessage());
        }
    }

    @Test
    @DisplayName("Russian is estimated at about 2.2 characters per token, not at English rates")
    void tokenEstimate() {
        assertEquals(10, SummaryService.estimateTokens("x".repeat(22)));
        assertEquals(455, SummaryService.estimateTokens("я".repeat(1000)),
                "an English-calibrated estimate would halve this");
    }
}
