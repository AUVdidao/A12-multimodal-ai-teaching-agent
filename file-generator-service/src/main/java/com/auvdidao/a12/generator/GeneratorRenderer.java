package com.auvdidao.a12.generator;

import com.auvdidao.a12.generator.GeneratorDtos.PackageEntry;
import com.auvdidao.a12.generator.GeneratorDtos.PackageRequest;
import com.auvdidao.a12.generator.GeneratorDtos.RenderRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
class GeneratorRenderer {
    private static final String FONT = "Microsoft YaHei";
    private final ObjectMapper objectMapper;
    private final int maxContentBytes;

    GeneratorRenderer(ObjectMapper objectMapper, @Value("${a12.generator.max-content-json-bytes:1048576}") int maxContentBytes) {
        this.objectMapper = objectMapper;
        this.maxContentBytes = maxContentBytes;
    }

    byte[] pptx(RenderRequest request) {
        require(request, "PPT", "PPTX");
        JsonNode root = content(request);
        JsonNode slides = root.path("slides");
        if (!slides.isArray() || slides.isEmpty()) throw fail("INVALID_CONTENT", "PPTX slides must not be empty.");
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            show.setPageSize(new Dimension(960, 540));
            int total = slides.size();
            for (int i = 0; i < total; i++) addSlide(show, slides.get(i), i + 1, total, request, root);
            show.write(out);
            return out.toByteArray();
        } catch (IOException exception) { throw fail("RENDER_FAILED", "PPTX generation failed."); }
    }

    byte[] docx(RenderRequest request) {
        require(request, "DOCX", "DOCX");
        JsonNode root = content(request);
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            heading(document, text(root, "title", first(request.title(), request.projectName(), "Lesson plan")), 22, true, ParagraphAlignment.CENTER);
            JsonNode info = root.path("courseInfo");
            addParagraph(document, first(text(info, "courseName", null), request.courseName()), false);
            addParagraph(document, first(text(info, "chapterTopic", null), request.chapterTopic()), false);
            JsonNode sections = root.path("sections");
            if (sections.isArray() && !sections.isEmpty()) {
                for (JsonNode section : sections) addSection(document, text(section, "title", "Section"), section.path("paragraphs"));
            } else {
                addSection(document, "Teaching goals", root.path("teachingGoals"));
                addSection(document, "Key points", root.path("keyPoints"));
                addSection(document, "Classroom activities", root.path("classroomActivities"));
            }
            document.write(out);
            return out.toByteArray();
        } catch (IOException exception) { throw fail("RENDER_FAILED", "DOCX generation failed."); }
    }

    byte[] interactiveHtml(RenderRequest request) {
        require(request, "INTERACTION", "INTERACTION_HTML");
        JsonNode root = content(request);
        String title = escape(text(root, "title", first(request.title(), request.projectName(), "Interactive activity")));
        String instructions = escape(text(root, "instructions", ""));
        String data;
        try { data = objectMapper.writeValueAsString(root.path("questions")); }
        catch (Exception exception) { throw fail("INVALID_CONTENT", "Interaction JSON cannot be serialized."); }
        data = data.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
                + title + "</title><style>body{font-family:Arial,'Microsoft YaHei',sans-serif;max-width:760px;margin:32px auto;padding:0 20px;color:#172033}button{margin:8px 0;padding:10px;border:1px solid #2457d6;background:#fff;cursor:pointer}button:hover{background:#eef3ff}</style></head><body><h1>"
                + title + "</h1><p>" + instructions + "</p><main id=\"app\"></main><script>const questions=" + data
                + ";const app=document.querySelector('#app');questions.forEach((q,i)=>{const s=document.createElement('section');s.innerHTML=`<h2>${i+1}. ${q.question||''}</h2>`;(q.options||[]).forEach((o,j)=>{const b=document.createElement('button');b.textContent=o;b.onclick=()=>{b.style.background=j===q.correctOption?'#d8f3e8':'#ffe3e3'};s.appendChild(b);s.appendChild(document.createElement('br'))});app.appendChild(s)});</script></body></html>";
        return html.getBytes(StandardCharsets.UTF_8);
    }

    byte[] packageFiles(PackageRequest request) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (PackageEntry entry : request.entries()) {
                String format = entry.format().trim().toUpperCase(Locale.ROOT);
                byte[] bytes = switch (format) {
                    case "PPTX" -> pptx(entry.request());
                    case "DOCX" -> docx(entry.request());
                    case "INTERACTION_HTML", "HTML" -> interactiveHtml(entry.request());
                    default -> throw fail("UNSUPPORTED_FORMAT", "Package format is unsupported: " + entry.format());
                };
                String name = entry.filename().replaceAll("[\\\\/:*?\"<>|]", "_");
                zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
            }
            zip.finish(); return out.toByteArray();
        } catch (IOException exception) { throw fail("PACKAGE_FAILED", "Artifact package generation failed."); }
    }

    private void addSlide(XMLSlideShow show, JsonNode slideData, int position, int total, RenderRequest request, JsonNode content) {
        XSLFSlide slide = show.createSlide(); slide.getBackground().setFillColor(new Color(247, 249, 252));
        XSLFAutoShape accent = slide.createAutoShape(); accent.setShapeType(ShapeType.RECT); accent.setAnchor(new Rectangle2D.Double(0, 0, 16, 540)); accent.setFillColor(new Color(36, 87, 214)); accent.setLineColor(new Color(36, 87, 214));
        String title = text(slideData, "title", text(content, "deckTitle", first(request.title(), request.projectName(), "Teaching presentation")));
        textBox(slide, 58, 48, 844, 72, title, 27, true, new Color(23, 32, 51), false);
        JsonNode points = slideData.path("points"); if (points.isArray() && !points.isEmpty()) {
            XSLFTextBox box = slide.createTextBox(); box.setAnchor(new Rectangle2D.Double(72, 140, 806, 290)); box.clearText();
            for (JsonNode point : points) { XSLFTextParagraph p = box.addNewTextParagraph(); p.setBullet(true); p.setLeftMargin(28d); p.setIndent(-14d); p.setSpaceAfter(10d); p.setTextAlign(TextParagraph.TextAlign.LEFT); XSLFTextRun run = p.addNewTextRun(); run.setText(point.asText()); style(run, 18, false, new Color(23,32,51)); }
        }
        textBox(slide, 58, 492, 844, 22, position + " / " + total, 10, false, new Color(83,97,116), true);
    }

    private static void textBox(XSLFSlide slide, double x, double y, double w, double h, String value, double size, boolean bold, Color color, boolean right) {
        XSLFTextBox box = slide.createTextBox(); box.setAnchor(new Rectangle2D.Double(x, y, w, h)); box.setWordWrap(true); XSLFTextRun run = box.setText(value); style(run, size, bold, color); if (right) box.getTextParagraphs().get(0).setTextAlign(TextParagraph.TextAlign.RIGHT);
    }
    private static void style(XSLFTextRun run, double size, boolean bold, Color color) { run.setFontFamily(FONT); run.setFontSize(size); run.setBold(bold); run.setFontColor(color); }
    private static void heading(XWPFDocument d, String value, int size, boolean bold, ParagraphAlignment alignment) { XWPFParagraph p = d.createParagraph(); p.setAlignment(alignment); XWPFRun r = p.createRun(); r.setText(value); r.setFontFamily(FONT); r.setFontSize(size); r.setBold(bold); r.setColor("172033"); }
    private static void addParagraph(XWPFDocument d, String value, boolean bullet) { if (value == null || value.isBlank()) return; XWPFParagraph p=d.createParagraph(); XWPFRun r=p.createRun(); r.setText((bullet ? "- " : "") + value.trim()); r.setFontFamily(FONT); r.setFontSize(11); }
    private static void addSection(XWPFDocument d, String title, JsonNode paragraphs) { heading(d, title, 15, true, ParagraphAlignment.LEFT); if (paragraphs.isArray()) for (JsonNode p : paragraphs) addParagraph(d, p.asText(), true); }
    private JsonNode content(RenderRequest request) { try { if (request.contentJson().getBytes(StandardCharsets.UTF_8).length > maxContentBytes) throw fail("CONTENT_TOO_LARGE", "content JSON exceeds the configured limit."); return objectMapper.readTree(request.contentJson()); } catch (GeneratorException e) { throw e; } catch (Exception e) { throw fail("INVALID_CONTENT", "content JSON does not match schema version 1."); } }
    private static void require(RenderRequest request, String expectedType, String format) { if (!expectedType.equalsIgnoreCase(request.artifactType())) throw fail("ARTIFACT_TYPE_MISMATCH", format + " request has an incompatible artifact type."); if (request.schemaVersion() != 1) throw fail("UNSUPPORTED_SCHEMA", format + " only supports schema version 1."); }
    private static GeneratorException fail(String code, String message) { return new GeneratorException(code, message); }
    private static String text(JsonNode node, String name, String fallback) { JsonNode value=node.path(name); return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : fallback; }
    private static String first(String... values) { for(String v:values) if(v != null && !v.isBlank()) return v.trim(); return ""; }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
