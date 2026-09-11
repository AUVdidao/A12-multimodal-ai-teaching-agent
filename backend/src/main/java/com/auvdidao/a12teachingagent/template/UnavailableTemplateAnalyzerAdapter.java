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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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
            "semanticRoles", "layout", "capacity", "imagePolicy", "tablePolicy",
            "chartPolicy", "fixedBrand", "limitations", "source"
    );
    private static final Set<String> FORBIDDEN_NAME_PARTS = Set.of(
            "native", "shape", "group", "slot", "coordinate", "ooxml", "xml"
    );

    private final ObjectMapper objectMapper;
    private final ModelGateway modelGateway;
    private final ModelCredentialResolver credentialResolver;
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
            @Qualifier("kimiModelGateway") ModelGateway modelGateway,
            ModelCredentialResolver credentialResolver,
            @Value("${a12.template.analyzer.enabled:false}") boolean enabled,
            @Value("${a12.template.analyzer.preview-base-url:}") String previewBaseUrl,
            @Value("${a12.template.analyzer.provider:KIMI}") String provider,
            @Value("${a12.template.analyzer.model:}") String model,
            @Value("${a12.template.analyzer.max-completion-tokens:1600}") int maxCompletionTokens,
            @Value("${a12.template.analyzer.timeout-ms:90000}") long timeoutMs,
            @Value("${a12.template.analyzer.fixture-enabled:false}") boolean fixtureEnabled,
            @Value("${spring.profiles.active:}") String activeProfile
    ) {
        this.objectMapper = objectMapper;
        this.modelGateway = modelGateway;
        this.credentialResolver = credentialResolver;
        this.enabled = enabled;
        this.previewBaseUrl = previewBaseUrl == null ? "" : previewBaseUrl.strip();
        this.provider = provider == null ? "" : provider.strip();
        this.model = model == null ? "" : model.strip();
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
        this.enabled = enabled;
        this.previewBaseUrl = previewBaseUrl == null ? "" : previewBaseUrl.strip();
        this.provider = provider == null ? "" : provider.strip();
        this.model = model == null ? "" : model.strip();
        this.maxCompletionTokens = maxCompletionTokens;
        this.timeoutMs = timeoutMs;
        this.fixtureEnabled = false;
        this.activeProfile = "";
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
            snapshotJson = objectMapper.writeValueAsString(request.structuralSnapshot());
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
                + "Return JSON only with exactly the semantic profile fields: " + REQUIRED_FIELDS
                + ". Do not return native PowerPoint object names, shape/group/slot IDs, coordinates, or OOXML.\n"
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
        JsonNode candidate = parseJson(result.content());
        if (!isSemanticCandidate(candidate)) {
            return new AnalysisResult(false, "multimodal-analyzer-integrity-gate", null,
                    "ANALYZER_OUTPUT_INVALID: candidate is not a bounded semantic profile", null, null,
                    request.analysisRunId(), inputSha256, digest(result.content()));
        }
        return new AnalysisResult(true, "kimi-multimodal", candidate, null,
                result.provider().name(), result.model(), request.analysisRunId(), inputSha256,
                digest(result.content()));
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
        for (String field : REQUIRED_FIELDS) if (!candidate.has(field)) return false;
        return !containsForbiddenName(candidate);
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
