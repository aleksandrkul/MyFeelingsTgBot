package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The summary against the real local model.
 *
 * <p>Skipped, not failed, when no Ollama with the model is reachable: the build has to pass on a
 * machine that is not running one.
 */
@DisplayName("the summary against the local model")
class SummaryWithModelTest {

    private static final String QUESTION =
            "Что сейчас важно мне — независимо от реакции другого человека?";

    private static final Duration PATIENCE = Duration.ofMinutes(6);

    @TempDir
    Path dir;

    @BeforeAll
    static void requireOllama() {
        assumeTrue(BotHarness.ollamaReady(),
                "no Ollama with qwen2.5 at " + BotHarness.ollamaUrl() + ": skipping the model tests");
    }

    /** Seven days of synthetic cards. No real personal data. */
    private BotHarness withWeek() throws Exception {
        BotHarness h = BotHarness.configured(dir.resolve("diary.db"), Lang.RU, "Миша", "Оля");
        String[][] week = {
            {"6", "Договорились созвониться в среду и обсудить отпуск. Тревожно, когда не знаю, чего ждать.", "anxious", "0"},
            {"5", "Созвон перенесли. Разозлился, но сказал, что всё нормально.", "anxious", "0"},
            {"4", "Поговорили час. Решили, что я говорю прямо, без паузы на три дня.", "warm", "1"},
            {"3", "Сказал прямо про перенесённый созвон. Неловко, но легче.", "calm", "1"},
            {"2", "Снова отложили разговор. На этот раз сказал сразу, что задевает.", "tired", "1"},
            {"1", "Спокойный день. Меньше прокручиваю диалоги в голове.", "calm", "0"},
            {"0", "Обсудили отпуск. Договорённость держится: говорю сразу.", "glad", "1"},
        };
        for (String[] day : week) {
            LocalDate date = DiaryDay.today().minusDays(Integer.parseInt(day[0]));
            h.repository().save(date, day[1]);
            h.repository().setFeeling(date, Feeling.byCode(day[2]).orElseThrow());
            h.repository().setTalked(date, day[3].equals("1"));
            h.repository().confirmCard(date);
        }
        return h;
    }

    @Test
    @DisplayName("итог answers in one paragraph and ends with the diary's question")
    void oneParagraph() throws Exception {
        try (BotHarness h = withWeek()) {
            h.clear();
            h.say("итог");

            String summary = h.awaitReply(reply -> reply.startsWith("Итог за "), PATIENCE);
            assertNotNull(summary, "no summary arrived in " + PATIENCE.toMinutes() + " minutes");

            assertTrue(summary.endsWith(QUESTION), "it must end with the diary's question: " + summary);
            assertFalse(summary.contains(" to 2026-"), "the period must be in Russian: " + summary);

            String body = summary.substring(summary.indexOf("\n\n") + 2).replace(QUESTION, "").strip();
            assertTrue(body.length() < 1200, "a paragraph, not a wall: " + body.length() + " chars");
            assertEquals(1, body.split("\n\\s*\n").length, "one block of text, not four parts: " + body);
            assertFalse(body.contains("**"), "Markdown leaked through: " + body);
            assertFalse(body.contains("##"), "Markdown leaked through: " + body);
            assertFalse(body.contains("Договорились созвониться в среду"), "the entries were pasted back");

            long cyrillic = body.chars().filter(c -> c >= 0x400 && c <= 0x4FF).count();
            assertTrue(cyrillic > body.length() / 3, "it must answer in Russian: " + body);

            for (String impersonal : new String[]{"другого человека", "другой человек", "партнёр", "партнер"}) {
                assertFalse(body.contains(impersonal),
                        "the person must not become a label (" + impersonal + "): " + body);
            }
        }
    }

    @Test
    @DisplayName("one summary at a time, and the bot keeps working while it runs")
    void oneAtATime() throws Exception {
        try (BotHarness h = withWeek()) {
            h.clear();
            long before = System.currentTimeMillis();
            h.say("/summary 7");
            assertTrue(System.currentTimeMillis() - before < 1000,
                    "the polling thread must not wait for the model");

            h.say("/summary 7");
            assertTrue(h.lastReply().contains("Уже считаю сводку"),
                    "a second request must be refused, not queued: " + h.lastReply());

            int entriesBefore = h.repository().count();
            h.say("запись, написанная пока считается сводка");
            assertTrue(h.lastReply().contains("Записал за"),
                    "saving must work while the model runs: " + h.lastReply());
            assertEquals(entriesBefore + 1, h.repository().count());

            assertNotNull(h.awaitReply(reply -> reply.startsWith("Итог за "), PATIENCE),
                    "the summary never arrived");
            assertTrue(h.typingActions.get() >= 1, "the typing indicator must start at once");

            h.clear();
            h.say("/summary 3");
            assertNotNull(h.awaitReply(reply -> reply.startsWith("Итог за "), PATIENCE),
                    "the slot was not released");
        }
    }

    @Test
    @DisplayName("with Ollama unreachable the answer is clear and the diary keeps working")
    void ollamaDown() throws Exception {
        Path db = dir.resolve("down.db");
        Config dead = new Config("", BotHarness.OWNER, "http://localhost:1", "qwen2.5:7b", 16384, db);
        try (DiaryRepository repository = new DiaryRepository(db)) {
            repository.settings().saveLanguage(Lang.RU);
            repository.settings().saveOwnerName("Миша");
            repository.settings().savePersonName("Оля");
            Messages messages = new Messages(Lang.RU);
            SummaryService service =
                    new SummaryService(repository, new OllamaClient(dead), dead, messages);

            try (BotHarness h = BotHarness.configured(db, Lang.RU, "Миша", "Оля")) {
                // The harness' own bot is fine; this one points at a dead port.
                try (DiaryBot bot = new DiaryBot(dead, h.telegram, repository, service, messages)) {
                    repository.save(DiaryDay.today(), "запись для проверки недоступной модели");
                    h.clear();

                    var field = BotHarness.class.getDeclaredField("bot");
                    field.setAccessible(true);
                    field.set(h, bot);

                    h.say("итог");
                    String failure = h.awaitReply(
                            reply -> reply.contains("Не получилось построить сводку"), PATIENCE);
                    assertNotNull(failure, "no clear failure arrived: " + h.replies);
                    assertTrue(failure.contains("Записи целы"), failure);

                    h.say("ещё запись после сбоя");
                    assertTrue(h.lastReply().contains("Записал за"),
                            "the diary must keep working: " + h.lastReply());
                }
            }
        }
    }
}
