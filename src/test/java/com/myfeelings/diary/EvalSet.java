package com.myfeelings.diary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Ten synthetic diary days (no real personal data), read from {@code eval/days.json}. The model
 * tests and the manual evaluation run read the same file, so a prompt change is judged against
 * the same input every time.
 *
 * <p>The other person in the fixture is called "Оля"; the owner is "Миша".
 */
final class EvalSet {

    static final String OWNER = "Миша";
    static final String PERSON = "Оля";

    /** One day of the fixture. {@code daysAgo} 0 is today. */
    record Day(int daysAgo, String text, Feeling feeling, boolean talked) {
    }

    static List<Day> days() {
        try (InputStream in = EvalSet.class.getResourceAsStream("/eval/days.json")) {
            if (in == null) {
                throw new IllegalStateException("eval/days.json is missing from the test resources");
            }
            List<Day> days = new ArrayList<>();
            for (JsonNode node : new ObjectMapper().readTree(in)) {
                days.add(new Day(node.get("daysAgo").asInt(), node.get("text").asText(),
                        Feeling.byCode(node.get("feeling").asText()).orElseThrow(),
                        node.get("talked").asBoolean()));
            }
            return days;
        } catch (IOException e) {
            throw new IllegalStateException("could not read eval/days.json", e);
        }
    }

    /** Writes every day as an entry with its confirmed card. */
    static void seed(DiaryRepository repository) throws SQLException {
        for (Day day : days()) {
            LocalDate date = DiaryDay.today().minusDays(day.daysAgo());
            repository.save(date, day.text());
            repository.setFeeling(date, day.feeling());
            repository.setTalked(date, day.talked());
            repository.confirmCard(date);
        }
    }

    /** All the entries' text in one string, for checks that compare an answer against it. */
    static String allText() {
        StringBuilder text = new StringBuilder();
        days().forEach(day -> text.append(day.text()).append('\n'));
        return text.toString();
    }

    private EvalSet() {
    }
}
