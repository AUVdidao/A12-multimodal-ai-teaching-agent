package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.agent.model.ModelCredentialResolver;
import com.auvdidao.a12teachingagent.agent.model.ModelExecutionContext;
import com.auvdidao.a12teachingagent.agent.model.ModelGateway;
import com.auvdidao.a12teachingagent.agent.model.ModelImageRef;
import com.auvdidao.a12teachingagent.agent.model.ModelMessage;
import com.auvdidao.a12teachingagent.agent.model.ModelProvider;
import com.auvdidao.a12teachingagent.agent.model.ModelRequest;
import com.auvdidao.a12teachingagent.agent.model.MultimodalModelResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Configurable real-provider analyzer boundary. Disabled by default: a missing
 * renderer, preview URL, credential, or multimodal provider remains blocked.
 * The fixture-only MockModelGateway is deliberately not injected here.
 */
@Component
public class UnavailableTemplateAnalyzerAdapter implements TemplateAnalyzer {
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "displayName", "pageRoles", "semanticLayouts", "imageCapability",
            "tableCapability", "chartCapability", "fixedBrandAreas", "limitations"
    );
    private static final Set<String> FORBIDDEN_NAME_PARTS = Set.of(
            "native", "shape", "group", "slot", "coordinate", "ooxml", "xml"
    );

    private final ObjectMapper objectMapper;
    private final ModelGateway modelGateway;
    private final ModelCredentialResolver credentialResolver;
    private final LessonForgeModelExecutionClient modelExecutionClient;
    private final TemplatePreviewImageService previewImageService;
    private final boolean enabled;
    private final String previewBaseUrl;
    private final String provider;
    private final String model;
    private final int maxCompletionTokens;
    private final long timeoutMs;
    private final boolean fixtureEnabled;
    private final String activeProfile;

    @Autowired
    public UnavailableTemplateAnalyzerAdapter(
            ObjectMapper objectMapper,
            LessonForgeModelExecutionClient modelExecutionClient,
            TemplatePreviewImageService previewImageService,
            @Value("${a12.template.analyzer.enabled:false}") boolean enabled,
            @Value("${a12.template.analyzer.max-completion-tokens:1600}") int maxCompletionTokens,
            @Value("${a12.template.analyzer.timeout-ms:90000}") long timeoutMs,
            @Value("${a12.template.analyzer.fixture-enabled:false}") boolean fixtureEnabled,
            @Value("${spring.profiles.active:}") String activeProfile
    ) {
        this.objectMapper = objectMapper;
        this.modelGateway = null;
        this.credentialResolver = null;
        this.modelExecutionClient = modelExecutionClient;
        this.previewImageService = previewImageService;
        this.enabled = enabled;
        this.previewBaseUrl = "";
        this.provider = "";
        this.model = "";
        this.maxCompletionTokens = maxCompletionTokens;
        this.timeoutMs = timeoutMs;
        this.fixtureEnabled = fixtureEnabled;
        this.activeProfile = activeProfile == null ? "" : activeProfile.strip();
    }

    /** Compatibility constructor retained for focused preview-source tests. */
    public UnavailableTemplateAnalyzerAdapter(
            ObjectMapper objectMapper,
            ModelGateway modelGateway,
            ModelCredentialResolver credentialResolver,
            boolean enabled,
            String previewBaseUrl,
            String provider,
            String model,
            int maxCompletionTokens,
            long timeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.modelGateway = modelGateway;
        this.credentialResolver = credentialResolver;
        this.modelExecutionClient = null;
        this.previewImageService = null;
        this.enabled = enabled;
        this.previewBaseUrl = previewBaseUrl == null ? "" : previewBaseUrl.strip();
        this.provider = provider == null ? "" : provider.strip();
        this.model = model == null ? "" : model.strip();
        this.maxCompletionTokens = maxCompletionTokens;
        this.timeoutMs = timeoutMs;
        this.fixtureEnabled = false;
        this.activeProfile = "";
    }

    /** Compatibility constructor retained for development-fixture tests. */
    public UnavailableTemplateAnalyzerAdapter(
            ObjectMapper objectMapper,
            ModelGateway modelGateway,
            ModelCredentialResolver credentialResolver,
            boolean enabled,
            String previewBaseUrl,
            String provider,
            String model,
            int maxCompletionTokens,
            long timeoutMs,
            boolean fixtureEnabled,
            String activeProfile
    ) {
        this.objectMapper = objectMapper;
        this.modelGateway = modelGateway;
        this.credentialResolver = credentialResolver;
        this.modelExecutionClient = null;
        this.previewImageService = null;
        this.enabled = enabled;
        this.previewBaseUrl = previewBaseUrl == null ? "" : previewBaseUrl.strip();
        this.provider = provider == null ? "" : provider.strip();
        this.model = model == null ? "" : model.strip();
        this.maxCompletionTokens = maxCompletionTokens;
        this.timeoutMs = timeoutMs;
        this.fixtureEnabled = fixtureEnabled;
        this.activeProfile = activeProfile == null ? "" : activeProfile.strip();
    }

    @Override
    public AnalysisResult analyze(JsonNode structuralSnapshot, String previewReference) {
        return AnalysisResult.notImplemented(
                "multimodal-analyzer-not-configured",
                "多模态模板分析必须通过带身份绑定的生产入口调用。"
        );
    }

    @Override
    public AnalysisResult analyze(AnalysisRequest request) {
        if (fixtureEnabled && "dev".equalsIgnoreCase(activeProfile)) {
            return analyzeDevelopmentFixture(request);
        }
        if (!enabled) {
            return AnalysisResult.notImplemented(
                    "multimodal-analyzer-not-configured",
                "多模态模板分析适配器尚未配置；未生成 Candidate，也未伪造分析通过。"
            );
        }
        if (modelExecutionClient != null) {
            return analyzeThroughLessonForgeBridge(request);
        }
        if (!"KIMI".equalsIgnoreCase(provider) || model.isBlank() || maxCompletionTokens < 1
                || maxCompletionTokens > ModelRequest.MAX_COMPLETION_TOKENS
                || timeoutMs < 1 || timeoutMs > ModelRequest.MAX_TIMEOUT_MS) {
            return AnalysisResult.notImplemented("multimodal-analyzer-configuration-invalid",
                    "多模态 Provider/model/预算配置无效，已 fail closed。" );
        }

        String imageUri = resolvePreviewUri(request.previewReference());
        if (imageUri == null) {
            return AnalysisResult.notImplemented("multimodal-analyzer-preview-unavailable",
                    "渲染结果没有可供 Provider 访问的受控 HTTP(S) 预览引用。" );
        }
        ModelExecutionContext context = new ModelExecutionContext(
                request.projectId(), Long.toString(request.ownerUserId()), "TEACHER",
                request.analysisRunId(), request.analysisRunId(), "template-analyzer",
                timeoutMs, Map.of("sourceSha256", request.sourceSha256(), "sourceVersionId", Long.toString(request.sourceVersionId()))
        );
        // Resolve through the existing credential service before any provider call.
        credentialResolver.resolve(context, ModelProvider.KIMI);

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(buildBoundedAnalyzerSnapshot(request.structuralSnapshot()));
        } catch (Exception exception) {
            return AnalysisResult.notImplemented("multimodal-analyzer-input-invalid",
                    "结构快照无法序列化，已 fail closed。" );
        }
        if (snapshotJson.length() > 50_000) {
            return AnalysisResult.notImplemented("multimodal-analyzer-input-too-large",
                    "结构快照超过 Analyzer 输入上限，已 fail closed。" );
        }
        String inputSha256 = digest(request.sourceSha256() + "\n" + request.projectId() + "\n"
                + request.sourceVersionId() + "\n" + request.ownerUserId() + "\n"
                + request.previewReference() + "\n" + snapshotJson);
        String instruction = "Analyze this rendered teaching-template preview and structural snapshot. "
                + "Return JSON only with exactly the Profile Candidate fields: " + REQUIRED_FIELDS
                + ". Use the bounded CandidateProfileRequest schema: displayName is a short string; "
                + "pageRoles has at most 20 short semantic role strings; semanticLayouts has at most 30 "
                + "objects with name, optional description, minCapacity and maxCapacity; each capability is "
                + "null or an object with supported, optional minCount/maxCount and notes; fixedBrandAreas "
                + "has at most 30 short strings; limitations has at most 50 short strings. Do not add any "
                + "other fields or use native PowerPoint object names, shape/group/slot IDs, coordinates, or OOXML.\n"
                + "sourceSha256=" + request.sourceSha256() + ", inputSha256=" + inputSha256 + "\n"
                + "snapshot=" + snapshotJson;
        ModelRequest modelRequest = new ModelRequest(
                "You are a constrained template-analysis service. Produce a bounded semantic candidate profile only.",
                List.of(new ModelMessage(ModelMessage.Role.USER, instruction,
                        List.of(new ModelImageRef(imageUri, "application/pdf")))),
                model, maxCompletionTokens, 0.0, timeoutMs,
                Map.of("sourceSha256", request.sourceSha256(), "inputSha256", inputSha256),
                List.of("template-profile-candidate", "semantic-only")
        );
        MultimodalModelResult result = modelGateway.completeMultimodal(context, modelRequest);
        JsonNode candidate = normalizeExecutionRoles(normalizeCandidate(parseJson(result.content())));
        if (!isSemanticCandidate(candidate)) {
            return new AnalysisResult(false, "multimodal-analyzer-integrity-gate", null,
                    "ANALYZER_OUTPUT_INVALID: candidate is not a bounded semantic profile", null, null,
                    request.analysisRunId(), inputSha256, digest(result.content()));
        }
        return new AnalysisResult(true, "kimi-multimodal", candidate, null,
                result.provider().name(), result.model(), request.analysisRunId(), inputSha256,
                digest(result.content()));
    }

    private AnalysisResult analyzeThroughLessonForgeBridge(AnalysisRequest request) {
        if (request.missionId() == null || request.missionFileId() == null
                || request.renderedSlideSetId() == null || request.processingRunId() == null) {
            return AnalysisResult.notImplemented(
                    "multimodal-analyzer-mission-binding-unavailable",
                    "多模态分析缺少 LessonForge Mission、MissionFile 或渲染运行绑定；已 fail closed。"
            );
        }
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(buildBoundedAnalyzerSnapshot(request.structuralSnapshot()));
        } catch (Exception exception) {
            return AnalysisResult.notImplemented("multimodal-analyzer-input-invalid",
                    "结构快照无法序列化，已 fail closed。" );
        }
        if (snapshotJson.length() > 50_000) {
            return AnalysisResult.notImplemented("multimodal-analyzer-input-too-large",
                    "结构快照超过 Analyzer 输入上限，已 fail closed。" );
        }

        TemplatePreviewImageService.PreviewImage preview = previewImageService.create(request.previewReference());
        try {
            String inputSha256 = digest(request.sourceSha256() + "\n" + request.projectId() + "\n"
                    + request.sourceVersionId() + "\n" + request.ownerUserId() + "\n"
                    + request.missionId() + "\n" + request.missionFileId() + "\n"
                    + request.renderedSlideSetId() + "\n" + request.processingRunId() + "\n"
                    + request.previewReference() + "\n" + preview.sha256() + "\n" + snapshotJson);
            String instruction = "Analyze this rendered teaching-template preview and structural snapshot. "
                    + "Return JSON only with exactly the Profile Candidate fields: " + REQUIRED_FIELDS
                    + ". Use the bounded CandidateProfileRequest schema: displayName is a short string; "
                    + "pageRoles has at most 20 short semantic role strings; semanticLayouts has at most 30 "
                    + "objects with name, optional description, minCapacity and maxCapacity; each capability is "
                    + "null or an object with supported, optional minCount/maxCount and notes; fixedBrandAreas "
                    + "has at most 30 short strings; limitations has at most 50 short strings. Do not add any "
                    + "other fields or use native PowerPoint object names, shape/group/slot IDs, coordinates, or OOXML.\n"
                    + "sourceSha256=" + request.sourceSha256() + ", inputSha256=" + inputSha256 + "\n"
                    + "snapshot=" + snapshotJson;
            String promptSha256 = digest(instruction);
            String nonce = java.util.UUID.randomUUID().toString();
            LessonForgeModelExecutionClient.LeaseResponse lease = modelExecutionClient.createLease(
                    new LessonForgeModelExecutionClient.LeaseRequest(
                            request.ownerUserId(), request.missionId(), request.missionFileId(),
                            "TEMPLATE_ANALYZER", request.analysisRunId(), request.sourceVersionId(),
                            request.renderedSlideSetId(), request.processingRunId(), request.sourceSha256(),
                            inputSha256, promptSha256, preview.reference(), preview.sha256(), preview.sizeBytes(),
                            preview.mediaType(), nonce));
            if (!nonce.equals(lease.nonce()) || !request.analysisRunId().equals(lease.analysisRunId())
                    || lease.modelConnectionId() == null || lease.modelConnectionId() <= 0) {
                throw new LessonForgeModelExecutionClient.ModelExecutionException("MODEL_EXECUTION_LEASE_RESPONSE_INVALID");
            }
            LessonForgeModelExecutionClient.ExecutionResponse result = modelExecutionClient.execute(
                    new LessonForgeModelExecutionClient.ExecutionRequest(
                            lease.leaseId(), lease.nonce(), instruction, inputSha256, promptSha256,
                            maxCompletionTokens, timeoutMs));
            if (!request.analysisRunId().equals(result.analysisRunId())
                    || !inputSha256.equalsIgnoreCase(result.inputSha256())
                    || !preview.sha256().equalsIgnoreCase(result.previewSha256())
                    || result.modelConnectionId() == null || !lease.modelConnectionId().equals(result.modelConnectionId())) {
                throw new LessonForgeModelExecutionClient.ModelExecutionException("MODEL_EXECUTION_RESULT_BINDING_INVALID");
            }
            JsonNode candidate = normalizeExecutionRoles(normalizeCandidate(parseJson(result.content())));
            if (!isSemanticCandidate(candidate)) {
                return new AnalysisResult(false, "multimodal-analyzer-integrity-gate", null,
                        "ANALYZER_OUTPUT_INVALID: candidate is not a bounded semantic profile", null, null,
                        request.analysisRunId(), inputSha256, digest(result.content()));
            }
            return new AnalysisResult(true, "lessonforge-go-vision", candidate, null,
                    result.provider(), result.modelId(), request.analysisRunId(), inputSha256,
                    digest(result.content()));
        } finally {
            previewImageService.delete(preview);
        }
    }

    /**
     * Keep the multimodal prompt bounded without exposing native object
     * records. The rendered preview remains the visual source of truth; this
     * summary only supplies stable page-level structure and content counts.
     */
    private ObjectNode buildBoundedAnalyzerSnapshot(JsonNode snapshot) {
        ObjectNode bounded = objectMapper.createObjectNode();
        if (snapshot == null || !snapshot.isObject()) {
            return bounded;
        }
        copyNumber(snapshot, bounded, "slideCount");
        copyNumber(snapshot, bounded, "pageWidth");
        copyNumber(snapshot, bounded, "pageHeight");

        ArrayNode boundedSlides = bounded.putArray("slides");
        JsonNode slides = snapshot.path("slides");
        if (!slides.isArray()) {
            return bounded;
        }
        for (JsonNode slide : slides) {
            if (!slide.isObject()) {
                continue;
            }
            ObjectNode boundedSlide = boundedSlides.addObject();
            copyNumber(slide, boundedSlide, "pageNumber");
            copyNumber(slide, boundedSlide, "shapeCount");

            ObjectNode shapeTypes = boundedSlide.putObject("shapeTypeCounts");
            ObjectNode contentCounts = boundedSlide.putObject("contentCounts");
            JsonNode shapes = slide.path("shapes");
            if (!shapes.isArray()) {
                continue;
            }
            for (JsonNode shape : shapes) {
                if (!shape.isObject()) {
                    continue;
                }
                String type = shape.path("type").asText("");
                if (!type.isBlank()) {
                    shapeTypes.put(type, shapeTypes.path(type).asInt(0) + 1);
                }
                countBoolean(shape, contentCounts, "text", "textShapes");
                countBoolean(shape, contentCounts, "textContent", "textContentShapes");
                countBoolean(shape, contentCounts, "picture", "pictures");
                countBoolean(shape, contentCounts, "table", "tables");
                countBoolean(shape, contentCounts, "chart", "charts");
                countBoolean(shape, contentCounts, "placeholder", "placeholders");
            }
        }
        return bounded;
    }

    private void copyNumber(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isNumber()) {
            target.set(field, value);
        }
    }

    private void countBoolean(JsonNode source, ObjectNode target, String sourceField, String targetField) {
        if (source.path(sourceField).asBoolean(false)) {
            target.put(targetField, target.path(targetField).asInt(0) + 1);
        }
    }

    /**
     * Deterministic development seam: derive semantic-only fields from the
     * verified Parser snapshot. It deliberately emits a non-production
     * provider/model marker and never reads native object identifiers.
     */
    private AnalysisResult analyzeDevelopmentFixture(AnalysisRequest request) {
        JsonNode snapshot = request.structuralSnapshot();
        JsonNode slides = snapshot.path("slides");
        if (!slides.isArray() || slides.isEmpty() || snapshot.path("slideCount").asInt(-1) != slides.size()) {
            return AnalysisResult.notImplemented("development-analyzer-fixture-invalid-input",
                    "DEVELOPMENT_FIXTURE: Parser snapshot is incomplete; no Candidate was created.");
        }
        ObjectNode candidate = objectMapper.createObjectNode();
        candidate.put("displayName", "Development semantic profile fixture");
        ArrayNode pageRoles = candidate.putArray("pageRoles");
        // pageRoles is a bounded semantic vocabulary, not a per-slide index.
        // The per-slide count remains bound to the verified Parser snapshot;
        // this summary must stay within CandidateProfileRequest's max of 20.
        pageRoles.add("COVER");
        if (slides.size() > 1) pageRoles.add("CONTENT");
        ArrayNode layouts = candidate.putArray("semanticLayouts");
        ObjectNode layout = layouts.addObject();
        layout.put("name", "CONTENT");
        layout.put("description", "DEVELOPMENT_FIXTURE semantic content layout; no native placement data.");
        layout.put("minCapacity", 1);
        layout.put("maxCapacity", 6);
        candidate.putNull("imageCapability");
        candidate.putNull("tableCapability");
        candidate.putNull("chartCapability");
        candidate.putArray("fixedBrandAreas");
        ArrayNode limitations = candidate.putArray("limitations");
        limitations.add("DEVELOPMENT_FIXTURE");
        limitations.add("AUTO_RENDERER=NOT_READY");
        limitations.add("Semantic-only preview; not Office-rendered and not visually approved.");

        String canonical = TemplateChecksum.canonicalJson(objectMapper, candidate);
        String inputSha256 = digest(request.sourceSha256() + "\n" + request.projectId() + "\n"
                + request.sourceVersionId() + "\n" + request.ownerUserId() + "\n"
                + request.analysisRunId() + "\n" + TemplateChecksum.canonicalJson(objectMapper, snapshot));
        return new AnalysisResult(true, "development-analyzer-fixture", candidate,
                "DEVELOPMENT_FIXTURE; AUTO_RENDERER=NOT_READY; synthetic semantic Candidate only.",
                "DEVELOPMENT_FIXTURE", "semantic-profile-fixture-v1", request.analysisRunId(),
                inputSha256, digest(canonical));
    }

    private String resolvePreviewUri(String previewReference) {
        try {
            if (previewReference == null || previewReference.isBlank()
                    || previewReference.length() > 512
                    || previewReference.chars().anyMatch(Character::isISOControl)
                    || previewReference.contains("\\")
                    || previewReference.contains("..")
                    || previewReference.contains("?")
                    || previewReference.contains("#")
                    || previewReference.matches("(?i).*%2e|.*%2f|.*%5c.*")
                    || !previewReference.matches("(?i)^template-renders/[a-z0-9][a-z0-9._-]{0,239}\\.(pdf|png|jpe?g)$")) {
                return null;
            }
            URI parsed = URI.create(previewReference);
            // Only controlled relative references are accepted. Absolute HTTP(S)
            // values are never trusted merely because they have a host.
            if (parsed.isAbsolute() || parsed.getHost() != null || parsed.getRawQuery() != null
                    || parsed.getRawFragment() != null || previewBaseUrl.isBlank()) return null;
            URI base = URI.create(previewBaseUrl.endsWith("/") ? previewBaseUrl : previewBaseUrl + "/");
            if (!isAllowedPreviewBase(base) || previewReference.startsWith("/")) return null;
            URI resolved = base.resolve(previewReference);
            String basePath = base.getPath() == null ? "/" : base.getPath();
            if (!basePath.endsWith("/")) basePath += "/";
            if (!isHttp(resolved) || !sameAuthority(base, resolved)
                    || resolved.getPath() == null || !resolved.getPath().startsWith(basePath)
                    || resolved.getRawQuery() != null || resolved.getRawFragment() != null) return null;
            return resolved.toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isAllowedPreviewBase(URI uri) {
        if (!isHttp(uri) || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return false;
        }
        return "https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme()) &&
                ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                        || "[::1]".equalsIgnoreCase(uri.getHost())));
    }

    private boolean sameAuthority(URI left, URI right) {
        int leftPort = left.getPort() < 0 ? defaultPort(left) : left.getPort();
        int rightPort = right.getPort() < 0 ? defaultPort(right) : right.getPort();
        return left.getHost().equalsIgnoreCase(right.getHost()) && leftPort == rightPort
                && left.getUserInfo() == null && right.getUserInfo() == null;
    }

    private int defaultPort(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean isHttp(URI uri) {
        return uri != null && uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private JsonNode parseJson(String content) {
        try {
            String value = content == null ? "" : content.strip();
            if (value.startsWith("```") && value.endsWith("```")) {
                int firstLine = value.indexOf('\n');
                value = firstLine >= 0 ? value.substring(firstLine + 1, value.length() - 3).strip() : "";
            }
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean isSemanticCandidate(JsonNode candidate) {
        if (candidate == null || !candidate.isObject() || !candidate.fieldNames().hasNext()) return false;
        if (candidate.size() != REQUIRED_FIELDS.size()) return false;
        for (String field : REQUIRED_FIELDS) if (!candidate.has(field)) return false;
        if (containsForbiddenName(candidate)) return false;
        if (!candidate.path("displayName").isTextual()
                || candidate.path("displayName").asText().isBlank()
                || candidate.path("displayName").asText().length() > 200) return false;
        if (!boundedStringArray(candidate.path("pageRoles"), 20, 80)
                || !boundedStringArray(candidate.path("fixedBrandAreas"), 30, 120)
                || !boundedStringArray(candidate.path("limitations"), 50, 300)) return false;
        JsonNode layouts = candidate.path("semanticLayouts");
        if (!layouts.isArray() || layouts.size() > 30) return false;
        for (JsonNode layout : layouts) {
            if (!layout.isObject() || !layout.path("name").isTextual()
                    || layout.path("name").asText().isBlank() || layout.path("name").asText().length() > 120
                    || (layout.has("description") && !layout.path("description").isNull()
                    && (!layout.path("description").isTextual() || layout.path("description").asText().length() > 500))
                    || !nonNegativeInteger(layout, "minCapacity") || !nonNegativeInteger(layout, "maxCapacity")) return false;
        }
        return validCapability(candidate.path("imageCapability"))
                && validCapability(candidate.path("tableCapability"))
                && validCapability(candidate.path("chartCapability"));
    }

    private boolean boundedStringArray(JsonNode node, int maxItems, int maxLength) {
        if (!node.isArray() || node.size() > maxItems) return false;
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) return false;
        }
        return true;
    }

    private boolean nonNegativeInteger(JsonNode node, String field) {
        return !node.has(field) || (node.path(field).isIntegralNumber() && node.path(field).asLong() >= 0);
    }

    private boolean validCapability(JsonNode node) {
        if (node.isNull()) return true;
        if (!node.isObject() || !node.path("supported").isBoolean()
                || !nonNegativeInteger(node, "minCount") || !nonNegativeInteger(node, "maxCount")) return false;
        return !node.has("notes") || node.path("notes").isNull()
                || (node.path("notes").isTextual() && node.path("notes").asText().length() <= 300);
    }

    /**
     * Keep compatibility with the earlier semantic-only provider vocabulary.
     * The normalized result is still validated against the persisted Profile
     * DTO contract before it can become an Analyzer Candidate.
     */
    private JsonNode normalizeCandidate(JsonNode candidate) {
        if (candidate == null || isSemanticCandidate(candidate)
                || !candidate.isObject() || containsForbiddenName(candidate)) {
            return candidate;
        }
        boolean recognized = candidate.has("displayName") || candidate.has("pageRoles")
                || candidate.has("semanticLayouts") || candidate.has("semanticRoles")
                || candidate.has("layout") || candidate.has("source");
        if (!recognized) return candidate;

        ObjectNode normalized = objectMapper.createObjectNode();
        String displayName = candidate.path("displayName").asText("").strip();
        if (displayName.isBlank()) displayName = candidate.path("source").asText("").strip();
        normalized.put("displayName", displayName.isBlank()
                ? "Analyzed teaching template" : displayName.substring(0, Math.min(200, displayName.length())));

        ArrayNode pageRoles = normalized.putArray("pageRoles");
        addTextValues(candidate.path("pageRoles"), pageRoles, 20, 80);
        JsonNode semanticRoles = candidate.path("semanticRoles");
        if (pageRoles.isEmpty() && semanticRoles.isObject()) {
            semanticRoles.fieldNames().forEachRemaining(name -> {
                if (pageRoles.size() < 20) pageRoles.add(name.toUpperCase(Locale.ROOT));
            });
        } else if (pageRoles.isEmpty()) {
            addTextValues(semanticRoles, pageRoles, 20, 80);
        }
        if (pageRoles.isEmpty()) pageRoles.add("CONTENT");

        ArrayNode layouts = normalized.putArray("semanticLayouts");
        JsonNode rawLayouts = candidate.path("semanticLayouts");
        if (rawLayouts.isArray()) {
            for (JsonNode rawLayout : rawLayouts) {
                if (layouts.size() >= 30) break;
                ObjectNode layout = layouts.addObject();
                String name = rawLayout.isTextual() ? rawLayout.asText().strip() : rawLayout.path("name").asText("").strip();
                if (name.isBlank()) {
                    layouts.remove(layouts.size() - 1);
                    continue;
                }
                layout.put("name", name.substring(0, Math.min(120, name.length())));
                String description = rawLayout.isTextual() ? "" : rawLayout.path("description").asText("").strip();
                layout.put("description", description.substring(0, Math.min(500, description.length())));
                layout.put("minCapacity", nonNegativeCapacity(rawLayout, "minCapacity", 1));
                layout.put("maxCapacity", nonNegativeCapacity(rawLayout, "maxCapacity", 20));
            }
        }
        if (layouts.isEmpty()) {
            ObjectNode layout = layouts.addObject();
            layout.put("name", "CONTENT");
            String description = candidate.path("layout").asText("Inferred semantic layout").strip();
            layout.put("description", description.substring(0, Math.min(500, description.length())));
            layout.put("minCapacity", 1);
            layout.put("maxCapacity", 20);
        }
        normalized.set("imageCapability", normalizedCapability(candidate.path("imageCapability"), candidate.path("imagePolicy")));
        normalized.set("tableCapability", normalizedCapability(candidate.path("tableCapability"), candidate.path("tablePolicy")));
        normalized.set("chartCapability", normalizedCapability(candidate.path("chartCapability"), candidate.path("chartPolicy")));

        ArrayNode brandAreas = normalized.putArray("fixedBrandAreas");
        addTextValues(candidate.path("fixedBrandAreas"), brandAreas, 30, 120);
        addTextValue(candidate.path("fixedBrand"), brandAreas, 30, 120);
        ArrayNode limitations = normalized.putArray("limitations");
        addTextValues(candidate.path("limitations"), limitations, 50, 300);
        return normalized;
    }

    private void addTextValues(JsonNode source, ArrayNode target, int maxItems, int maxLength) {
        if (source == null || source.isMissingNode() || source.isNull()) return;
        if (source.isArray()) {
            for (JsonNode value : source) addTextValue(value, target, maxItems, maxLength);
        } else {
            addTextValue(source, target, maxItems, maxLength);
        }
    }

    private void addTextValue(JsonNode value, ArrayNode target, int maxItems, int maxLength) {
        if (target.size() >= maxItems || value == null || !value.isTextual() || value.asText().isBlank()) return;
        String text = value.asText().strip();
        target.add(text.substring(0, Math.min(maxLength, text.length())));
    }

    private int nonNegativeCapacity(JsonNode value, String field, int fallback) {
        JsonNode node = value == null ? null : value.path(field);
        return node != null && node.isIntegralNumber() && node.asLong() >= 0 && node.asLong() <= Integer.MAX_VALUE
                ? node.asInt() : fallback;
    }

    private JsonNode normalizedCapability(JsonNode direct, JsonNode policy) {
        if (direct != null && !direct.isMissingNode() && validCapability(direct)) return direct.deepCopy();
        return capabilityFromPolicy(policy);
    }

    /**
     * The Candidate DTO intentionally accepts human-readable role vocabulary,
     * while the Engine's exact-role resolver needs the two grounded roles that
     * the service can prove from the source snapshot. Preserve the model's
     * vocabulary but add only those bounded canonical aliases when applicable.
     */
    private JsonNode normalizeExecutionRoles(JsonNode candidate) {
        if (candidate == null || !candidate.isObject() || !isSemanticCandidate(candidate)) {
            return candidate;
        }
        ObjectNode normalized = candidate.deepCopy();
        ArrayNode roles = normalized.putArray("pageRoles");
        boolean cover = false;
        boolean content = false;
        boolean nonBlank = false;
        List<String> rawRoleValues = new ArrayList<>();
        JsonNode rawRoles = candidate.path("pageRoles");
        if (rawRoles.isArray()) {
            for (JsonNode rawRole : rawRoles) {
                if (!rawRole.isTextual() || rawRole.asText().isBlank()) continue;
                String value = rawRole.asText().strip();
                String lower = value.toLowerCase(Locale.ROOT);
                nonBlank = true;
                if (lower.contains("cover") || lower.equals("title") || lower.contains("title page")
                        || lower.contains("opening")) cover = true;
                if (lower.equals("content") || lower.contains("content")
                        || lower.contains("lecture") || lower.contains("body")) content = true;
                rawRoleValues.add(value);
            }
        }
        List<String> freeRoleValues = rawRoleValues.stream()
                .filter(value -> !"COVER".equalsIgnoreCase(value) && !"CONTENT".equalsIgnoreCase(value))
                .toList();
        boolean needsCoverAlias = cover;
        boolean needsContentAlias = content || nonBlank;
        int rawLimit = Math.max(0, 20 - (needsCoverAlias ? 1 : 0) - (needsContentAlias ? 1 : 0));
        freeRoleValues.stream().limit(rawLimit).forEach(roles::add);
        if (needsCoverAlias) roles.add("COVER");
        if (needsContentAlias) roles.add("CONTENT");
        if (roles.isEmpty()) roles.add("CONTENT");
        return normalized;
    }

    private boolean containsRole(ArrayNode roles, String expected) {
        for (JsonNode role : roles) {
            if (expected.equalsIgnoreCase(role.asText(""))) return true;
        }
        return false;
    }

    private JsonNode capabilityFromPolicy(JsonNode policy) {
        if (policy == null || policy.isMissingNode() || policy.isNull()) return objectMapper.nullNode();
        ObjectNode capability = objectMapper.createObjectNode();
        capability.put("supported", true);
        capability.put("minCount", 0);
        capability.put("maxCount", 20);
        String notes = policy.asText("Inferred from rendered preview").strip();
        capability.put("notes", notes.substring(0, Math.min(300, notes.length())));
        return capability;
    }

    private boolean containsForbiddenName(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                String name = fields.next().getKey().toLowerCase();
                if (FORBIDDEN_NAME_PARTS.stream().anyMatch(name::contains)) return true;
            }
            fields = node.fields();
            while (fields.hasNext()) if (containsForbiddenName(fields.next().getValue())) return true;
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsForbiddenName(child)) return true;
        }
        return false;
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
