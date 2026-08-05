package com.auvdidao.a12teachingagent.material.chunk;

import org.springframework.stereotype.Component;

@Component
public class TextCleaner {

    public String clean(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text
                .replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", " ");
        StringBuilder result = new StringBuilder();
        int blankLines = 0;
        for (String line : normalized.split("\n", -1)) {
            String cleanedLine = line.replaceAll("[ \\t]+", " ").trim();
            if (cleanedLine.isBlank()) {
                blankLines++;
                if (blankLines <= 1 && result.length() > 0) {
                    result.append('\n');
                }
                continue;
            }
            blankLines = 0;
            if (result.length() > 0 && result.charAt(result.length() - 1) != '\n') {
                result.append('\n');
            }
            result.append(cleanedLine);
        }
        return result.toString().strip();
    }
}
