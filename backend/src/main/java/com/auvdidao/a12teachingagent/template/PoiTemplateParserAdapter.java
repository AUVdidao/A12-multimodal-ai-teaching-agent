package com.auvdidao.a12teachingagent.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.presentationml.x2006.main.CTConnector;
import org.openxmlformats.schemas.presentationml.x2006.main.CTApplicationNonVisualDrawingProps;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPlaceholder;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShapeNonVisual;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PoiTemplateParserAdapter implements TemplateParser {

    private final ObjectMapper objectMapper;

    public PoiTemplateParserAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ParseResult parse(Resource source) throws IOException {
        try (InputStream input = source.getInputStream(); XMLSlideShow show = new XMLSlideShow(input)) {
            List<Map<String, Object>> slides = new ArrayList<>();
            for (int slideIndex = 0; slideIndex < show.getSlides().size(); slideIndex++) {
                XSLFSlide slide = show.getSlides().get(slideIndex);
                List<Map<String, Object>> shapes = new ArrayList<>();
                collectShapes(slide.getShapes(), "slide-" + (slideIndex + 1), shapes);
                Map<String, Object> slideSnapshot = new LinkedHashMap<>();
                slideSnapshot.put("pageNumber", slideIndex + 1);
                slideSnapshot.put("shapeCount", shapes.size());
                slideSnapshot.put("shapes", shapes);
                slides.add(slideSnapshot);
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("adapter", "apache-poi-readonly");
            snapshot.put("slideCount", slides.size());
            snapshot.put("pageWidth", show.getPageSize().getWidth());
            snapshot.put("pageHeight", show.getPageSize().getHeight());
            snapshot.put("pageWidthEmu", Math.round(show.getPageSize().getWidth() * 12_700d));
            snapshot.put("pageHeightEmu", Math.round(show.getPageSize().getHeight() * 12_700d));
            snapshot.put("slides", slides);
            String json = objectMapper.writeValueAsString(snapshot);
            return new ParseResult(json, TemplateChecksum.sha256(json), slides.size());
        }
    }

    private void collectShapes(List<XSLFShape> source, String parentPath, List<Map<String, Object>> target) {
        for (int index = 0; index < source.size(); index++) {
            XSLFShape shape = source.get(index);
            String path = parentPath + "/shape-" + (index + 1);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reference", path);
            item.put("type", shape.getClass().getSimpleName());
            Rectangle2D anchor = shape.getAnchor();
            if (anchor != null) {
                item.put("x", anchor.getX());
                item.put("y", anchor.getY());
                item.put("width", anchor.getWidth());
                item.put("height", anchor.getHeight());
                item.put("coordinateUnit", "POINTS_FROM_POI_ANCHOR");
                item.put("xEmu", Math.round(anchor.getX() * 12_700d));
                item.put("yEmu", Math.round(anchor.getY() * 12_700d));
                item.put("widthEmu", Math.round(anchor.getWidth() * 12_700d));
                item.put("heightEmu", Math.round(anchor.getHeight() * 12_700d));
            }
            item.put("text", shape instanceof XSLFTextShape);
            item.put("textContent", shape instanceof XSLFTextShape textShape && !textShape.getText().isBlank());
            item.put("picture", shape instanceof XSLFPictureShape);
            item.put("table", shape instanceof XSLFTable);
            item.put("chart", shape.getClass().getSimpleName().contains("Chart"));
            String geometry = geometry(shape);
            if (geometry != null) {
                item.put("geometry", geometry);
            }
            addPlaceholderFacts(shape, item);
            if (isFixedDecoration(shape, anchor, geometry)) {
                // This is deliberately a narrow, source-backed exception for
                // an empty, no-fill AutoShape used as fixed decoration.  It
                // remains a native object reference, but is not a content
                // slot and is never exposed as planning capability.
                item.put("fixedDecoration", true);
                item.put("fixedDecorationReason", "EMPTY_AUTOSHAPE_NO_FILL");
            }
            target.add(item);
            if (shape instanceof XSLFGroupShape group) {
                collectShapes(group.getShapes(), path, target);
            }
        }
    }

    private String geometry(XSLFShape shape) {
        if (shape.getXmlObject() instanceof CTConnector connector) {
            return "connector";
        }
        if (shape.getXmlObject() instanceof CTShape autoShape) {
            CTShapeProperties properties = autoShape.getSpPr();
            if (properties != null && properties.isSetPrstGeom()
                    && properties.getPrstGeom().getPrst() != null) {
                return properties.getPrstGeom().getPrst().toString();
            }
        }
        return null;
    }

    private boolean isFixedDecoration(XSLFShape shape, Rectangle2D anchor, String geometry) {
        if (!(shape instanceof org.apache.poi.xslf.usermodel.XSLFAutoShape autoShape)
                || anchor == null
                || (!isZero(anchor.getWidth()) && !isZero(anchor.getHeight()))
                || !"rect".equalsIgnoreCase(geometry)) {
            return false;
        }
        if (shape instanceof XSLFTextShape textShape && !textShape.getText().isBlank()) {
            return false;
        }
        if (!(autoShape.getXmlObject() instanceof CTShape xmlShape)) {
            return false;
        }
        CTShapeProperties properties = xmlShape.getSpPr();
        return properties != null
                && properties.isSetNoFill()
                && properties.isSetLn()
                && properties.getLn() != null
                && properties.getLn().isSetNoFill();
    }

    private boolean isZero(double value) {
        return Double.compare(value, 0d) == 0;
    }

    private void addPlaceholderFacts(XSLFShape shape, Map<String, Object> item) {
        if (!(shape.getXmlObject() instanceof CTShape xmlShape)) {
            return;
        }
        CTShapeNonVisual nonVisual = xmlShape.getNvSpPr();
        CTApplicationNonVisualDrawingProps application = nonVisual == null ? null : nonVisual.getNvPr();
        if (application == null || !application.isSetPh()) {
            item.put("placeholder", false);
            item.put("contentPlaceholder", false);
            return;
        }
        CTPlaceholder placeholder = application.getPh();
        String placeholderType = placeholder != null && placeholder.isSetType() && placeholder.getType() != null
                ? placeholder.getType().toString().toLowerCase(java.util.Locale.ROOT)
                : "body";
        item.put("placeholder", true);
        item.put("placeholderType", placeholderType);
        if (placeholder != null && placeholder.isSetIdx()) {
            item.put("placeholderIndex", placeholder.getIdx());
        }
        item.put("contentPlaceholder", isContentPlaceholder(placeholderType));
    }

    private boolean isContentPlaceholder(String placeholderType) {
        return switch (placeholderType) {
            case "body", "obj", "content", "title", "ctrtitle", "subtitle" -> true;
            default -> false;
        };
    }
}
