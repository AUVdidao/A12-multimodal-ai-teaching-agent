package com.auvdidao.a12teachingagent.material.parse;

import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class MaterialTextExtractor {

    static final int MAX_EXTRACTED_CHARACTERS = 200_000;
    static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;

    private static final int MAX_PDF_PAGES = 500;
    private static final int MAX_PPTX_SLIDES = 500;
    private static final int MAX_ARCHIVE_ENTRIES = 2_048;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_ARCHIVE_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_ARCHIVE_TEXT_BYTES = 8L * 1024 * 1024;
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OLE2_HEADER = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };

    static {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_ARCHIVE_ENTRY_BYTES);
        ZipSecureFile.setMaxFileCount(MAX_ARCHIVE_ENTRIES);
        ZipSecureFile.setMaxTextSize(MAX_ARCHIVE_TEXT_BYTES);
        XMLSlideShow.setMaxRecordLength((int) MAX_ARCHIVE_ENTRY_BYTES);
    }

    private final FileStorageService fileStorageService;

    MaterialTextExtractor(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    Extraction extract(UploadedMaterial material) {
        if (material == null || material.getFileType() == null) {
            throw new MaterialParsingException("Material format is missing or unsupported.");
        }

        return switch (material.getFileType()) {
            case TXT -> extractUtf8(material, "TXT UTF-8");
            case MD -> extractUtf8(material, "Markdown UTF-8");
            case PDF -> extractPdf(material);
            case DOCX -> extractDocx(material);
            case PPTX -> extractPptx(material);
            case PNG, JPG, JPEG, IMAGE -> unavailable(
                    material,
                    "当前未启用 OCR，未提取图片文字，也未生成或推断正文摘要。"
            );
            case MP4, VIDEO -> unavailable(
                    material,
                    "当前未启用转写，未提取音视频文字，也未生成或推断正文摘要。"
            );
            case PPT -> unavailable(
                    material,
                    "当前未启用旧版 PPT 正文提取，未生成或推断正文摘要。"
            );
            case WORD -> unavailable(
                    material,
                    "当前未启用旧版 Word 正文提取，未生成或推断正文摘要。"
            );
            case XLSX -> unavailable(
                    material,
                    "当前未启用 XLSX 正文提取，未生成或推断正文摘要。"
            );
            case OTHER -> unavailable(
                    material,
                    "该文件格式当前不支持正文提取，未生成或推断正文摘要。"
            );
        };
    }

    private Extraction extractUtf8(UploadedMaterial material, String sourceLabel) {
        byte[] bytes = readBoundedFile(material);
        TextValue value = decodeUtf8(bytes, "Material text is not valid UTF-8.");
        if (value.text().isBlank()) {
            return Extraction.unavailable("文件未包含可用的 UTF-8 正文，未生成正文摘要。");
        }
        return Extraction.extracted(value.text(), sourceLabel, value.truncated());
    }

    private Extraction extractPdf(UploadedMaterial material) {
        byte[] bytes = readBoundedFile(material);
        if (!containsPdfHeader(bytes)) {
            return extractMismatchedPdfText(bytes);
        }

        try (PDDocument document = Loader.loadPDF(
                bytes,
                "",
                null,
                null,
                IOUtils.createTempFileOnlyStreamCache()
        )) {
            if (document.isEncrypted()) {
                throw new MaterialParsingException("Encrypted PDF files are not supported.");
            }

            LimitedTextWriter writer = new LimitedTextWriter(MAX_EXTRACTED_CHARACTERS);
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = document.getNumberOfPages();
            if (pages > MAX_PDF_PAGES) {
                stripper.setEndPage(MAX_PDF_PAGES);
                writer.markTruncated();
            }
            try {
                stripper.writeText(document, writer);
            } catch (TextLimitReachedException ignored) {
                // The writer already retained the bounded prefix.
            }

            String text = sanitize(writer.text());
            if (text.isBlank()) {
                return Extraction.unavailable(
                        "PDF 未提取到可用文本；如文件为扫描件，当前未启用 OCR，未生成正文摘要。"
                );
            }
            return Extraction.extracted(text, "PDFBox PDF 文本", writer.truncated());
        } catch (InvalidPasswordException exception) {
            throw new MaterialParsingException("Encrypted PDF files are not supported.");
        } catch (MaterialParsingException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MaterialParsingException("PDF text extraction failed because the file is damaged or unsupported.");
        }
    }

    private Extraction extractMismatchedPdfText(byte[] bytes) {
        TextValue value;
        try {
            value = decodeUtf8(bytes, "PDF content is damaged or unsupported.");
        } catch (MaterialParsingException exception) {
            throw new MaterialParsingException("PDF content is damaged or unsupported.");
        }
        if (value.text().isBlank() || !value.mostlyReadable()) {
            throw new MaterialParsingException("PDF content is damaged or unsupported.");
        }
        return Extraction.extracted(
                value.text(),
                "PDF 格式不匹配时的 UTF-8 兼容读取",
                value.truncated()
        );
    }

    private Extraction extractDocx(UploadedMaterial material) {
        byte[] bytes = readBoundedFile(material);
        rejectOle2Container(bytes);
        validateOoxmlArchive(bytes);

        try (InputStream input = new ByteArrayInputStream(bytes);
             XWPFDocument document = new XWPFDocument(input)) {
            TextAccumulator accumulator = new TextAccumulator(MAX_EXTRACTED_CHARACTERS);
            appendBodyElements(document.getBodyElements(), accumulator);
            String text = sanitize(accumulator.text());
            if (text.isBlank()) {
                return Extraction.unavailable("DOCX 未提取到段落或表格文本，未生成正文摘要。");
            }
            return Extraction.extracted(text, "Apache POI XWPF 段落与表格文本", accumulator.truncated());
        } catch (MaterialParsingException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MaterialParsingException("DOCX text extraction failed because the file is damaged or unsupported.");
        }
    }

    private Extraction extractPptx(UploadedMaterial material) {
        byte[] bytes = readBoundedFile(material);
        rejectOle2Container(bytes);
        validateOoxmlArchive(bytes);

        try (InputStream input = new ByteArrayInputStream(bytes);
             XMLSlideShow slideShow = new XMLSlideShow(input)) {
            TextAccumulator accumulator = new TextAccumulator(MAX_EXTRACTED_CHARACTERS);
            int slideCount = Math.min(slideShow.getSlides().size(), MAX_PPTX_SLIDES);
            for (int index = 0; index < slideCount && !accumulator.full(); index++) {
                for (XSLFShape shape : slideShow.getSlides().get(index).getShapes()) {
                    appendShape(shape, accumulator);
                    if (accumulator.full()) {
                        break;
                    }
                }
            }
            if (slideShow.getSlides().size() > MAX_PPTX_SLIDES) {
                accumulator.markTruncated();
            }

            String text = sanitize(accumulator.text());
            if (text.isBlank()) {
                return Extraction.unavailable("PPTX 未提取到页面 shape 文本，未生成正文摘要。");
            }
            return Extraction.extracted(text, "Apache POI XSLF 页面 shape 文本", accumulator.truncated());
        } catch (MaterialParsingException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MaterialParsingException("PPTX text extraction failed because the file is damaged or unsupported.");
        }
    }

    private Extraction unavailable(UploadedMaterial material, String reason) {
        requireAvailableResource(material, false);
        return Extraction.unavailable(reason);
    }

    private byte[] readBoundedFile(UploadedMaterial material) {
        Resource resource = requireAvailableResource(material, true);
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
            if (bytes.length > MAX_INPUT_BYTES) {
                throw new MaterialParsingException("Material file exceeds the parsing size limit.");
            }
            if (bytes.length == 0) {
                throw new MaterialParsingException("Material file is empty.");
            }
            return bytes;
        } catch (MaterialParsingException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MaterialParsingException("Material file could not be opened for parsing.");
        }
    }

    private Resource requireAvailableResource(UploadedMaterial material, boolean enforceParsingLimit) {
        Long declaredSize = material.getFileSize();
        if (declaredSize != null && declaredSize <= 0) {
            throw new MaterialParsingException("Material file is empty.");
        }
        if (enforceParsingLimit && declaredSize != null && declaredSize > MAX_INPUT_BYTES) {
            throw new MaterialParsingException("Material file exceeds the parsing size limit.");
        }

        try {
            Resource resource = fileStorageService.load(material.getFilePath());
            long actualSize = resource.contentLength();
            if (actualSize <= 0) {
                throw new MaterialParsingException("Material file is empty.");
            }
            if (enforceParsingLimit && actualSize > MAX_INPUT_BYTES) {
                throw new MaterialParsingException("Material file exceeds the parsing size limit.");
            }
            return resource;
        } catch (MaterialParsingException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MaterialParsingException("Material file could not be opened for parsing.");
        }
    }

    private static void appendBodyElements(List<IBodyElement> elements, TextAccumulator accumulator) {
        for (IBodyElement element : elements) {
            if (accumulator.full()) {
                return;
            }
            if (element instanceof XWPFParagraph paragraph) {
                accumulator.appendLine(paragraph.getText());
            } else if (element instanceof XWPFTable table) {
                appendTable(table, accumulator);
            }
        }
    }

    private static void appendTable(XWPFTable table, TextAccumulator accumulator) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                appendBodyElements(cell.getBodyElements(), accumulator);
                if (accumulator.full()) {
                    return;
                }
            }
        }
    }

    private static void appendShape(XSLFShape shape, TextAccumulator accumulator) {
        if (shape instanceof XSLFTable table) {
            for (XSLFTableRow row : table.getRows()) {
                for (XSLFTableCell cell : row.getCells()) {
                    accumulator.appendLine(cell.getText());
                    if (accumulator.full()) {
                        return;
                    }
                }
            }
        } else if (shape instanceof XSLFTextShape textShape) {
            accumulator.appendLine(textShape.getText());
        } else if (shape instanceof XSLFGroupShape groupShape) {
            for (XSLFShape child : groupShape.getShapes()) {
                appendShape(child, accumulator);
                if (accumulator.full()) {
                    return;
                }
            }
        }
    }

    private static void validateOoxmlArchive(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw new MaterialParsingException("Office document content is damaged or unsupported.");
        }

        int entries = 0;
        long totalExpanded = 0;
        byte[] buffer = new byte[8_192];
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ARCHIVE_ENTRIES || hasUnsafeArchivePath(entry.getName())) {
                    throw new MaterialParsingException("Office document archive is unsafe or too large.");
                }

                long entryExpanded = 0;
                int read;
                while ((read = archive.read(buffer)) != -1) {
                    entryExpanded += read;
                    totalExpanded += read;
                    if (entryExpanded > MAX_ARCHIVE_ENTRY_BYTES
                            || totalExpanded > MAX_ARCHIVE_EXPANDED_BYTES) {
                        throw new MaterialParsingException("Office document archive is unsafe or too large.");
                    }
                }
                archive.closeEntry();
            }
        } catch (MaterialParsingException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MaterialParsingException("Office document content is damaged or unsupported.");
        }
        if (entries == 0) {
            throw new MaterialParsingException("Office document content is damaged or unsupported.");
        }
    }

    private static void rejectOle2Container(byte[] bytes) {
        if (startsWith(bytes, OLE2_HEADER, 0)) {
            throw new MaterialParsingException("Encrypted or legacy Office files are not supported.");
        }
    }

    private static boolean hasUnsafeArchivePath(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String normalized = name.replace('\\', '/');
        return normalized.startsWith("/")
                || normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.contains("/../")
                || normalized.contains(":");
    }

    private static TextValue decodeUtf8(byte[] bytes, String failureMessage) {
        int offset = startsWith(bytes, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, 0) ? 3 : 0;
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
            boolean mostlyReadable = isMostlyReadableText(decoded);
            String sanitized = sanitize(decoded);
            if (sanitized.length() <= MAX_EXTRACTED_CHARACTERS) {
                return new TextValue(sanitized, false, mostlyReadable);
            }
            int end = MAX_EXTRACTED_CHARACTERS;
            if (Character.isHighSurrogate(sanitized.charAt(end - 1))) {
                end--;
            }
            return new TextValue(sanitized.substring(0, end).strip(), true, mostlyReadable);
        } catch (CharacterCodingException exception) {
            throw new MaterialParsingException(failureMessage);
        }
    }

    private static boolean isMostlyReadableText(String text) {
        int checked = 0;
        int readable = 0;
        for (int index = 0; index < text.length() && checked < 8_192; index++) {
            char value = text.charAt(index);
            checked++;
            if (!Character.isISOControl(value) || value == '\n' || value == '\r' || value == '\t') {
                readable++;
            }
        }
        return checked > 0 && readable * 100 >= checked * 90;
    }

    private static boolean containsPdfHeader(byte[] bytes) {
        int maxOffset = Math.min(bytes.length - PDF_HEADER.length, 1_024);
        for (int offset = 0; offset <= maxOffset; offset++) {
            if (startsWith(bytes, PDF_HEADER, offset)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix, int offset) {
        if (offset < 0 || bytes.length - offset < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[offset + index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\uFEFF' || character == '\u0000') {
                continue;
            }
            if (character == '\r') {
                if (index + 1 < value.length() && value.charAt(index + 1) == '\n') {
                    index++;
                }
                sanitized.append('\n');
            } else if (!Character.isISOControl(character) || character == '\n' || character == '\t') {
                sanitized.append(character);
            }
        }
        return sanitized.toString().strip();
    }

    record Extraction(String text, String sourceLabel, String noTextReason, boolean truncated) {

        static Extraction extracted(String text, String sourceLabel, boolean truncated) {
            return new Extraction(text, sourceLabel, null, truncated);
        }

        static Extraction unavailable(String reason) {
            return new Extraction("", null, reason, false);
        }

        boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    private record TextValue(String text, boolean truncated, boolean mostlyReadable) {
    }

    private static final class TextAccumulator {

        private final int limit;
        private final StringBuilder value = new StringBuilder();
        private boolean truncated;

        private TextAccumulator(int limit) {
            this.limit = limit;
        }

        private void appendLine(String line) {
            String cleaned = sanitize(line);
            if (cleaned.isBlank() || full()) {
                return;
            }
            int separatorLength = value.isEmpty() ? 0 : 1;
            int remaining = limit - value.length() - separatorLength;
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            if (separatorLength > 0) {
                value.append('\n');
            }
            if (cleaned.length() <= remaining) {
                value.append(cleaned);
            } else {
                int end = remaining;
                if (end > 0 && Character.isHighSurrogate(cleaned.charAt(end - 1))) {
                    end--;
                }
                value.append(cleaned, 0, end);
                truncated = true;
            }
        }

        private boolean full() {
            return value.length() >= limit;
        }

        private void markTruncated() {
            truncated = true;
        }

        private String text() {
            return value.toString();
        }

        private boolean truncated() {
            return truncated;
        }
    }

    private static final class LimitedTextWriter extends Writer {

        private final int limit;
        private final StringBuilder value = new StringBuilder();
        private boolean truncated;

        private LimitedTextWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            int remaining = limit - value.length();
            if (remaining <= 0) {
                truncated = true;
                throw new TextLimitReachedException();
            }
            int accepted = Math.min(length, remaining);
            value.append(buffer, offset, accepted);
            if (accepted < length) {
                truncated = true;
                throw new TextLimitReachedException();
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private String text() {
            return value.toString();
        }

        private boolean truncated() {
            return truncated;
        }

        private void markTruncated() {
            truncated = true;
        }
    }

    private static final class TextLimitReachedException extends IOException {
    }
}
