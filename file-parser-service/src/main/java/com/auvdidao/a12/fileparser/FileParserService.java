package com.auvdidao.a12.fileparser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FileParserService {

    private static final int ANALYSIS_TEXT_LIMIT = 32_000;

    private static final int MAX_PDF_PAGES = 500;
    private static final int MAX_PPTX_SLIDES = 500;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 2_048;
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{1,39}");
    private static final Pattern MARKDOWN_PREFIX = Pattern.compile("^[#>*+\\-\\d.\\s]+");

    static {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_ARCHIVE_ENTRY_BYTES);
        ZipSecureFile.setMaxFileCount(MAX_ARCHIVE_ENTRIES);
        XMLSlideShow.setMaxRecordLength((int) MAX_ARCHIVE_ENTRY_BYTES);
    }

    private final long maxInputBytes;
    private final int maxExtractedCharacters;

    public FileParserService(
            @Value("${parser.max-input-bytes:20971520}") long maxInputBytes,
            @Value("${parser.max-extracted-characters:200000}") int maxExtractedCharacters
    ) {
        this.maxInputBytes = maxInputBytes;
        this.maxExtractedCharacters = maxExtractedCharacters;
    }

    public ParseResponse parse(MultipartFile file, String declaredType, String topic, List<String> usageTypes) {
        byte[] content = readBounded(file);
        String fileType = normalizeFileType(declaredType);
        Extraction extraction = extract(content, fileType);
        String safeTopic = topic == null || topic.isBlank() ? "当前课题" : topic.strip();
        String summary = extraction.hasText()
                ? "确定性原型摘要（实际提取文本，来源：" + extraction.sourceLabel() + "）："
                + abbreviate(normalizeWhitespace(extraction.text()), 600)
                + (extraction.truncated() ? "（提取内容已按字符或页数上限截断）" : "")
                + " 课程上下文（来自已确认教学需求，不是文件正文）：" + safeTopic + "。"
                : "确定性原型解析说明：" + extraction.noTextReason()
                + " 课程上下文（来自已确认教学需求，不是文件正文）：" + safeTopic + "。";
        return new ParseResponse(
                summary,
                extraction.hasText() ? keywords(extraction.text(), safeTopic) : List.of(),
                teachingStages(usageTypes),
                extraction.hasText()
                        ? abbreviate(extraction.text().strip(), ANALYSIS_TEXT_LIMIT)
                        : summary
        );
    }

    private byte[] readBounded(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ParserException("FILE_EMPTY", "Material file is empty.");
        }
        if (file.getSize() > maxInputBytes) {
            throw new ParserException("REQUEST_TOO_LARGE", "Material exceeds the parser request size limit.");
        }
        try {
            byte[] content = file.getBytes();
            if (content.length == 0) {
                throw new ParserException("FILE_EMPTY", "Material file is empty.");
            }
            if (content.length > maxInputBytes) {
                throw new ParserException("REQUEST_TOO_LARGE", "Material exceeds the parser request size limit.");
            }
            return content;
        } catch (IOException exception) {
            throw new ParserException("REQUEST_READ_FAILED", "Material content could not be read.");
        }
    }

    private Extraction extract(byte[] content, String fileType) {
        return switch (fileType) {
            case "TXT" -> Extraction.extracted(decodeUtf8(content), "TXT UTF-8", false);
            case "MD" -> Extraction.extracted(decodeUtf8(content), "Markdown UTF-8", false);
            case "PDF" -> extractPdf(content);
            case "DOCX" -> extractDocx(content);
            case "PPTX" -> extractPptx(content);
            case "PNG", "JPG", "JPEG", "IMAGE" -> Extraction.unavailable("当前未启用 OCR，未提取图片文字，也未生成或推断正文摘要。");
            case "MP4", "VIDEO" -> Extraction.unavailable("当前未启用转写，未提取音视频文字，也未生成或推断正文摘要。");
            case "PPT", "WORD", "XLSX", "OTHER" -> Extraction.unavailable("该文件格式当前不支持正文提取，未生成或推断正文摘要。");
            default -> throw new ParserException("UNSUPPORTED_FILE_TYPE", "Material file type is unsupported.");
        };
    }

    private Extraction extractPdf(byte[] content) {
        if (!startsWithPdfHeader(content)) {
            String compatible = decodeUtf8(content);
            if (compatible.isBlank() || !mostlyReadable(compatible)) {
                throw new ParserException("EXTRACTION_FAILED", "PDF content is damaged or unsupported.");
            }
            return Extraction.extracted(compatible, "PDF 格式不匹配时的 UTF-8 兼容读取", compatible.length() >= maxExtractedCharacters);
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new ParserException("ENCRYPTED_DOCUMENT", "Encrypted PDF files are not supported.");
            }
            LimitedWriter writer = new LimitedWriter(maxExtractedCharacters);
            PDFTextStripper stripper = new PDFTextStripper();
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                stripper.setEndPage(MAX_PDF_PAGES);
                writer.markTruncated();
            }
            try {
                stripper.writeText(document, writer);
            } catch (TextLimitReachedException ignored) {
                // The bounded prefix is intentionally retained.
            }
            String text = sanitize(writer.text());
            return text.isBlank()
                    ? Extraction.unavailable("PDF 未提取到可用文本；如文件为扫描件，当前未启用 OCR，未生成正文摘要。")
                    : Extraction.extracted(text, "PDFBox PDF 文本", writer.truncated());
        } catch (ParserException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ParserException("EXTRACTION_FAILED", "PDF text extraction failed because the file is damaged or unsupported.");
        }
    }

    private Extraction extractDocx(byte[] content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            TextAccumulator accumulator = new TextAccumulator(maxExtractedCharacters);
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                accumulator.append(paragraph.getText());
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        accumulator.append(cell.getText());
                    }
                }
            }
            String text = sanitize(accumulator.text());
            return text.isBlank()
                    ? Extraction.unavailable("DOCX 未提取到段落或表格文本，未生成正文摘要。")
                    : Extraction.extracted(text, "Apache POI XWPF 段落与表格文本", accumulator.truncated());
        } catch (IOException | RuntimeException exception) {
            throw new ParserException("EXTRACTION_FAILED", "DOCX text extraction failed because the file is damaged or unsupported.");
        }
    }

    private Extraction extractPptx(byte[] content) {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(content))) {
            TextAccumulator accumulator = new TextAccumulator(maxExtractedCharacters);
            int limit = Math.min(slideShow.getSlides().size(), MAX_PPTX_SLIDES);
            for (int index = 0; index < limit && !accumulator.full(); index++) {
                for (XSLFShape shape : slideShow.getSlides().get(index).getShapes()) {
                    appendShape(shape, accumulator);
                    if (accumulator.full()) break;
                }
            }
            if (slideShow.getSlides().size() > MAX_PPTX_SLIDES) accumulator.markTruncated();
            String text = sanitize(accumulator.text());
            return text.isBlank()
                    ? Extraction.unavailable("PPTX 未提取到页面 shape 文本，未生成正文摘要。")
                    : Extraction.extracted(text, "Apache POI XSLF 页面 shape 文本", accumulator.truncated());
        } catch (IOException | RuntimeException exception) {
            throw new ParserException("EXTRACTION_FAILED", "PPTX text extraction failed because the file is damaged or unsupported.");
        }
    }

    private static void appendShape(XSLFShape shape, TextAccumulator accumulator) {
        if (shape instanceof XSLFTextShape textShape) {
            accumulator.append(textShape.getText());
        } else if (shape instanceof XSLFTable table) {
            for (XSLFTableRow row : table.getRows()) {
                for (XSLFTableCell cell : row.getCells()) accumulator.append(cell.getText());
            }
        } else if (shape instanceof XSLFGroupShape group) {
            for (XSLFShape nested : group.getShapes()) appendShape(nested, accumulator);
        }
    }

    private List<String> keywords(String text, String topic) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (text.toLowerCase(Locale.ROOT).contains(topic.toLowerCase(Locale.ROOT))) addKeyword(values, topic);
        for (String line : text.split("\\R")) {
            String candidate = MARKDOWN_PREFIX.matcher(line.strip()).replaceFirst("").strip();
            int colon = Math.max(candidate.indexOf(':'), candidate.indexOf('：'));
            if (colon >= 0 && colon + 1 < candidate.length()) candidate = candidate.substring(colon + 1).strip();
            addKeyword(values, candidate);
            if (values.size() >= 6) return values.stream().limit(6).toList();
        }
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find() && values.size() < 6) addKeyword(values, matcher.group());
        return values.stream().limit(6).toList();
    }

    private static List<String> teachingStages(List<String> usageTypes) {
        LinkedHashSet<String> stages = new LinkedHashSet<>();
        for (String type : usageTypes) {
            switch (type) {
                case "TEXTBOOK_BASIS" -> stages.add("概念讲解");
                case "CASE_MATERIAL", "IMAGE_ASSET" -> { stages.add("课堂导入"); stages.add("案例分析"); }
                case "EXERCISE_SOURCE" -> stages.add("课堂练习");
                case "KNOWLEDGE_SUPPLEMENT" -> stages.add("课后拓展");
                default -> { }
            }
        }
        if (stages.isEmpty()) stages.add("概念讲解");
        return List.copyOf(stages);
    }

    private String decodeUtf8(byte[] content) {
        int offset = content.length >= 3 && content[0] == (byte) 0xEF && content[1] == (byte) 0xBB && content[2] == (byte) 0xBF ? 3 : 0;
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, offset, content.length - offset))
                    .toString();
            return truncate(sanitize(decoded));
        } catch (CharacterCodingException exception) {
            throw new ParserException("INVALID_UTF8", "Material text is not valid UTF-8.");
        }
    }

    private String truncate(String value) {
        if (value.length() <= maxExtractedCharacters) return value;
        int end = maxExtractedCharacters;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end).strip();
    }

    private static String normalizeFileType(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_]{1,20}")) {
            throw new ParserException("UNSUPPORTED_FILE_TYPE", "Material file type is missing or unsupported.");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static boolean startsWithPdfHeader(byte[] value) {
        for (int offset = 0; offset <= Math.min(value.length - 5, 1024); offset++) {
            if (value[offset] == '%' && value[offset + 1] == 'P' && value[offset + 2] == 'D' && value[offset + 3] == 'F' && value[offset + 4] == '-') return true;
        }
        return false;
    }

    private static boolean mostlyReadable(String value) {
        int examined = 0;
        int readable = 0;
        for (int index = 0; index < value.length() && examined < 8192; index++) {
            char character = value.charAt(index);
            examined++;
            if (!Character.isISOControl(character) || character == '\n' || character == '\r' || character == '\t') readable++;
        }
        return examined > 0 && readable * 100 >= examined * 90;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace("\uFEFF", "").replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private static String abbreviate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private static void addKeyword(LinkedHashSet<String> values, String candidate) {
        String normalized = normalizeWhitespace(candidate);
        if (normalized.length() >= 2 && normalized.length() <= 40 && normalized.chars().anyMatch(Character::isLetterOrDigit)) values.add(normalized);
    }

    public record ParseResponse(
            String summary,
            List<String> keywords,
            List<String> teachingStages,
            String analysisText
    ) {
    }

    private record Extraction(String text, String sourceLabel, String noTextReason, boolean truncated) {
        static Extraction extracted(String text, String sourceLabel, boolean truncated) { return new Extraction(text, sourceLabel, null, truncated); }
        static Extraction unavailable(String reason) { return new Extraction("", null, reason, false); }
        boolean hasText() { return text != null && !text.isBlank(); }
    }

    private static final class TextAccumulator {
        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;
        private TextAccumulator(int limit) { this.limit = limit; }
        private void append(String value) {
            String cleaned = sanitize(value);
            if (cleaned.isBlank() || full()) return;
            int space = text.isEmpty() ? 0 : 1;
            int remaining = limit - text.length() - space;
            if (remaining <= 0) { truncated = true; return; }
            if (space > 0) text.append('\n');
            if (cleaned.length() <= remaining) text.append(cleaned);
            else { text.append(cleaned, 0, remaining); truncated = true; }
        }
        private boolean full() { return text.length() >= limit; }
        private String text() { return text.toString(); }
        private boolean truncated() { return truncated; }
        private void markTruncated() { truncated = true; }
    }

    private static final class LimitedWriter extends Writer {
        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;
        private LimitedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] buffer, int offset, int length) throws IOException {
            int remaining = limit - text.length();
            if (remaining <= 0) { truncated = true; throw new TextLimitReachedException(); }
            int accepted = Math.min(remaining, length);
            text.append(buffer, offset, accepted);
            if (accepted < length) { truncated = true; throw new TextLimitReachedException(); }
        }
        @Override public void flush() { }
        @Override public void close() { }
        private String text() { return text.toString(); }
        private boolean truncated() { return truncated; }
        private void markTruncated() { truncated = true; }
    }

    private static final class TextLimitReachedException extends IOException { }
}
