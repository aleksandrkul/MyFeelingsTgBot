package com.myfeelings.diary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the evaluation set and the checks run on a summary")
class EvalChecksTest {

    private static final String QUESTION = "Что сейчас важно мне?";

    @Test
    @DisplayName("the fixture holds ten days with a feeling and a mark each, no real names")
    void fixture() {
        List<EvalSet.Day> days = EvalSet.days();
        assertEquals(10, days.size());
        assertEquals(10, days.stream().map(EvalSet.Day::daysAgo).distinct().count(), "one entry per day");
        assertTrue(days.stream().allMatch(day -> !day.text().isBlank()));
    }

    @Test
    @DisplayName("the answer is cut into header, body and the diary's question")
    void bodyIsolated() {
        String summary = "Итог за 7 дней, 7 записей.\n\nСпокойная неделя.\n\n" + QUESTION;
        assertEquals("Спокойная неделя.", EvalChecks.body(summary, QUESTION));
        assertTrue(EvalChecks.endsWithQuestion(summary, QUESTION));
        assertFalse(EvalChecks.endsWithQuestion("Спокойная неделя.", QUESTION));
    }

    @Test
    @DisplayName("one paragraph and the language are told apart from a list and from English")
    void shape() {
        assertTrue(EvalChecks.oneParagraph("Одно. Два. Три."));
        assertFalse(EvalChecks.oneParagraph("Первый.\n\nВторой."));
        assertTrue(EvalChecks.inRussian("Неделя прошла спокойно."));
        assertFalse(EvalChecks.inRussian("The week was calm."));
    }

    @Test
    @DisplayName("impersonal labels are found")
    void labels() {
        assertEquals(List.of("партнёр"), EvalChecks.impersonalLabels("Реакция Партнёр на перенос."));
        assertTrue(EvalChecks.impersonalLabels("Оля перенесла созвон.").isEmpty());
    }

    @Test
    @DisplayName("a name the entries never mention is flagged, the two real names are not")
    void invention() {
        String diary = EvalSet.allText();
        assertTrue(EvalChecks.unknownNames("Миша договорился с Олей про отпуск.", diary).isEmpty());
        assertEquals(List.of("Дмитрием"),
                EvalChecks.unknownNames("Миша поговорил с Дмитрием про отпуск.", diary));
    }
}
