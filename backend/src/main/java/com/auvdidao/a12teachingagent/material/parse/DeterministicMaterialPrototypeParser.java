package com.auvdidao.a12teachingagent.material.parse;

import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.material.MaterialLabels;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DeterministicMaterialPrototypeParser implements MaterialPrototypeParser {

    private static final int SUMMARY_TEXT_LIMIT = 600;
    private static final int KEYWORD_LIMIT = 6;
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{1,39}");
    private static final Pattern MARKDOWN_PREFIX = Pattern.compile("^[#>*+\\-\\d.\\s]+", Pattern.UNICODE_CHARACTER_CLASS);

    private final MaterialTextExtractor textExtractor;

    public DeterministicMaterialPrototypeParser(FileStorageService fileStorageService) {
        this.textExtractor = new MaterialTextExtractor(fileStorageService);
    }

    @Override
    public ParsedContent parse(
            UploadedMaterial material,
            List<PurposeType> usageTypes,
            RequirementSummary requirementSummary
    ) {
        List<PurposeType> safeUsageTypes = usageTypes == null ? List.of() : usageTypes;
        List<String> stages = MaterialLabels.teachingStages(safeUsageTypes);
        MaterialTextExtractor.Extraction extraction = textExtractor.extract(material);
        String summary = extraction.hasText()
                ? extractedSummary(extraction, requirementSummary)
                : unavailableSummary(extraction, requirementSummary);
        List<String> keywords = extraction.hasText()
                ? extractKeywords(extraction.text(), requirementSummary)
                : List.of();

        return new ParsedContent(summary, keywords, stages);
    }

    private static String extractedSummary(
            MaterialTextExtractor.Extraction extraction,
            RequirementSummary requirementSummary
    ) {
        StringBuilder summary = new StringBuilder("确定性原型摘要（实际提取文本，来源：")
                .append(extraction.sourceLabel())
                .append("）：")
                .append(abbreviate(normalizeWhitespace(extraction.text()), SUMMARY_TEXT_LIMIT));
        if (extraction.truncated()) {
            summary.append("（提取内容已按字符或页数上限截断）");
        }
        summary.append(" 课程上下文（来自已确认教学需求，不是文件正文）：")
                .append(contextTopic(requirementSummary))
                .append('。');
        return summary.toString();
    }

    private static String unavailableSummary(
            MaterialTextExtractor.Extraction extraction,
            RequirementSummary requirementSummary
    ) {
        return "确定性原型解析说明：" + extraction.noTextReason()
                + " 课程上下文（来自已确认教学需求，不是文件正文）："
                + contextTopic(requirementSummary) + "。";
    }

    private static List<String> extractKeywords(String text, RequirementSummary requirementSummary) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        String comparableText = text.toLowerCase(Locale.ROOT);
        if (requirementSummary != null) {
            addIfPresent(keywords, requirementSummary.getSubject(), comparableText);
            addIfPresent(keywords, requirementSummary.getTopic(), comparableText);
            addIfPresent(keywords, requirementSummary.getKeyPoints(), comparableText);
            addIfPresent(keywords, requirementSummary.getDifficultPoints(), comparableText);
        }

        for (String line : text.split("\\R")) {
            String candidate = MARKDOWN_PREFIX.matcher(line.strip()).replaceFirst("").strip();
            int colon = firstColon(candidate);
            if (colon >= 0 && colon + 1 < candidate.length()) {
                candidate = candidate.substring(colon + 1).strip();
            }
            addKeyword(keywords, candidate);
            if (keywords.size() >= KEYWORD_LIMIT) {
                return keywords.stream().limit(KEYWORD_LIMIT).toList();
            }
        }

        List<String> words = lexicalWords(text);
        words.forEach(word -> addKeyword(keywords, word));
        for (int index = 0; index + 1 < words.size() && keywords.size() < KEYWORD_LIMIT; index++) {
            addKeyword(keywords, words.get(index) + " " + words.get(index + 1));
        }
        return keywords.stream().limit(KEYWORD_LIMIT).toList();
    }

    private static List<String> lexicalWords(String text) {
        java.util.ArrayList<String> words = new java.util.ArrayList<>();
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find() && words.size() < 32) {
            words.add(matcher.group());
        }
        return words;
    }

    private static void addIfPresent(LinkedHashSet<String> keywords, String value, String comparableText) {
        if (value == null || value.isBlank()) {
            return;
        }
        String candidate = value.strip();
        if (candidate.length() <= 40 && comparableText.contains(candidate.toLowerCase(Locale.ROOT))) {
            addKeyword(keywords, candidate);
        }
    }

    private static void addKeyword(LinkedHashSet<String> keywords, String value) {
        if (value == null) {
            return;
        }
        String candidate = normalizeWhitespace(value);
        if (candidate.length() >= 2 && candidate.length() <= 40 && containsLetterOrDigit(candidate)) {
            keywords.add(candidate);
        }
    }

    private static boolean containsLetterOrDigit(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isLetterOrDigit(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static int firstColon(String value) {
        int ascii = value.indexOf(':');
        int fullWidth = value.indexOf('：');
        if (ascii < 0) {
            return fullWidth;
        }
        if (fullWidth < 0) {
            return ascii;
        }
        return Math.min(ascii, fullWidth);
    }

    private static String contextTopic(RequirementSummary requirementSummary) {
        if (requirementSummary == null || requirementSummary.getTopic() == null
                || requirementSummary.getTopic().isBlank()) {
            return "当前课题";
        }
        return requirementSummary.getTopic().strip();
    }

    private static String normalizeWhitespace(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character)) {
                pendingSpace = normalized.length() > 0;
            } else {
                if (pendingSpace) {
                    normalized.append(' ');
                    pendingSpace = false;
                }
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    private static String abbreviate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "…";
    }
}
