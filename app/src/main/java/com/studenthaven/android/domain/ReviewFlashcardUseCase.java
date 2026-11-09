package com.studenthaven.android.domain;

import java.time.ZonedDateTime;

public final class ReviewFlashcardUseCase {
    public record Review(int repetitions, double easeFactor, int intervalDays,
                         long nextReviewAt, long lastReviewedAt) {}

    public Review execute(int repetitions, double easeFactor, int intervalDays,
                          int quality, ZonedDateTime now) {
        if (quality < 0 || quality > 5 || repetitions < 0 || intervalDays < 0
                || !Double.isFinite(easeFactor) || easeFactor < 1.3) {
            throw new IllegalArgumentException("Invalid flashcard review values.");
        }
        double updatedEase = Math.max(1.3,
                easeFactor + 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
        int updatedRepetitions = quality >= 3 ? repetitions + 1 : 0;
        int updatedInterval;
        if (updatedRepetitions <= 1) updatedInterval = 1;
        else if (updatedRepetitions == 2) updatedInterval = 6;
        else updatedInterval = (int) (intervalDays * updatedEase);
        return new Review(updatedRepetitions, updatedEase, updatedInterval,
                now.plusDays(updatedInterval).toInstant().toEpochMilli(),
                now.toInstant().toEpochMilli());
    }
}
