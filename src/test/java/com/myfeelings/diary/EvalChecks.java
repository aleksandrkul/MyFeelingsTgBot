package com.myfeelings.diary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simple, mechanical checks run on a summary. Pure functions of the answer, so they are tested
 * without a model; they find the obvious failures, not the subtle ones.
 */
final class EvalChecks {

    private static final List<String> IMPERSONAL =
            List.of("другого человека", "другой человек", "партнёр", "партнер");

    /** A capitalised word that does not open a sentence. */
    private static final Pattern MID_SENTENCE_CAPITAL =
            Pattern.compile("(?<![.!?…\\n]\\s)(?<!^)(?<=[\\p{L}\\d,;:—-]\\s)\\p{Lu}\\p{L}{2,}");

    /** The answer without the diary's own closing question. */
    static String body(String summary, String question) {
        int header = summary.indexOf("\n\n");
        String text = header < 0 ? summary : summary.substring(header + 2);
        return text.replace(question, "").strip();
    }

    static boolean endsWithQuestion(String summary, String question) {
        return summary.endsWith(question);
    }

    static boolean oneParagraph(String body) {
        return !body.isBlank() && body.split("\n\\s*\n").length == 1;
    }

    static boolean inRussian(String body) {
        long cyrillic = body.chars().filter(c -> c >= 0x400 && c <= 0x4FF).count();
        return cyrillic > body.length() / 3;
    }

    /** The impersonal labels found in the answer: the person has a name and should be called by it. */
    static List<String> impersonalLabels(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        return IMPERSONAL.stream().filter(lower::contains).toList();
    }

    /**
     * Names the answer uses that the entries never mention: capitalised words in the middle of a
     * sentence whose stem starts no word in the diary text or the two names. A rough
     * net for invented people and places; it will miss a plausible invention.
     */
    static List<String> unknownNames(String body, String diaryText) {
        List<String> known = List.of((diaryText + " " + EvalSet.OWNER + " " + EvalSet.PERSON)
                .toLowerCase(Locale.ROOT).split("[^\\p{L}]+"));
        List<String> unknown = new ArrayList<>();
        Matcher matcher = MID_SENTENCE_CAPITAL.matcher(body);
        while (matcher.find()) {
            String word = matcher.group();
            // Russian inflects names ("Оля", "Олей"), so compare a stem: the word minus its ending.
            int stemLength = Math.max(2, Math.min(4, word.length() - 2));
            String stem = word.toLowerCase(Locale.ROOT).substring(0, stemLength);
            if (known.stream().noneMatch(token -> token.startsWith(stem)) && !unknown.contains(word)) {
                unknown.add(word);
            }
        }
        return unknown;
    }

    private EvalChecks() {
    }
}
