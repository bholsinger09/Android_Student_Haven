package com.studenthaven.android.domain;

import static org.junit.Assert.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Test;

public class StudyRulesTest {
    private final ZonedDateTime now = ZonedDateTime.of(
            2026, 3, 7, 12, 0, 0, 0, ZoneId.of("America/Denver"));

    @Test public void generatesBothDefinitionPatternsInSourceOrder() {
        var result = new GenerateFlashcardsUseCase()
                .execute("Mitosis is cell division. Atom: a unit of matter! No definition here?");
        assertEquals(2, result.size());
        assertEquals("Mitosis", result.get(0).front());
        assertEquals("cell division", result.get(0).back());
        assertEquals("Atom", result.get(1).front());
        assertEquals("a unit of matter", result.get(1).back());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsContentWithoutDefinitions() {
        new GenerateFlashcardsUseCase().execute("A plain sentence.");
    }

    @Test public void ignoresEmptyDefinitionSides() {
        var result = new GenerateFlashcardsUseCase().execute("Blank: . Atom: matter.");
        assertEquals(1, result.size());
    }

    @Test public void firstPassingReviewUsesOneCalendarDayAcrossDst() {
        var result = new ReviewFlashcardUseCase().execute(0, 2.5, 0, 5, now);
        assertEquals(1, result.repetitions());
        assertEquals(1, result.intervalDays());
        assertEquals(2.6, result.easeFactor(), 0.00001);
        assertEquals(now.plusDays(1).toInstant().toEpochMilli(), result.nextReviewAt());
        assertEquals(23 * 60 * 60 * 1000L, result.nextReviewAt() - result.lastReviewedAt());
    }

    @Test public void secondPassingReviewUsesSixDays() {
        assertEquals(6, new ReviewFlashcardUseCase()
                .execute(1, 2.5, 1, 4, now).intervalDays());
    }

    @Test public void laterReviewUsesUpdatedEaseAndTruncatesLikeSwift() {
        var result = new ReviewFlashcardUseCase().execute(2, 2.5, 6, 5, now);
        assertEquals(3, result.repetitions());
        assertEquals(15, result.intervalDays());
    }

    @Test public void failedReviewResetsRepetitionsAndClampsEase() {
        var result = new ReviewFlashcardUseCase().execute(8, 1.3, 100, 0, now);
        assertEquals(0, result.repetitions());
        assertEquals(1, result.intervalDays());
        assertEquals(1.3, result.easeFactor(), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidQuality() {
        new ReviewFlashcardUseCase().execute(0, 2.5, 0, 6, now);
    }

    @Test public void gradeNormalizesOnlyGradedWeights() {
        var result = new CalculateGradeUseCase().execute(List.of(
                new CalculateGradeUseCase.Mark(80.0, 100, 1),
                new CalculateGradeUseCase.Mark(45.0, 50, 3),
                new CalculateGradeUseCase.Mark(null, 100, 9)));
        assertEquals(87.5, result.getAsDouble(), 0.00001);
    }

    @Test public void noGradeIsExplicitlyAbsentInsteadOfAFailingGrade() {
        assertFalse(new CalculateGradeUseCase().execute(List.of()).isPresent());
        assertFalse(new CalculateGradeUseCase().execute(List.of(
                new CalculateGradeUseCase.Mark(null, 100, 1))).isPresent());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroMaximumScore() {
        new CalculateGradeUseCase().validate(new CalculateGradeUseCase.Mark(1.0, 0, 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNan() {
        new CalculateGradeUseCase().validate(new CalculateGradeUseCase.Mark(Double.NaN, 100, 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeWeight() {
        new CalculateGradeUseCase().validate(new CalculateGradeUseCase.Mark(1.0, 100, -1));
    }

    @Test public void permitsExtraCreditLikeIos() {
        assertEquals(105, new CalculateGradeUseCase().execute(List.of(
                new CalculateGradeUseCase.Mark(105.0, 100, 1))).getAsDouble(), 0);
    }

    @Test public void letterGradeBoundariesMatchIos() {
        CalculateGradeUseCase calculator = new CalculateGradeUseCase();
        assertEquals("A", calculator.letter(90));
        assertEquals("B", calculator.letter(89.99));
        assertEquals("C", calculator.letter(70));
        assertEquals("D", calculator.letter(60));
        assertEquals("F", calculator.letter(59.99));
    }
}
