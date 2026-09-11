package com.auvdidao.a12.pptengine.executor;

import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticImpact;
import com.auvdidao.a12.pptengine.contract.ContractTypes.DiagnosticSeverity;
import com.auvdidao.a12.pptengine.contract.ContractTypes.ObjectType;
import com.auvdidao.a12.pptengine.contract.ContractTypes.CompositionOperationType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** File-level OOXML gate; it never repairs a generated package. */
final class PptxBuildValidator {

    private static final String SLIDE_REL = "/slide";
    private static final String LAYOUT_REL = "/slideLayout";
    private static final String MASTER_REL = "/slideMaster";
    private static final String THEME_REL = "/theme";

    Result validate(
            Path artifact,
            CompositionModels.ComposedPresentationPlan plan,
            Set<String> appliedOperationIds,
            Set<String> skippedOperationIds,
            Map<String, String> assetPackageParts) {
        List<ExecutorModels.ExecutorDiagnostic> diagnostics = new ArrayList<>();
        PptxPackage pack;
        try {
            pack = PptxPackage.read(artifact);
        } catch (Exception exception) {
            diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_PACKAGE_INVALID,
                    "pptxBuildValidator.packageInvalid", Map.of("reason", "unreadable")));
            return new Result(diagnostics, 0, null, 0);
        }

        String artifactSha256;
        long artifactSize;
        try {
            artifactSha256 = sha256(artifact);
            artifactSize = java.nio.file.Files.size(artifact);
        } catch (Exception exception) {
            diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_CHECKSUM_MISMATCH,
                    "pptxBuildValidator.artifactIdentityInvalid", Map.of()));
            return new Result(diagnostics, 0, null, 0);
        }

        requireEntries(pack, diagnostics);
        List<String> slides = List.of();
        try {
            slides = pack.slidePaths();
        } catch (Exception exception) {
            diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                    "pptxBuildValidator.presentationRelationshipsInvalid", Map.of("reason", "slideOrder")));
        }
        if (slides.size() != plan.slides().size() || plan.originalSlideCount() != plan.slides().size()) {
            diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_SLIDE_COUNT_MISMATCH,
                    "pptxBuildValidator.slideCountMismatch", Map.of(
                            "packageCount", Integer.toString(slides.size()),
                            "planCount", Integer.toString(plan.slides().size()))));
        }

        validateRelationships(pack, diagnostics);
        validateContentTypes(pack, diagnostics);
        validateSlideClosure(pack, slides, diagnostics);
        validateNativeIds(pack, slides, diagnostics);
        validateOperationCoverage(plan, appliedOperationIds, skippedOperationIds, diagnostics);
        for (Map.Entry<String, String> asset : assetPackageParts.entrySet()) {
            if (!pack.entryNames().contains(asset.getValue())) {
                diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_OPERATION_NOT_COVERED,
                        "pptxBuildValidator.assetPartMissing", Map.of("assetRequirementId", asset.getKey())));
            }
        }
        return new Result(diagnostics, slides.size(), artifactSha256, artifactSize);
    }

    private void requireEntries(PptxPackage pack, List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        for (String required : List.of("[Content_Types].xml", "_rels/.rels",
                "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels")) {
            if (!pack.entryNames().contains(required)) {
                diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_PACKAGE_INVALID,
                        "pptxBuildValidator.requiredPartMissing", Map.of("part", required)));
            }
        }
    }

    private void validateRelationships(PptxPackage pack, List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        for (String relsPath : pack.entryNames().stream().filter(name -> name.endsWith(".rels")).sorted().toList()) {
            try {
                Document rels = pack.document(relsPath);
                Set<String> ids = new HashSet<>();
                Set<String> tuples = new HashSet<>();
                NodeList nodes = rels.getElementsByTagNameNS(PptxPackage.REL_NS, "Relationship");
                String source = PptxPackage.relationshipSource(relsPath);
                for (int index = 0; index < nodes.getLength(); index++) {
                    Element relationship = (Element) nodes.item(index);
                    String id = relationship.getAttribute("Id");
                    String target = relationship.getAttribute("Target");
                    String type = relationship.getAttribute("Type");
                    if (!ids.add(id) || !tuples.add(type + "\u0000" + target)) {
                        diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                                "pptxBuildValidator.duplicateRelationship", Map.of("part", relsPath)));
                    }
                    if (!"External".equals(relationship.getAttribute("TargetMode"))
                            && !pack.entryNames().contains(PptxPackage.resolveTarget(source, target))) {
                        diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                                "pptxBuildValidator.danglingRelationship", Map.of("part", relsPath)));
                    }
                }
            } catch (Exception exception) {
                diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                        "pptxBuildValidator.relationshipPartInvalid", Map.of("part", relsPath)));
            }
        }
    }

    void validateContentTypes(PptxPackage pack, List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        try {
            Document document = pack.document("[Content_Types].xml");
            Map<String, String> defaults = new HashMap<>();
            Map<String, String> overrides = new HashMap<>();
            for (Element element : pack.elements(document, PptxPackage.CT_NS, "Default")) {
                String extension = element.getAttribute("Extension").trim().toLowerCase(Locale.ROOT);
                String contentType = element.getAttribute("ContentType").trim();
                if (extension.isBlank() || contentType.isBlank() || defaults.putIfAbsent(extension, contentType) != null) {
                    diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_CONTENT_TYPES_INVALID,
                            "pptxBuildValidator.duplicateDefaultContentType", Map.of()));
                }
            }
            for (Element element : pack.elements(document, PptxPackage.CT_NS, "Override")) {
                String part = normalizePartName(element.getAttribute("PartName"));
                String contentType = element.getAttribute("ContentType").trim();
                if (part.isBlank() || contentType.isBlank() || overrides.putIfAbsent(part, contentType) != null) {
                    diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_CONTENT_TYPES_INVALID,
                            "pptxBuildValidator.duplicateOverrideContentType", Map.of()));
                }
            }
            for (String part : pack.entryNames()) {
                if ("[Content_Types].xml".equals(part) || part.endsWith("/")) {
                    continue;
                }
                if (!overrides.containsKey(part) && !defaults.containsKey(PptxPackage.localExtension(part))) {
                    diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_CONTENT_TYPES_INVALID,
                            "pptxBuildValidator.contentTypeMissing", Map.of("part", part)));
                }
            }
            for (String part : overrides.keySet()) {
                if (!pack.entryNames().contains(part)) {
                    diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_CONTENT_TYPES_INVALID,
                            "pptxBuildValidator.overridePartMissing", Map.of("part", part)));
                }
            }
        } catch (Exception exception) {
            diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_CONTENT_TYPES_INVALID,
                    "pptxBuildValidator.contentTypesInvalid", Map.of("reason", "unreadable")));
        }
    }

    private void validateSlideClosure(
            PptxPackage pack, List<String> slides, List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        for (String slide : slides) {
            try {
                Document rels = pack.document(pack.relsPath(slide));
                boolean hasLayout = false;
                for (Element relationship : pack.elements(rels, PptxPackage.REL_NS, "Relationship")) {
                    String type = relationship.getAttribute("Type");
                    String target = PptxPackage.resolveTarget(slide, relationship.getAttribute("Target"));
                    if (type.endsWith(SLIDE_REL) && !pack.entryNames().contains(target)) {
                        diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                                "pptxBuildValidator.slideRelationshipInvalid", Map.of("part", slide)));
                    }
                    if (type.endsWith(LAYOUT_REL)) {
                        hasLayout = true;
                        validateLayoutClosure(pack, target, diagnostics);
                    }
                }
                if (!hasLayout) {
                    diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                            "pptxBuildValidator.slideLayoutRelationshipMissing", Map.of("part", slide)));
                }
            } catch (Exception exception) {
                diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                        "pptxBuildValidator.slideRelsMissing", Map.of("part", slide)));
            }
        }
    }

    private void validateLayoutClosure(
            PptxPackage pack, String part, List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        if (!pack.entryNames().contains(part)) {
            diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                    "pptxBuildValidator.relatedPartMissing", Map.of("part", part)));
            return;
        }
        try {
            Document rels = pack.document(pack.relsPath(part));
            boolean hasMaster = false;
            for (Element relationship : pack.elements(rels, PptxPackage.REL_NS, "Relationship")) {
                String type = relationship.getAttribute("Type");
                String target = PptxPackage.resolveTarget(part, relationship.getAttribute("Target"));
                if (type.endsWith(MASTER_REL)) {
                    hasMaster = true;
                    validateMasterClosure(pack, target, diagnostics);
                }
            }
            if (!hasMaster) {
                diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                        "pptxBuildValidator.layoutMasterRelationshipMissing", Map.of("part", part)));
            }
        } catch (Exception exception) {
            diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                    "pptxBuildValidator.relatedRelsMissing", Map.of("part", part)));
        }
    }

    private void validateMasterClosure(
            PptxPackage pack, String part, List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        if (!pack.entryNames().contains(part)) {
            diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                    "pptxBuildValidator.masterMissing", Map.of("part", part)));
            return;
        }
        try {
            Document rels = pack.document(pack.relsPath(part));
            boolean hasTheme = false;
            for (Element relationship : pack.elements(rels, PptxPackage.REL_NS, "Relationship")) {
                if (relationship.getAttribute("Type").endsWith(THEME_REL)) {
                    hasTheme = true;
                    String target = PptxPackage.resolveTarget(part, relationship.getAttribute("Target"));
                    if (!pack.entryNames().contains(target)) {
                        diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                                "pptxBuildValidator.themeMissing", Map.of("part", target)));
                    }
                }
            }
            if (!hasTheme) {
                diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                        "pptxBuildValidator.masterThemeRelationshipMissing", Map.of("part", part)));
            }
        } catch (Exception exception) {
            diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_RELATIONSHIP_INVALID,
                    "pptxBuildValidator.masterRelsMissing", Map.of("part", part)));
        }
    }

    private void validateNativeIds(PptxPackage pack, List<String> slides, List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        for (String slide : slides) {
            try {
                Set<String> ids = new HashSet<>();
                for (Element cNvPr : pack.elements(pack.document(slide), "*", "cNvPr")) {
                    if (!ids.add(cNvPr.getAttribute("id"))) {
                        diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_NATIVE_REFERENCE_INVALID,
                                "pptxBuildValidator.duplicateNativeObjectId", Map.of("part", slide)));
                    }
                }
            } catch (Exception exception) {
                diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_NATIVE_REFERENCE_INVALID,
                        "pptxBuildValidator.nativeReferenceInvalid", Map.of("part", slide)));
            }
        }
    }

    private void validateOperationCoverage(
            CompositionModels.ComposedPresentationPlan plan,
            Set<String> applied,
            Set<String> skipped,
            List<ExecutorModels.ExecutorDiagnostic> diagnostics) {
        Set<String> accounted = new HashSet<>(applied);
        accounted.addAll(skipped);
        for (CompositionModels.ComposedSlidePlan slide : plan.slides()) {
            for (CompositionModels.CompositionOperation operation : slide.operations()) {
                if (!accounted.contains(operation.operationId())) {
                    diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_OPERATION_NOT_COVERED,
                            "pptxBuildValidator.operationNotCovered", Map.of("operationId", operation.operationId())));
                }
                if (operation.operationType() == CompositionOperationType.USE_OR_CLONE_COMPONENT_OBJECT
                        && operation.nativeObjectReference() != null
                        && operation.nativeObjectReference().objectType() == ObjectType.UNKNOWN) {
                    diagnostics.add(error(ContractTypes.DiagnosticCode.PPTX_NATIVE_REFERENCE_INVALID,
                            "pptxBuildValidator.unknownComponentObjectType", Map.of("operationId", operation.operationId())));
                }
            }
        }
    }

    private ExecutorModels.ExecutorDiagnostic error(
            ContractTypes.DiagnosticCode code, String messageKey, Map<String, String> details) {
        return new ExecutorModels.ExecutorDiagnostic(
                code.name(), DiagnosticSeverity.ERROR, DiagnosticImpact.JOB_BLOCKING,
                null, null, null, null, null, null, messageKey, details);
    }

    record Result(List<ExecutorModels.ExecutorDiagnostic> diagnostics, int slideCount,
                  String artifactSha256, long artifactSize) {
        Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private String normalizePartName(String value) {
        if (value == null || !value.startsWith("/") || value.contains("\\")
                || value.length() > 255 || value.indexOf('\u0000') >= 0) {
            return "";
        }
        String normalized = value.substring(1);
        if (normalized.isBlank() || normalized.endsWith("/") || normalized.contains("//")
                || normalized.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.isWhitespace(codePoint))
                || normalized.chars().anyMatch(character -> ":*?<>|\"%#".indexOf(character) >= 0)
                || java.util.Arrays.stream(normalized.split("/"))
                .anyMatch(part -> part.isBlank() || ".".equals(part) || "..".equals(part)
                        || part.endsWith(".") || part.endsWith(" "))) {
            return "";
        }
        return normalized;
    }

    private String sha256(Path path) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = java.nio.file.Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
