package com.studenthaven.android.domain;

import java.util.List;
import java.util.OptionalDouble;

public final class CalculateGradeUseCase {
    public record Mark(Double score, double maxScore, double weight) {}

    public OptionalDouble execute(List<Mark> assignments) {
        double weightedPoints = 0;
        double totalWeight = 0;
        for (Mark mark : assignments) {
            validate(mark);
            if (mark.score() != null) {
                weightedPoints += mark.score() / mark.maxScore() * 100 * mark.weight();
                totalWeight += mark.weight();
            }
        }
        return totalWeight == 0 ? OptionalDouble.empty()
                : OptionalDouble.of(weightedPoints / totalWeight);
    }

    public void validate(Mark mark) {
        if (!Double.isFinite(mark.maxScore()) || mark.maxScore() <= 0
                || !Double.isFinite(mark.weight()) || mark.weight() <= 0
                || (mark.score() != null && (!Double.isFinite(mark.score()) || mark.score() < 0))) {
            throw new IllegalArgumentException(
                    "Maximum score and weight must be positive; score must be non-negative.");
        }
    }

    public String letter(double percentage) {
        if (percentage >= 90) return "A";
        if (percentage >= 80) return "B";
        if (percentage >= 70) return "C";
        if (percentage >= 60) return "D";
        return "F";
    }
}
