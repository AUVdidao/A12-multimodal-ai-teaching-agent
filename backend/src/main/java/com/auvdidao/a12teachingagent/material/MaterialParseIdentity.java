package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.material.parse.MaterialPrototypeParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Server-owned identity for a material parser fact.  The uploaded material id
 * is the stable source-version identity in the current material model; the
 * snapshot checksum is derived only from the parser output.
 */
public final class MaterialParseIdentity {

    private MaterialParseIdentity() {
    }

    public static String newAnalysisRunId() {
        return "material-analysis-" + java.util.UUID.randomUUID();
    }

    public static String snapshotChecksum(MaterialPrototypeParser.ParsedContent parsed) {
        if (parsed == null) {
            return null;
        }
        StringBuilder canonical = new StringBuilder();
        append(canonical, parsed.summary());
        append(canonical, parsed.analysisText());
        append(canonical, parsed.extractedText());
        append(canonical, parsed.pageCount());
        append(canonical, parsed.keywords());
        append(canonical, parsed.teachingStages());
        append(canonical, parsed.sections());
        return sha256(canonical.toString());
    }

    public static void requireComplete(ParseResult result) {
        if (result == null
                || result.getAnalysisRunId() == null || result.getAnalysisRunId().isBlank()
                || result.getSourceVersionId() == null || result.getSourceVersionId() <= 0
                || result.getParserSnapshotChecksum() == null
                || !result.getParserSnapshotChecksum().matches("[0-9a-fA-F]{64}")) {
            throw new ConflictException("PARSE_RESULT_IDENTITY_UNAVAILABLE");
        }
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(StringBuilder target, Object value) {
        if (value instanceof List<?> list) {
            target.append('[').append(list.size()).append(']');
            for (Object item : list) {
                append(target, item);
            }
            return;
        }
        String text = value == null ? "<null>" : value.toString();
        target.append(text.length()).append(':').append(text).append('|');
    }
}
