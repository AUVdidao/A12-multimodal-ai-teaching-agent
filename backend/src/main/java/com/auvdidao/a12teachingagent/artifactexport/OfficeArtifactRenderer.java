package com.auvdidao.a12teachingagent.artifactexport;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.CourseInfo;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.DocSection;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.LessonPlanContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "a12.artifact-generator.mode", havingValue = "local", matchIfMissing = true)
class OfficeArtifactRenderer implements ArtifactGenerator {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final String FONT_FAMILY = "Microsoft YaHei";

    private final ObjectMapper objectMapper;

    OfficeArtifactRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] renderDocx(Project project, GeneratedArtifact artifact) {
        requireArtifact(artifact, ArtifactType.DOCX);
        LessonPlanContent content = readContent(artifact, LessonPlanContent.class, "DOCX");
        List<DocSection> sections = safeList(content.sections());
        if (!hasText(content.title()) && sections.isEmpty()) {
            throw invalidContent("DOCX", "title or sections must be present");
        }

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            setDocumentMargins(document);
            addDocumentTitle(document, firstNonBlank(content.title(), artifact.getTitle(), project.getProjectName()));
            addCourseInfo(document, content.courseInfo());
            if (sections.isEmpty()) {
                addFallbackSections(document, content);
            } else {
                sections.stream()
                        .sorted(java.util.Comparator.comparingInt(section -> section.order() == null ? Integer.MAX_VALUE : section.order()))
                        .forEach(section -> addSection(document, section.title(), section.paragraphs()));
            }
            document.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to render DOCX export", exception);
        }
    }

    private static void setDocumentMargins(XWPFDocument document) {
        var sectionProperties = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();
        var margins = sectionProperties.isSetPgMar() ? sectionProperties.getPgMar() : sectionProperties.addNewPgMar();
        margins.setTop(1134L);
        margins.setBottom(1134L);
        margins.setLeft(1276L);
        margins.setRight(1276L);
    }

    private static void addDocumentTitle(XWPFDocument document, String title) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(320);
        XWPFRun run = paragraph.createRun();
        run.setText(firstNonBlank(title, "Lesson plan"));
        styleRun(run, 22, true, "172033");
    }

    private static void addCourseInfo(XWPFDocument document, CourseInfo courseInfo) {
        if (courseInfo == null) {
            return;
        }
        List<String[]> rows = new ArrayList<>();
        addInfoRow(rows, "课程名称", courseInfo.courseName());
        addInfoRow(rows, "章节主题", courseInfo.chapterTopic());
        addInfoRow(rows, "授课对象", courseInfo.targetAudience());
        addInfoRow(rows, "课时长度", courseInfo.lessonDurationMinutes() == null
                ? null : courseInfo.lessonDurationMinutes() + " 分钟");
        addInfoRow(rows, "生成模式", courseInfo.generationMode());
        if (rows.isEmpty()) {
            return;
        }

        XWPFTable table = document.createTable(rows.size(), 2);
        table.setWidth("100%");
        for (int index = 0; index < rows.size(); index++) {
            String[] values = rows.get(index);
            setCellText(table.getRow(index).getCell(0), values[0], true);
            setCellText(table.getRow(index).getCell(1), values[1], false);
        }
        document.createParagraph().setSpacingAfter(120);
    }

    private static void addInfoRow(List<String[]> rows, String label, String value) {
        if (hasText(value)) {
            rows.add(new String[]{label, value.trim()});
        }
    }

    private static void setCellText(XWPFTableCell cell, String value, boolean label) {
        XWPFParagraph paragraph = cell.getParagraphs().get(0);
        paragraph.setSpacingBefore(80);
        paragraph.setSpacingAfter(80);
        XWPFRun run = paragraph.createRun();
        run.setText(value);
        styleRun(run, 10, label, label ? "2457D6" : "172033");
    }

    private static void addFallbackSections(XWPFDocument document, LessonPlanContent content) {
        addSection(document, "教学目标", content.teachingGoals());
        addSection(document, "教学重点", content.keyPoints());
        addSection(document, "教学难点", content.difficultPoints());
        addSection(document, "教学方法", content.methods());
        addSection(document, "课堂活动", content.classroomActivities());
        addSection(document, "课后作业", content.homework());
        addSection(document, "资源说明", content.resourceNotes());
    }

    private static void addSection(XWPFDocument document, String title, List<String> paragraphs) {
        List<String> values = normalized(paragraphs);
        if (!hasText(title) && values.isEmpty()) {
            return;
        }

        XWPFParagraph heading = document.createParagraph();
        heading.setSpacingBefore(240);
        heading.setSpacingAfter(100);
        XWPFRun headingRun = heading.createRun();
        headingRun.setText(firstNonBlank(title, "Section"));
        styleRun(headingRun, 15, true, "2457D6");

        for (String value : values) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setIndentationLeft(260);
            paragraph.setIndentationHanging(180);
            paragraph.setSpacingAfter(90);
            XWPFRun run = paragraph.createRun();
            run.setText("• " + value);
            styleRun(run, 11, false, "172033");
        }
    }

    private static void styleRun(XWPFRun run, int size, boolean bold, String color) {
        run.setFontFamily(FONT_FAMILY);
        run.setFontFamily(FONT_FAMILY, XWPFRun.FontCharRange.eastAsia);
        run.setFontSize(size);
        run.setBold(bold);
        run.setColor(color);
    }

    private <T> T readContent(GeneratedArtifact artifact, Class<T> type, String format) {
        if (!hasText(artifact.getContentJson())) {
            throw invalidContent(format, "content is empty");
        }
        try {
            return objectMapper.readValue(artifact.getContentJson(), type);
        } catch (JsonProcessingException exception) {
            throw invalidContent(format, "content JSON does not match schema version 1");
        }
    }

    private static void requireArtifact(GeneratedArtifact artifact, ArtifactType expectedType) {
        if (artifact.getArtifactType() != expectedType) {
            throw invalidContent(expectedType.name(), "artifact type does not match the requested export");
        }
        if (!Integer.valueOf(SUPPORTED_SCHEMA_VERSION).equals(artifact.getSchemaVersion())) {
            throw invalidContent(expectedType.name(),
                    "unsupported schema version: " + artifact.getSchemaVersion());
        }
    }

    private static BadRequestException invalidContent(String format, String reason) {
        return new BadRequestException("Cannot export " + format + ": " + reason);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static List<String> normalized(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(OfficeArtifactRenderer::hasText)
                .map(String::trim)
                .toList();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
