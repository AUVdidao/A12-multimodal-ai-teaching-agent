package com.auvdidao.a12teachingagent.material.parse;

import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.material.storage.LocalFileStorageService;
import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicMaterialPrototypeParserTest {

    @TempDir
    Path tempDirectory;

    private Path storageRoot;
    private DeterministicMaterialPrototypeParser parser;
    private int fixtureSequence;

    @BeforeEach
    void setUp() {
        storageRoot = tempDirectory.resolve("uploads");
        StorageProperties properties = new StorageProperties();
        properties.setUploadDir(storageRoot.toString());
        parser = new DeterministicMaterialPrototypeParser(new LocalFileStorageService(properties));
    }

    @Test
    void readsUtf8TxtAndRemovesBom() throws Exception {
        byte[] content = withUtf8Bom("TxtActualMarker\n真实 TXT 正文");

        MaterialPrototypeParser.ParsedContent parsed = parse(
                material("lesson.txt", MaterialFileType.TXT, content),
                "TxtActualMarker"
        );

        assertThat(parsed.summary())
                .contains("实际提取文本", "TXT UTF-8", "TxtActualMarker", "真实 TXT 正文")
                .doesNotContain("\uFEFF");
        assertThat(parsed.keywords()).contains("TxtActualMarker");
    }

    @Test
    void readsUtf8MarkdownContent() throws Exception {
        byte[] content = "# MarkdownActualMarker\n\n正文包含确定性证据。".getBytes(StandardCharsets.UTF_8);

        MaterialPrototypeParser.ParsedContent parsed = parse(
                material("lesson.md", MaterialFileType.MD, content),
                "MarkdownActualMarker"
        );

        assertThat(parsed.summary()).contains("Markdown UTF-8", "MarkdownActualMarker", "确定性证据");
        assertThat(parsed.keywords()).contains("MarkdownActualMarker");
    }

    @Test
    void extractsDocxParagraphsAndTables() throws Exception {
        MaterialPrototypeParser.ParsedContent parsed = parse(
                material("lesson.docx", MaterialFileType.DOCX, createDocx()),
                "DocxParagraphMarker"
        );

        assertThat(parsed.summary())
                .contains("Apache POI XWPF", "DocxParagraphMarker", "DocxTableMarker");
        assertThat(parsed.keywords()).contains("DocxParagraphMarker", "DocxTableMarker");
    }

    @Test
    void extractsPptxShapeTextFromEachSlide() throws Exception {
        MaterialPrototypeParser.ParsedContent parsed = parse(
                material("lesson.pptx", MaterialFileType.PPTX, createPptx()),
                "PptxFirstShapeMarker"
        );

        assertThat(parsed.summary())
                .contains("Apache POI XSLF", "PptxFirstShapeMarker", "PptxSecondShapeMarker");
        assertThat(parsed.keywords()).contains("PptxFirstShapeMarker", "PptxSecondShapeMarker");
    }

    @Test
    void extractsPdfPageText() throws Exception {
        MaterialPrototypeParser.ParsedContent parsed = parse(
                material("lesson.pdf", MaterialFileType.PDF, createPdf("PdfActualMarker", false)),
                "PdfActualMarker"
        );

        assertThat(parsed.summary()).contains("PDFBox PDF 文本", "PdfActualMarker");
        assertThat(parsed.keywords()).contains("PdfActualMarker");
    }

    @Test
    void keepsLegacyM2PlainTextPdfFixtureHonestAndCompatible() throws Exception {
        MaterialPrototypeParser.ParsedContent parsed = parse(
                material(
                        "legacy-fixture.pdf",
                        MaterialFileType.PDF,
                        "prototype material".getBytes(StandardCharsets.UTF_8)
                ),
                "光合作用"
        );

        assertThat(parsed.summary())
                .contains("实际提取文本", "UTF-8 兼容读取", "prototype material", "光合作用");
        assertThat(parsed.keywords()).contains("prototype material", "prototype", "material");
    }

    @Test
    void reportsBlankPdfWithoutClaimingBodyText() throws Exception {
        MaterialPrototypeParser.ParsedContent parsed = parse(
                material("blank.pdf", MaterialFileType.PDF, createPdf(null, false)),
                "BlankPdfTopic"
        );

        assertThat(parsed.summary())
                .contains("未提取到可用文本", "未启用 OCR", "不是文件正文")
                .doesNotContain("实际提取文本，来源");
        assertThat(parsed.keywords()).isEmpty();
    }

    @Test
    void imagesAndVideosUseHonestDegradation() throws Exception {
        MaterialPrototypeParser.ParsedContent image = parse(
                material("diagram.png", MaterialFileType.PNG, new byte[]{1, 2, 3}),
                "ImageTopic"
        );
        MaterialPrototypeParser.ParsedContent video = parse(
                material("lesson.mp4", MaterialFileType.MP4, new byte[]{4, 5, 6}),
                "VideoTopic"
        );

        assertThat(image.summary())
                .contains("未启用 OCR", "未生成或推断正文摘要")
                .doesNotContain("实际提取文本，来源");
        assertThat(video.summary())
                .contains("未启用转写", "未生成或推断正文摘要")
                .doesNotContain("实际提取文本，来源");
        assertThat(image.keywords()).isEmpty();
        assertThat(video.keywords()).isEmpty();
    }

    @Test
    void rejectsDamagedDocxWithStablePathFreeError() throws Exception {
        UploadedMaterial material = material("damaged.docx", MaterialFileType.DOCX, damagedOoxml());

        assertThatThrownBy(() -> parse(material, "DamageTopic"))
                .isInstanceOf(MaterialParsingException.class)
                .hasMessage("DOCX text extraction failed because the file is damaged or unsupported.")
                .hasMessageNotContaining(tempDirectory.toString());
    }

    @Test
    void rejectsEncryptedPdfWithStableError() throws Exception {
        UploadedMaterial material = material(
                "encrypted.pdf",
                MaterialFileType.PDF,
                createPdf("EncryptedMarker", true)
        );

        assertThatThrownBy(() -> parse(material, "EncryptedMarker"))
                .isInstanceOf(MaterialParsingException.class)
                .hasMessage("Encrypted PDF files are not supported.")
                .hasMessageNotContaining(tempDirectory.toString());
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        UploadedMaterial material = material("empty.txt", MaterialFileType.TXT, new byte[0]);

        assertThatThrownBy(() -> parse(material, "EmptyTopic"))
                .isInstanceOf(MaterialParsingException.class)
                .hasMessage("Material file is empty.");
    }

    @Test
    void rejectsInvalidUtf8() throws Exception {
        UploadedMaterial material = material(
                "invalid.txt",
                MaterialFileType.TXT,
                new byte[]{(byte) 0xC3, 0x28}
        );

        assertThatThrownBy(() -> parse(material, "Utf8Topic"))
                .isInstanceOf(MaterialParsingException.class)
                .hasMessage("Material text is not valid UTF-8.");
    }

    @Test
    void rejectsStoragePathTraversalWithoutReadingOutsideFile() throws Exception {
        Path outside = tempDirectory.resolve("outside.txt");
        Files.writeString(outside, "OutsideSecretMarker", StandardCharsets.UTF_8);
        UploadedMaterial material = metadata("outside.txt", MaterialFileType.TXT, Files.size(outside));
        material.setFilePath("../outside.txt");

        assertThatThrownBy(() -> parse(material, "TraversalTopic"))
                .isInstanceOf(MaterialParsingException.class)
                .hasMessage("Material file could not be opened for parsing.")
                .hasMessageNotContaining("outside.txt")
                .hasMessageNotContaining(tempDirectory.toString());
    }

    @Test
    void rejectsDeclaredFilesAboveParsingLimitBeforeReading() throws Exception {
        UploadedMaterial material = material(
                "large.txt",
                MaterialFileType.TXT,
                "small fixture".getBytes(StandardCharsets.UTF_8)
        );
        material.setFileSize((long) MaterialTextExtractor.MAX_INPUT_BYTES + 1);

        assertThatThrownBy(() -> parse(material, "LimitTopic"))
                .isInstanceOf(MaterialParsingException.class)
                .hasMessage("Material file exceeds the parsing size limit.");
    }

    @Test
    void truncatesExtractedCharactersBeforeKeywordGeneration() throws Exception {
        String content = "LimitVisibleMarker\n"
                + "x".repeat(MaterialTextExtractor.MAX_EXTRACTED_CHARACTERS)
                + "\nHiddenAfterLimitMarker";
        MaterialPrototypeParser.ParsedContent parsed = parse(
                material("limited.txt", MaterialFileType.TXT, content.getBytes(StandardCharsets.UTF_8)),
                "LimitVisibleMarker"
        );

        assertThat(parsed.summary()).contains("提取内容已按字符或页数上限截断", "LimitVisibleMarker");
        assertThat(parsed.keywords()).doesNotContain("HiddenAfterLimitMarker");
    }

    private MaterialPrototypeParser.ParsedContent parse(UploadedMaterial material, String topic) {
        return parser.parse(material, List.of(PurposeType.TEXTBOOK_BASIS), requirement(topic));
    }

    private RequirementSummary requirement(String topic) {
        RequirementSummary summary = new RequirementSummary();
        summary.setSubject("ParserTestSubject");
        summary.setTopic(topic);
        summary.setTeachingGoals("Use only evidence extracted from the material");
        summary.setKeyPoints(topic);
        summary.setDifficultPoints("Source attribution");
        return summary;
    }

    private UploadedMaterial material(String originalName, MaterialFileType fileType, byte[] content) throws IOException {
        String extension = originalName.substring(originalName.lastIndexOf('.') + 1);
        String storedName = "fixture-" + fixtureSequence++ + "." + extension;
        Path projectDirectory = storageRoot.resolve("1");
        Files.createDirectories(projectDirectory);
        Files.write(projectDirectory.resolve(storedName), content);

        UploadedMaterial material = metadata(originalName, fileType, content.length);
        material.setFilePath("1/" + storedName);
        return material;
    }

    private UploadedMaterial metadata(String originalName, MaterialFileType fileType, long fileSize) {
        UploadedMaterial material = new UploadedMaterial();
        material.setProjectId(1L);
        material.setOriginalFileName(originalName);
        material.setFileName(originalName);
        material.setFileType(fileType);
        material.setFileSize(fileSize);
        int dot = originalName.lastIndexOf('.');
        material.setFileExtension(dot < 0 ? "" : originalName.substring(dot + 1));
        return material;
    }

    private static byte[] withUtf8Bom(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[body.length + 3];
        content[0] = (byte) 0xEF;
        content[1] = (byte) 0xBB;
        content[2] = (byte) 0xBF;
        System.arraycopy(body, 0, content, 3, body.length);
        return content;
    }

    private static byte[] createDocx() throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("DocxParagraphMarker");
            XWPFTable table = document.createTable(1, 1);
            table.getRow(0).getCell(0).setText("DocxTableMarker");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] createPptx() throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide first = slideShow.createSlide();
            XSLFTextBox firstShape = first.createTextBox();
            firstShape.setText("PptxFirstShapeMarker");

            XSLFSlide second = slideShow.createSlide();
            XSLFTextBox secondShape = second.createTextBox();
            secondShape.setText("PptxSecondShapeMarker");

            slideShow.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] createPdf(String text, boolean encrypted) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (text != null) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            if (encrypted) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy(
                        "owner-password",
                        "user-password",
                        new AccessPermission()
                );
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] damagedOoxml() throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("[Content_Types].xml"));
            archive.write("not valid OOXML".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.finish();
            return output.toByteArray();
        }
    }
}
