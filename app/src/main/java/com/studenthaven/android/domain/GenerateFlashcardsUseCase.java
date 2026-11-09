package com.studenthaven.android.domain;

import java.util.ArrayList;
import java.util.List;

public final class GenerateFlashcardsUseCase {
    public record Definition(String front, String back) {}

    public List<Definition> execute(String content) {
        List<Definition> definitions = new ArrayList<>();
        for (String part : content.split("[.!?]")) {
            String sentence = part.trim();
            int split = sentence.indexOf(" is ");
            int separatorLength = 4;
            if (split < 0) {
                split = sentence.indexOf(": ");
                separatorLength = 2;
            }
            if (split >= 0) {
                String front = sentence.substring(0, split).trim();
                String back = sentence.substring(split + separatorLength).trim();
                if (!front.isEmpty() && !back.isEmpty()) {
                    definitions.add(new Definition(front, back));
                }
            }
        }
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "No definitions found. Use \"Term is definition.\" or \"Term: definition.\"");
        }
        return definitions;
    }
}
