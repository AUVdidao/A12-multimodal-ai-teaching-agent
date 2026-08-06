package com.auvdidao.a12teachingagent.material.chunk;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChunkSplitter {

    private final TextCleaner cleaner;
    private final int maxChars;
    private final int overlapChars;
    private final int minUsefulChars;

    @Autowired
    public ChunkSplitter(TextCleaner cleaner) {
        this(cleaner, 1000, 120, 80);
    }

    public ChunkSplitter(TextCleaner cleaner, int maxChars, int overlapChars, int minUsefulChars) {
        if (maxChars <= 0 || overlapChars < 0 || overlapChars >= maxChars || minUsefulChars <= 0) {
            throw new IllegalArgumentException("Invalid chunking configuration");
        }
        this.cleaner = cleaner;
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
        this.minUsefulChars = minUsefulChars;
    }

    public List<String> split(String text) {
        String cleaned = cleaner.clean(text);
        if (cleaned.isBlank()) {
            return List.of();
        }
        List<String> paragraphs = paragraphs(cleaned);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (paragraph.length() > maxChars) {
                flush(current, result);
                splitOversized(paragraph, result);
                continue;
            }
            if (current.length() == 0) {
                current.append(paragraph);
            } else if (current.length() + 1 + paragraph.length() <= maxChars) {
                current.append('\n').append(paragraph);
            } else {
                flush(current, result);
                current.append(paragraph);
            }
        }
        flush(current, result);
        return List.copyOf(result);
    }

    private List<String> paragraphs(String cleaned) {
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : cleaned.split("\n\\s*\n")) {
            String value = paragraph.strip();
            if (!value.isBlank()) {
                paragraphs.add(value);
            }
        }
        if (paragraphs.isEmpty()) {
            paragraphs.add(cleaned);
        }
        return paragraphs;
    }

    private void splitOversized(String value, List<String> result) {
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(value.length(), start + maxChars);
            String chunk = value.substring(start, end).strip();
            if (chunk.length() >= minUsefulChars || end == value.length()) {
                result.add(chunk);
            }
            if (end == value.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlapChars);
        }
    }

    private void flush(StringBuilder current, List<String> result) {
        if (current.length() >= minUsefulChars) {
            result.add(current.toString().strip());
        } else if (current.length() > 0 && result.isEmpty()) {
            result.add(current.toString().strip());
        }
        current.setLength(0);
    }
}
