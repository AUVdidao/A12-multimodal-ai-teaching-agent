package com.auvdidao.a12.pptengine.executor;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Small OOXML package helper used only by the same-package V1 executor. */
final class PptxPackage {

    static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    static final String CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types";
    static final String P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main";
    static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private final LinkedHashMap<String, byte[]> entries;
    private final Map<String, Document> documents = new HashMap<>();

    private PptxPackage(LinkedHashMap<String, byte[]> entries) {
        this.entries = entries;
    }

    static PptxPackage read(Path path) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        Set<String> normalizedEntryNames = new HashSet<>();
        try (InputStream input = Files.newInputStream(path); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                // Directory markers are ZIP metadata, not OOXML parts. Strip
                // exactly one trailing slash, then apply the same strict path
                // validation as files (including traversal and empty segments).
                String partName = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
                if (name.length() > 255) {
                    throw new IllegalArgumentException("unsafe package entry");
                }
                validateEntryName(partName);
                if (!normalizedEntryNames.add(partName.toLowerCase(Locale.ROOT))) {
                    throw new IOException("normalized duplicate zip entry");
                }
                if (entry.isDirectory()) {
                    if (zip.read() != -1) {
                        throw new IOException("directory entry has content");
                    }
                    continue;
                }
                entries.put(name, zip.readAllBytes());
            }
        }
        return new PptxPackage(entries);
    }

    void write(Path path) throws IOException {
        try (OutputStream output = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                // ZIP timestamps are not part of the PowerPoint model.  A
                // fixed timestamp keeps identical execution inputs
                // byte-stable and makes artifact hashes meaningful.
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    Set<String> entryNames() {
        return Set.copyOf(entries.keySet());
    }

    /**
     * Returns slide XML parts physically present in the ZIP package. This is
     * intentionally separate from {@link #slidePaths()}, which only follows
     * the presentation's visible slide order.
     */
    Set<String> physicalSlidePaths() {
        return entries.keySet().stream()
                .filter(PptxPackage::isPhysicalSlidePart)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    byte[] bytes(String name) {
        return entries.get(name);
    }

    void put(String name, byte[] value) {
        validateEntryName(name);
        entries.put(name, value);
        documents.remove(name);
    }

    /**
     * Creates the output slide list from template slide parts already in this
     * package.  The source slide numbers are template/profile coordinates, not
     * output coordinates.  New slide parts use the same directory as the
     * source parts so their relationship targets can be rewritten without
     * changing any layout/master/theme parts.
     */
    List<String> materializeSlides(List<Integer> sourceSlideNumbers) throws IOException {
        List<String> sourceSlides = slidePaths();
        List<String> targetSlides = new ArrayList<>();
        for (int index = 0; index < sourceSlideNumbers.size(); index++) {
            int sourceSlide = sourceSlideNumbers.get(index);
            if (sourceSlide < 1 || sourceSlide > sourceSlides.size()) {
                throw new IOException("template source slide is outside package");
            }
            String sourcePath = sourceSlides.get(sourceSlide - 1);
            String targetPath = nextGeneratedSlidePath(index + 1);
            put(targetPath, bytes(sourcePath).clone());
            String sourceRels = relsPath(sourcePath);
            String targetRels = relsPath(targetPath);
            if (!entries.containsKey(sourceRels)) {
                throw new IOException("template slide relationships are missing");
            }
            put(targetRels, bytes(sourceRels).clone());
            // A notesSlide relationship is bidirectional: the copied notes
            // part still points back to the original source slide. Reusing it
            // for a materialized slide makes PowerPoint reject the package as
            // corrupt even though the ZIP and the slide XML are readable.
            // Generated slides do not yet carry editable speaker notes, so
            // omit that stale relationship until a notes part is cloned with
            // its back-reference rewritten as well.
            Document targetRelationships = document(targetRels);
            for (Element relationship : elements(targetRelationships, REL_NS, "Relationship")) {
                if (relationship.getAttribute("Type").endsWith("/notesSlide")) {
                    targetRelationships.getDocumentElement().removeChild(relationship);
                }
            }
            saveDocument(targetRels, targetRelationships);
            targetSlides.add(targetPath);
        }
        replacePresentationSlides(targetSlides);
        for (String targetPath : targetSlides) {
            ensureContentTypeOverride(targetPath,
                    "application/vnd.openxmlformats-officedocument.presentationml.slide+xml");
        }
        return List.copyOf(targetSlides);
    }

    /**
     * Removes template slide parts that are no longer referenced by
     * presentation.xml, together with their relationship parts and content
     * type overrides. Keeping those orphan parts makes the ZIP look readable
     * while leaving the OOXML package structurally inconsistent.
     */
    void retainOnlySlides(List<String> targetSlides) throws IOException {
        Set<String> keep = new HashSet<>(targetSlides);
        Set<String> removedPackageParts = new HashSet<>();
        for (String name : new ArrayList<>(entries.keySet())) {
            if (isPhysicalSlidePart(name) && !keep.contains(name)) {
                removedPackageParts.add(name);
                entries.remove(name);
                documents.remove(name);
            }
        }
        for (String name : new ArrayList<>(entries.keySet())) {
            if (isSlideRelationshipsPart(name)
                    && !keep.contains(slidePartForRelationships(name))) {
                removedPackageParts.add(name);
                entries.remove(name);
                documents.remove(name);
            }
        }

        // Generated slides deliberately omit the copied notesSlide relationship
        // until a notes part can be cloned and rebound. Remove notes parts that
        // are therefore no longer reachable; otherwise their back-references
        // point at deleted template slides and the final relationship gate
        // correctly rejects the package as dangling.
        Set<String> referencedNotes = new HashSet<>();
        for (String slide : keep) {
            String relationshipsPart = relsPath(slide);
            if (!entries.containsKey(relationshipsPart)) {
                continue;
            }
            Document relationships = document(relationshipsPart);
            for (Element relationship : elements(relationships, REL_NS, "Relationship")) {
                if (relationship.getAttribute("Type").endsWith("/notesSlide")) {
                    referencedNotes.add(resolveTarget(slide, relationship.getAttribute("Target")));
                }
            }
        }
        for (String name : new ArrayList<>(entries.keySet())) {
            if (isPhysicalNotesPart(name) && !referencedNotes.contains(name)) {
                removedPackageParts.add(name);
                entries.remove(name);
                documents.remove(name);
            }
        }
        for (String name : new ArrayList<>(entries.keySet())) {
            if (isNotesRelationshipsPart(name)
                    && !referencedNotes.contains(notesPartForRelationships(name))) {
                removedPackageParts.add(name);
                entries.remove(name);
                documents.remove(name);
            }
        }
        if (removedPackageParts.isEmpty()) {
            return;
        }
        Document contentTypes = document("[Content_Types].xml");
        for (Element override : new ArrayList<>(elements(contentTypes, CT_NS, "Override"))) {
            String partName = override.getAttribute("PartName");
            String normalized = partName.startsWith("/") ? partName.substring(1) : partName;
            if (removedPackageParts.stream().anyMatch(normalized::equalsIgnoreCase)) {
                override.getParentNode().removeChild(override);
            }
        }
        saveDocument("[Content_Types].xml", contentTypes);
    }

    /** Adds an OOXML content-type override without weakening existing entries. */
    void ensureContentTypeOverride(String partName, String contentType) throws IOException {
        Document contentTypes = document("[Content_Types].xml");
        String expectedPartName = "/" + partName;
        for (Element override : elements(contentTypes, CT_NS, "Override")) {
            if (expectedPartName.equals(override.getAttribute("PartName"))) {
                if (!contentType.equals(override.getAttribute("ContentType"))) {
                    throw new IOException("content type override conflict");
                }
                return;
            }
        }
        Element override = contentTypes.createElementNS(CT_NS, "Override");
        override.setAttribute("PartName", expectedPartName);
        override.setAttribute("ContentType", contentType);
        contentTypes.getDocumentElement().appendChild(override);
        saveDocument("[Content_Types].xml", contentTypes);
    }

    /** Returns an existing equivalent relationship or appends one once. */
    static String ensureRelationship(Document rels, String type, String target,
                                     String targetMode, Element template) {
        String normalizedMode = targetMode == null ? "" : targetMode;
        for (Element relationship : elements(rels, REL_NS, "Relationship")) {
            if (type.equals(relationship.getAttribute("Type"))
                    && target.equals(relationship.getAttribute("Target"))
                    && normalizedMode.equals(relationship.getAttribute("TargetMode"))) {
                return relationship.getAttribute("Id");
            }
        }
        String id = nextRelationshipId(rels);
        Element relationship = rels.createElementNS(REL_NS, "Relationship");
        if (template != null) {
            var attributes = template.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                var attribute = attributes.item(index);
                if (!"Id".equals(attribute.getLocalName()) && !"Id".equals(attribute.getNodeName())) {
                    relationship.setAttributeNS(attribute.getNamespaceURI(), attribute.getNodeName(),
                            attribute.getNodeValue());
                }
            }
        }
        relationship.setAttribute("Id", id);
        relationship.setAttribute("Type", type);
        relationship.setAttribute("Target", target);
        if (!normalizedMode.isBlank()) {
            relationship.setAttribute("TargetMode", normalizedMode);
        }
        rels.getDocumentElement().appendChild(relationship);
        return id;
    }

    static Element relationship(Document rels, String id) {
        for (Element relationship : elements(rels, REL_NS, "Relationship")) {
            if (id != null && id.equals(relationship.getAttribute("Id"))) {
                return relationship;
            }
        }
        return null;
    }

    static String relativeTarget(String sourcePart, String targetPart) {
        List<String> source = pathParts(parent(sourcePart));
        List<String> target = pathParts(targetPart);
        int common = 0;
        while (common < source.size() && common < target.size()
                && source.get(common).equals(target.get(common))) {
            common++;
        }
        List<String> result = new ArrayList<>();
        for (int index = common; index < source.size(); index++) {
            result.add("..");
        }
        result.addAll(target.subList(common, target.size()));
        return String.join("/", result);
    }

    Document document(String name) throws IOException {
        if (!entries.containsKey(name)) {
            throw new IOException("missing package part");
        }
        Document existing = documents.get(name);
        if (existing != null) {
            return existing;
        }
        try {
            Document parsed = secureFactory().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(entries.get(name)));
            documents.put(name, parsed);
            return parsed;
        } catch (Exception exception) {
            throw new IOException("invalid XML part", exception);
        }
    }

    void saveDocument(String name, Document document) throws IOException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            put(name, output.toByteArray());
            documents.put(name, document);
        } catch (Exception exception) {
            throw new IOException("cannot serialize XML part", exception);
        }
    }

    List<String> slidePaths() throws IOException {
        Document presentation = document("ppt/presentation.xml");
        Document rels = document("ppt/_rels/presentation.xml.rels");
        Map<String, String> byId = relationships(rels);
        List<String> result = new ArrayList<>();
        NodeList slideIds = presentation.getElementsByTagNameNS(P_NS, "sldId");
        for (int index = 0; index < slideIds.getLength(); index++) {
            Element slideId = (Element) slideIds.item(index);
            String target = byId.get(slideId.getAttributeNS(R_NS, "id"));
            if (target == null) {
                throw new IOException("presentation relationship is missing");
            }
            result.add(resolveTarget("ppt/presentation.xml", target));
        }
        return result;
    }

    String relsPath(String sourcePart) {
        int slash = sourcePart.lastIndexOf('/');
        String parent = slash < 0 ? "" : sourcePart.substring(0, slash + 1);
        String leaf = slash < 0 ? sourcePart : sourcePart.substring(slash + 1);
        return parent + "_rels/" + leaf + ".rels";
    }

    Map<String, String> relationships(Document rels) {
        Map<String, String> result = new LinkedHashMap<>();
        NodeList nodes = rels.getElementsByTagNameNS(REL_NS, "Relationship");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element relationship = (Element) nodes.item(index);
            result.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
        }
        return result;
    }

    static List<Element> elements(Document document, String namespace, String localName) {
        NodeList nodes = document.getElementsByTagNameNS(namespace, localName);
        List<Element> result = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            result.add((Element) nodes.item(index));
        }
        return result;
    }

    private static boolean isPhysicalSlidePart(String name) {
        return name.startsWith("ppt/slides/")
                && name.endsWith(".xml")
                && !name.startsWith("ppt/slides/_rels/")
                && name.indexOf('/', "ppt/slides/".length()) < 0;
    }

    private static boolean isSlideRelationshipsPart(String name) {
        return name.startsWith("ppt/slides/_rels/") && name.endsWith(".xml.rels");
    }

    private static String slidePartForRelationships(String name) {
        String leaf = name.substring("ppt/slides/_rels/".length());
        if (!leaf.endsWith(".rels")) {
            throw new IllegalArgumentException("invalid slide relationship part");
        }
        return "ppt/slides/" + leaf.substring(0, leaf.length() - ".rels".length());
    }

    private static boolean isPhysicalNotesPart(String name) {
        return name.startsWith("ppt/notesSlides/")
                && name.endsWith(".xml")
                && !name.startsWith("ppt/notesSlides/_rels/")
                && name.indexOf('/', "ppt/notesSlides/".length()) < 0;
    }

    private static boolean isNotesRelationshipsPart(String name) {
        return name.startsWith("ppt/notesSlides/_rels/") && name.endsWith(".xml.rels");
    }

    private static String notesPartForRelationships(String name) {
        String leaf = name.substring("ppt/notesSlides/_rels/".length());
        if (!leaf.endsWith(".rels")) {
            throw new IllegalArgumentException("invalid notes relationship part");
        }
        return "ppt/notesSlides/" + leaf.substring(0, leaf.length() - ".rels".length());
    }

    static Element findObject(Document slide, String objectId) {
        List<Element> matches = new ArrayList<>();
        for (Element cNvPr : elements(slide, "*", "cNvPr")) {
            if (objectId != null && objectId.equals(cNvPr.getAttribute("id"))) {
                Node node = cNvPr;
                while (node != null && node.getNodeType() == Node.ELEMENT_NODE) {
                    String name = node.getLocalName();
                    if (Set.of("sp", "pic", "graphicFrame", "grpSp").contains(name)) {
                        matches.add((Element) node);
                        break;
                    }
                    node = node.getParentNode();
                }
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (!matches.isEmpty() || objectId == null) {
            return null;
        }
        return findStableReference(slide, objectId);
    }

    /**
     * Resolves the parser's stable shape reference against the actual OOXML
     * tree when a template does not expose the parser reference as cNvPr@id.
     *
     * The Java parser deliberately emits references such as
     * {@code slide-1/shape-3/shape-1}: each shape number is the one-based
     * position in its parent shape list.  Apache POI's snapshot is the source
     * of that stable path, while the package's native cNvPr ids are unrelated
     * numeric identities.  Mapping the path at execution time keeps the
     * stable reference contract and still targets the original native object;
     * it does not match by name, text, or a best-effort candidate.
     */
    private static Element findStableReference(Document slide, String objectId) {
        String[] parts = objectId.replace('/', '.').split("\\.");
        if (parts.length < 2 || !parts[0].startsWith("slide-")
                || !isPositiveInteger(parts[0].substring("slide-".length()))) {
            return null;
        }
        Element shapeTree = null;
        for (Element candidate : elements(slide, P_NS, "spTree")) {
            if (shapeTree != null) {
                return null;
            }
            shapeTree = candidate;
        }
        if (shapeTree == null) {
            return null;
        }
        Element current = shapeTree;
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            if (!part.startsWith("shape-")
                    || !isPositiveInteger(part.substring("shape-".length()))) {
                return null;
            }
            int ordinal;
            try {
                ordinal = Integer.parseInt(part.substring("shape-".length()));
            } catch (NumberFormatException exception) {
                return null;
            }
            List<Element> drawableChildren = drawableChildren(current);
            if (ordinal > drawableChildren.size()) {
                return null;
            }
            current = drawableChildren.get(ordinal - 1);
        }
        return current;
    }

    private static List<Element> drawableChildren(Element parent) {
        Set<String> drawableNames = Set.of("sp", "pic", "graphicFrame", "grpSp");
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && P_NS.equals(node.getNamespaceURI())
                    && drawableNames.contains(node.getLocalName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static boolean isPositiveInteger(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        try {
            return Integer.parseInt(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    static Element child(Element parent, String namespace, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && namespace.equals(node.getNamespaceURI())
                    && localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }

    static List<Element> descendants(Element parent, String namespace, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        List<Element> result = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            result.add((Element) nodes.item(index));
        }
        return result;
    }

    static String resolveTarget(String sourcePart, String target) {
        if (target == null || target.isBlank() || target.contains("\\") || target.indexOf('\u0000') >= 0
                || target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("unsafe relationship target");
        }
        String raw = target.startsWith("/") ? target.substring(1) : parent(sourcePart) + target;
        List<String> parts = new ArrayList<>();
        for (String part : raw.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException("relationship target escapes package root");
                }
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    static String relationshipSource(String relsPath) {
        if ("_rels/.rels".equals(relsPath)) {
            return "";
        }
        int marker = relsPath.indexOf("/_rels/");
        if (marker < 0 || !relsPath.endsWith(".rels")) {
            return relsPath;
        }
        return relsPath.substring(0, marker + 1)
                + relsPath.substring(marker + 7, relsPath.length() - 5);
    }

    static String parent(String part) {
        int slash = part.lastIndexOf('/');
        return slash < 0 ? "" : part.substring(0, slash + 1);
    }

    private String nextGeneratedSlidePath(int ordinal) {
        String base = "ppt/slides/a12-generated-slide-" + ordinal;
        String candidate = base + ".xml";
        int suffix = 2;
        while (entries.containsKey(candidate) || entries.containsKey(relsPath(candidate))) {
            candidate = base + "-" + suffix++ + ".xml";
        }
        return candidate;
    }

    private void replacePresentationSlides(List<String> targetSlides) throws IOException {
        Document presentation = document("ppt/presentation.xml");
        Document presentationRels = document("ppt/_rels/presentation.xml.rels");
        Element slideIdList = child(presentation.getDocumentElement(), P_NS, "sldIdLst");
        if (slideIdList == null) {
            slideIdList = presentation.createElementNS(P_NS, "p:sldIdLst");
            presentation.getDocumentElement().appendChild(slideIdList);
        }
        long nextSlideId = 1;
        for (Element slideId : elements(presentation, P_NS, "sldId")) {
            try {
                nextSlideId = Math.max(nextSlideId, Long.parseLong(slideId.getAttribute("id")) + 1);
            } catch (NumberFormatException ignored) {
                // A malformed existing ID will be rejected by the final gate;
                // generated IDs remain numeric and collision-free.
            }
            slideIdList.removeChild(slideId);
        }
        for (Element relationship : elements(presentationRels, REL_NS, "Relationship")) {
            if (relationship.getAttribute("Type").endsWith("/slide")) {
                presentationRels.getDocumentElement().removeChild(relationship);
            }
        }
        for (String targetSlide : targetSlides) {
            String relationshipId = nextRelationshipId(presentationRels);
            Element relationship = presentationRels.createElementNS(REL_NS, "Relationship");
            relationship.setAttribute("Id", relationshipId);
            relationship.setAttribute("Type",
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide");
            relationship.setAttribute("Target", relativeTarget("ppt/presentation.xml", targetSlide));
            presentationRels.getDocumentElement().appendChild(relationship);

            Element slideId = presentation.createElementNS(P_NS, "p:sldId");
            slideId.setAttribute("id", Long.toString(nextSlideId++));
            slideId.setAttributeNS(R_NS, "r:id", relationshipId);
            slideIdList.appendChild(slideId);
        }
        saveDocument("ppt/presentation.xml", presentation);
        saveDocument("ppt/_rels/presentation.xml.rels", presentationRels);
    }

    private static String nextRelationshipId(Document rels) {
        int max = 0;
        Set<String> ids = new HashSet<>();
        for (Element relationship : elements(rels, REL_NS, "Relationship")) {
            String id = relationship.getAttribute("Id");
            ids.add(id);
            if (id.startsWith("rId")) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(3)));
                } catch (NumberFormatException ignored) {
                    // Non-numeric relationship IDs do not participate in the
                    // numeric sequence but remain preserved.
                }
            }
        }
        String candidate;
        do {
            candidate = "rId" + (++max);
        } while (ids.contains(candidate));
        return candidate;
    }

    private static List<String> pathParts(String path) {
        return java.util.Arrays.stream(path.split("/"))
                .filter(part -> !part.isBlank())
                .toList();
    }

    static String localExtension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase() : "bin";
    }

    private static DocumentBuilderFactory secureFactory() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory;
        } catch (Exception exception) {
            throw new IllegalStateException("secure XML parser is unavailable", exception);
        }
    }

    private static void validateEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")
                || name.length() > 255 || name.endsWith("/") || name.indexOf('\u0000') >= 0
                || name.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.isWhitespace(codePoint))
                || name.chars().anyMatch(character -> ":*?<>|\"%#".indexOf(character) >= 0)
                || java.util.Arrays.stream(name.split("/"))
                .anyMatch(segment -> segment.isBlank() || ".".equals(segment) || "..".equals(segment)
                        || segment.endsWith(".") || segment.endsWith(" "))) {
            throw new IllegalArgumentException("unsafe package entry");
        }
    }
}
