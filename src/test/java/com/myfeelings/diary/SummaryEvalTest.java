package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manual evaluation run: builds a summary of the whole evaluation set with the real model and
 * prints the answer, its length, the wait, and the mechanical checks. It judges nothing by itself;
 * the point is to run it before and after a prompt change and compare what is printed.
 *
 * <p>Off by default. Run it with {@code ./mvnw test -Dtest=SummaryEvalTest -Deval=true}; the Ollama
 * address comes from {@code OLLAMA_URL}, as in the other model tests.
 */
@EnabledIfSystemProperty(named = "eval", matches = "true")
@DisplayName("evaluation run against the local model")
class SummaryEvalTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("prints the summary of the evaluation set with its checks")
    void run() throws Exception {
        assumeTrue(BotHarness.ollamaReady(), "no Ollama at " + BotHarness.ollamaUrl());

        Path db = dir.resolve("eval.db");
        Config config = new Config("", 1L, BotHarness.ollamaUrl(), "qwen2.5:7b", 16384, db);
        try (DiaryRepository repository = new DiaryRepository(db)) {
            repository.settings().saveLanguage(Lang.RU);
            repository.settings().saveOwnerName(EvalSet.OWNER);
            repository.settings().savePersonName(EvalSet.PERSON);
            EvalSet.seed(repository);

            Messages messages = new Messages(Lang.RU);
            SummaryService service = new SummaryService(repository, new OllamaClient(config), config, messages);

            long startedAt = System.nanoTime();
            SummaryService.Result result = service.summarize(SummaryService.lastDays(EvalSet.days().size()));
            Duration wait = Duration.ofNanos(System.nanoTime() - startedAt);

            String question = messages.get("summary.question");
            String body = EvalChecks.body("\n\n" + result.text(), question);
            List<String> labels = EvalChecks.impersonalLabels(body);
            List<String> unknown = EvalChecks.unknownNames(body, EvalSet.allText());

            System.out.println();
            System.out.println("==== evaluation: " + config.ollamaModel() + ", " + result.entries()
                    + " entries ====");
            System.out.println(body);
            System.out.println("----");
            System.out.printf("length        %d chars, %d sentences%n", body.length(), sentences(body));
            System.out.printf("wait          %.1f s%n", wait.toMillis() / 1000.0);
            System.out.printf("ends with the question   %s%n", mark(EvalChecks.endsWithQuestion(result.text(), question)));
            System.out.printf("one paragraph            %s%n", mark(EvalChecks.oneParagraph(body)));
            System.out.printf("in Russian               %s%n", mark(EvalChecks.inRussian(body)));
            System.out.printf("no impersonal label      %s %s%n", mark(labels.isEmpty()), labels);
            System.out.printf("no unknown names         %s %s%n", mark(unknown.isEmpty()), unknown);
            System.out.println("==== end ====");

            assertFalse(body.isBlank(), "the model returned nothing");
        }
    }

    private static int sentences(String text) {
        return (int) text.chars().filter(c -> c == '.' || c == '!' || c == '?').count();
    }

    private static String mark(boolean ok) {
        return ok ? "ok" : "FAIL";
    }
}
