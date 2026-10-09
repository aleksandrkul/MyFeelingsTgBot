package com.myfeelings.diary;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the model input from stored entries and turns the reply into a summary.
 *
 * <p>Entry text is never logged: only entry counts and estimated token counts.
 */
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private static final String PROMPT_RESOURCE = "/summary-prompt.txt";

    /**
     * Characters per token for Russian under the qwen2.5 tokenizer. English is about 4, so an
     * estimate calibrated on English would underestimate the input by nearly half.
     */
    private static final double CHARS_PER_TOKEN = 2.2;

    /** num_ctx covers input plus generation, so the reply needs its own share of the window. */
    private static final int RESERVED_REPLY_TOKENS = 1500;

    private final DiaryRepository repository;
    private final OllamaClient ollama;
    private final Config config;
    private final Messages messages;
    private final String systemPrompt;

    public SummaryService(DiaryRepository repository, OllamaClient ollama, Config config, Messages messages) {
        this.repository = repository;
        this.ollama = ollama;
        this.config = config;
        this.messages = messages;
        this.systemPrompt = loadSystemPrompt();
        log.info("Summary prompt loaded, {} chars", systemPrompt.length());
    }

    /**
     * A date range to summarize.
     *
     * <p>{@code toString} is the ISO form that goes to the log. What the owner sees comes from
     * {@link #format(Period)}, which follows their language.
     */
    public record Period(LocalDate from, LocalDate to) {
        @Override
        public String toString() {
            return from.equals(to) ? from.toString() : from + " to " + to;
        }
    }

    /** The period as the owner reads it: "7 октября" or "1 октября — 7 октября". */
    public String format(Period period) {
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMMM", messages.locale());
        String to = period.to().format(day);
        return period.from().equals(period.to()) ? to : period.from().format(day) + " — " + to;
    }

    /** The finished summary, with the facts needed to label it in the reply. */
    public record Result(Period period, int entries, String text) {
    }

    /** The last {@code days} diary days, today included. */
    public static Period lastDays(int days) {
        LocalDate today = DiaryDay.today();
        return new Period(today.minusDays(days - 1L), today);
    }

    /** Everything on record. */
    public Period everything() throws SQLException, SummaryException {
        LocalDate earliest = repository.earliestDate()
                .orElseThrow(() -> new SummaryException(messages.get("diary.empty")));
        return new Period(earliest, DiaryDay.today());
    }

    /**
     * Summarizes one period in a single model call.
     *
     * <p>Periods that do not fit the context window are refused rather than silently truncated;
     * weekly chunking is stage 5.
     */
    public Result summarize(Period period) throws SQLException, SummaryException {
        List<Entry> entries = repository.findBetween(period.from(), period.to());
        if (entries.isEmpty()) {
            throw new SummaryException(messages.get("summary.no.entries", format(period)));
        }

        Map<LocalDate, DayCard> cards = repository.cardsBetween(period.from(), period.to());
        String userPrompt = buildUserPrompt(period, entries, cards,
                repository.settings().personName().orElse(""));
        int estimated = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
        int budget = config.ollamaNumCtx() - RESERVED_REPLY_TOKENS;
        log.info("Summarizing {}: {} entries, ~{} tokens of {} available",
                period, entries.size(), estimated, budget);

        if (estimated > budget) {
            throw new SummaryException(messages.get("summary.too.long",
                    format(period), estimated, budget, config.ollamaNumCtx()));
        }

        try {
            OllamaClient.Reply reply = ollama.chat(systemPrompt, userPrompt);
            String text = reply.text();
            if (text.isBlank()) {
                throw new SummaryException(messages.get("summary.empty.answer"));
            }
            // The diary's one question is appended rather than asked of the model: it has to be this
            // exact question every time, and a 7B model rephrases whatever it is told to end with.
            // A reply cut off by the context limit says so: otherwise it reads as a finished thought.
            String cutOff = reply.truncated()
                    ? "\n\n" + messages.get("summary.truncated", config.ollamaNumCtx()) : "";
            return new Result(period, entries.size(),
                    text.strip() + cutOff + "\n\n" + messages.get("summary.question"));
        } catch (OllamaClient.OllamaException e) {
            // The cause is already phrased for a human; add what it means for the request.
            // The technical detail stays in English: it is the same text that goes to the log.
            throw new SummaryException(messages.get("summary.failed", e.getMessage()));
        }
    }

    /**
     * The diary day by day: the text as written, then that day's feeling and conversation mark.
     *
     * <p>The two marks are the owner's own summary of the day, so they belong in the input rather
     * than only in the feed.
     */
    private String buildUserPrompt(Period period, List<Entry> entries,
            Map<LocalDate, DayCard> cards, String personName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Diary for ").append(period)
                .append(" (").append(entries.size()).append(" entries).\n");

        LocalDate current = null;
        for (Entry entry : entries) {
            if (!entry.entryDate().equals(current)) {
                appendMarks(prompt, cards.get(current));
                current = entry.entryDate();
                prompt.append('\n').append(current).append('\n');
            }
            prompt.append("- ").append(entry.text()).append('\n');
        }
        appendMarks(prompt, cards.get(current));

        // The system prompt already asks for the language of the entries and for no markup, but a 7B
        // model drifts to English and to Markdown headings. Naming both at the very end of the input,
        // right before generation, is what actually holds.
        if (!personName.isBlank()) {
            // Without this the model falls back to "the other person", which reads like a case file.
            prompt.append("\nThe other person is called ").append(personName)
                    .append(". Use that name, do not say \"the other person\".\n");
        }
        prompt.append("\nWrite the summary in ").append(dominantLanguage(entries))
                .append(". One paragraph, four to seven sentences, nothing else. Never copy, quote "
                        + "or re-list the entries: the author wrote them and does not need them "
                        + "back. Plain text only: no Markdown, no asterisks, no headings, no lists. "
                        + "Do not end with a question of your own.\n");
        return prompt.toString();
    }

    /** The feeling and the conversation mark of a day, in English, as the model reads the rest. */
    private void appendMarks(StringBuilder prompt, DayCard card) {
        if (card == null) {
            return;
        }
        card.feelingIfSet().ifPresent(feeling -> prompt.append("  (feeling: ").append(feeling.code()).append(')'));
        if (card.talked() != null) {
            prompt.append("  (conversation with the other person: ")
                    .append(card.talked() ? "yes" : "no").append(')');
        }
        if (card.feeling() != null || card.talked() != null) {
            prompt.append('\n');
        }
    }

    /**
     * Names the language of the entries by script, so the instruction can be explicit.
     *
     * <p>Counting Cyrillic against Latin letters is enough here: the diary is written in one
     * language at a time.
     */
    static String dominantLanguage(List<Entry> entries) {
        int cyrillic = 0;
        int latin = 0;
        for (Entry entry : entries) {
            for (int i = 0; i < entry.text().length(); i++) {
                char c = entry.text().charAt(i);
                if (c >= '\u0400' && c <= '\u04FF') {
                    cyrillic++;
                } else if (Character.isLetter(c) && c < 128) {
                    latin++;
                }
            }
        }
        return cyrillic > latin ? "Russian" : "English";
    }

    static int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    private static String loadSystemPrompt() {
        try (InputStream in = SummaryService.class.getResourceAsStream(PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(PROMPT_RESOURCE + " is missing from the build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + PROMPT_RESOURCE, e);
        }
    }

    /** Carries a message that can be shown to the user as it is. */
    public static class SummaryException extends Exception {
        public SummaryException(String message) {
            super(message);
        }
    }

    /** Parses the argument of {@code /summary}: empty, a day count, or {@code all}. */
    public static Optional<Integer> parseDays(String argument) {
        if (argument.isBlank()) {
            return Optional.of(7);
        }
        try {
            int days = Integer.parseInt(argument);
            return days >= 1 && days <= 3650 ? Optional.of(days) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
