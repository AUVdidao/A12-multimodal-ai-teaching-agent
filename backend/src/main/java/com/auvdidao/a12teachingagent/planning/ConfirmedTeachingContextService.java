package com.auvdidao.a12teachingagent.planning;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntentEvidence;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.PlanningRequest;
import com.auvdidao.a12teachingagent.planning.PlanningDtos.SourceEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the planning input from server-owned, confirmed upstream records. */
@Service
public class ConfirmedTeachingContextService {
    private static final Pattern REVISION = Pattern.compile("^intent:(\\d+):([0-9a-fA-F]{64})$");

    private final ProjectRepository projectRepository;
    private final RequirementSummaryRepository summaryRepository;
    private final TeachingIntentRepository intentRepository;
    private final ObjectMapper objectMapper;

    public ConfirmedTeachingContextService(ProjectRepository projectRepository,
                                           RequirementSummaryRepository summaryRepository,
                                           TeachingIntentRepository intentRepository,
                                           ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.summaryRepository = summaryRepository;
        this.intentRepository = intentRepository;
        this.objectMapper = objectMapper;
    }

    public Snapshot load(Long projectId, String revision) {
        Matcher matcher = revision == null ? null : REVISION.matcher(revision.trim());
        if (matcher == null || !matcher.matches()) {
            throw new ConflictException("CONFIRMED_CONTEXT_INVALID: use intent:<id>:<canonical-checksum>");
        }
        long intentId = Long.parseLong(matcher.group(1));
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        TeachingIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new ResourceNotFoundException("Confirmed teaching intent not found: " + intentId));
        RequirementSummary summary = requireConfirmedRecords(projectId, project, intent);
        Snapshot snapshot = buildSnapshot(projectId, project, intent, summary);
        if (!snapshot.checksum().equalsIgnoreCase(matcher.group(2))) {
            throw new ConflictException("CONFIRMED_CONTEXT_CHECKSUM_MISMATCH");
        }
        return snapshot;
    }

    /** Returns the current server-owned reference; callers never supply its checksum. */
    public ConfirmedContextReference currentReference(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        TeachingIntent intent = intentRepository
                .findFirstByProjectIdAndStatusOrderByConfirmedAtDescCreatedAtDescIdDesc(
                        projectId, TeachingIntentStatus.CONFIRMED)
                .orElseThrow(() -> new ConflictException("CONFIRMED_CONTEXT_NOT_READY"));
        RequirementSummary summary = requireConfirmedRecords(projectId, project, intent);
        Snapshot snapshot = buildSnapshot(projectId, project, intent, summary);
        return new ConfirmedContextReference(snapshot.revision(), snapshot.checksum(), snapshot.confirmedPageCount(), snapshot.confirmedPageOutline());
    }

    /**
     * The legacy request still carries context/evidence fields for wire compatibility.
     * They are not a source of truth: when present they must exactly match the server snapshot.
     */
    public void validateRequestBinding(PlanningRequest request, Snapshot snapshot) {
        if (request == null || request.teachingContext() == null
                || !sameContext(request.teachingContext(), snapshot.context())
                || !sameEvidence(request.evidence(), snapshot.evidence())) {
            throw new ConflictException("CONFIRMED_CONTEXT_REQUEST_MISMATCH");
        }
    }

    private RequirementSummary requireConfirmedRecords(Long projectId, Project project, TeachingIntent intent) {
        if (project.getOwnerUserId() == null || !projectId.equals(project.getId())) {
            throw new ConflictException("CONFIRMED_CONTEXT_OWNER_MISMATCH");
        }
        if (!projectId.equals(intent.getProjectId()) || intent.getStatus() != TeachingIntentStatus.CONFIRMED) {
            throw new ConflictException("CONFIRMED_CONTEXT_STATUS_MISMATCH");
        }
        RequirementSummary summary = summaryRepository.findById(intent.getRequirementSummaryId())
                .orElseThrow(() -> new ConflictException("CONFIRMED_CONTEXT_SUMMARY_MISSING"));
        if (!projectId.equals(summary.getProjectId()) || summary.getStatus() != RequirementSummaryStatus.CONFIRMED) {
            throw new ConflictException("CONFIRMED_CONTEXT_SUMMARY_NOT_CONFIRMED");
        }
        return summary;
    }

    private Snapshot buildSnapshot(Long projectId, Project project, TeachingIntent intent, RequirementSummary summary) {
        List<String> objectives = nonBlank(intent.getGenerationGoals());
        if (objectives.isEmpty() || !StringUtils.hasText(project.getCourseName()) || !StringUtils.hasText(summary.getTopic())) {
            throw new ConflictException("CONFIRMED_CONTEXT_INCOMPLETE");
        }
        // generationGoals are confirmed teaching facts, not an implicit slide outline.
        // The server owns this explicit, versioned projection until a future teacher
        // outline editor can persist a first-class per-slide outline.
        List<ConfirmedPageOutlineItem> confirmedPageOutline = new ArrayList<>();
        for (int index = 0; index < objectives.size(); index++) {
            confirmedPageOutline.add(new ConfirmedPageOutlineItem(
                    index + 2, objectives.get(index), "CONTENT",
                    "CONFIRMED_TEACHING_INTENT_GENERATION_GOAL", "intent:" + intent.getId()));
        }
        int confirmedPageCount = confirmedPageOutline.size() + 1; // one server-owned cover page
        List<String> outline = confirmedPageOutline.stream().map(ConfirmedPageOutlineItem::title).toList();
        List<String> lessonPlan = StringUtils.hasText(intent.getTeachingApproach())
                ? List.of(intent.getTeachingApproach().strip()) : List.of();
        List<SourceEvidence> evidence = intent.getEvidenceItems().stream()
                .map(ConfirmedTeachingContextService::toEvidence)
                .toList();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("projectId", projectId);
        canonical.put("ownerUserId", project.getOwnerUserId());
        canonical.put("intentId", intent.getId());
        canonical.put("intentUpdatedAt", intent.getUpdatedAt());
        canonical.put("summaryId", summary.getId());
        canonical.put("summaryUpdatedAt", summary.getUpdatedAt());
        canonical.put("courseName", project.getCourseName().strip());
        canonical.put("topic", summary.getTopic().strip());
        canonical.put("teachingObjectives", objectives);
        canonical.put("outlineSource", "SERVER_CONFIRMED_PAGE_OUTLINE_V1");
        canonical.put("confirmedPageCount", confirmedPageCount);
        canonical.put("confirmedPageOutline", confirmedPageOutline);
        canonical.put("lessonPlan", lessonPlan);
        canonical.put("evidence", evidence);
        String canonicalJson;
        try {
            canonicalJson = objectMapper.writeValueAsString(canonical);
        } catch (JsonProcessingException exception) {
            throw new ConflictException("CONFIRMED_CONTEXT_SERIALIZATION_FAILED");
        }
        String checksum = sha256(canonicalJson);
        PlanningDtos.TeachingContext context = new PlanningDtos.TeachingContext(
                project.getCourseName().strip(), summary.getTopic().strip(), objectives, outline, lessonPlan, true);
        return new Snapshot("intent:" + intent.getId() + ":" + checksum, checksum, canonicalJson, context, evidence,
                confirmedPageCount, confirmedPageOutline);
    }

    private static boolean sameContext(PlanningDtos.TeachingContext requested, PlanningDtos.TeachingContext expected) {
        return sameText(requested.courseName(), expected.courseName())
                && sameText(requested.topic(), expected.topic())
                && sameValues(requested.teachingObjectives(), expected.teachingObjectives())
                && sameValues(requested.outline(), expected.outline())
                && sameValues(requested.lessonPlan(), expected.lessonPlan())
                && Boolean.TRUE.equals(requested.teacherConfirmed());
    }

    private static boolean sameEvidence(List<SourceEvidence> requested, List<SourceEvidence> expected) {
        if (requested == null || requested.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            SourceEvidence left = requested.get(index);
            SourceEvidence right = expected.get(index);
            if (left == null || !sameText(left.sourceId(), right.sourceId())
                    || !sameText(left.sourceType(), right.sourceType())
                    || !sameText(left.excerpt(), right.excerpt())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameValues(List<String> requested, List<String> expected) {
        if (requested == null || expected == null || requested.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!sameText(requested.get(index), expected.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameText(String left, String right) {
        return left != null && right != null && left.strip().equals(right.strip());
    }

    private static SourceEvidence toEvidence(TeachingIntentEvidence evidence) {
        String sourceId = "material:" + value(evidence.getMaterialId()) + ":chunk:" + value(evidence.getKnowledgeChunkId());
        String excerpt = StringUtils.hasText(evidence.getContentExcerpt()) ? evidence.getContentExcerpt().strip() : "confirmed-evidence";
        return new SourceEvidence(sourceId, "CONFIRMED_TEACHING_INTENT", excerpt);
    }

    private static String value(Long value) { return value == null ? "none" : value.toString(); }

    private static List<String> nonBlank(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null) values.stream().filter(StringUtils::hasText).map(String::strip).forEach(result::add);
        return List.copyOf(result);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Snapshot(String revision, String checksum, String canonicalJson,
                           PlanningDtos.TeachingContext context, List<SourceEvidence> evidence,
                           Integer confirmedPageCount, List<ConfirmedPageOutlineItem> confirmedPageOutline) {
        public Snapshot(String revision, String checksum, String canonicalJson,
                        PlanningDtos.TeachingContext context, List<SourceEvidence> evidence) {
            this(revision, checksum, canonicalJson, context, evidence,
                    context == null ? null : context.outline().size() + 1,
                    legacyOutline(context));
        }

        public Snapshot {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            confirmedPageOutline = confirmedPageOutline == null ? List.of() : List.copyOf(confirmedPageOutline);
            if (confirmedPageCount == null || confirmedPageCount != confirmedPageOutline.size() + 1) {
                throw new IllegalArgumentException("confirmedPageCount must equal outline pages plus cover");
            }
        }

        private static List<ConfirmedPageOutlineItem> legacyOutline(PlanningDtos.TeachingContext context) {
            if (context == null || context.outline() == null) return List.of();
            List<ConfirmedPageOutlineItem> items = new ArrayList<>();
            for (int index = 0; index < context.outline().size(); index++) {
                items.add(new ConfirmedPageOutlineItem(index + 2, context.outline().get(index), "CONTENT",
                        "LEGACY_TEST_SNAPSHOT", "legacy-test"));
            }
            return items;
        }
    }

    public record ConfirmedPageOutlineItem(Integer pageNumber, String title, String semanticRole,
                                           String sourceType, String sourceReference) { }

    public record ConfirmedContextReference(String revision, String checksum,
                                            Integer confirmedPageCount,
                                            List<ConfirmedPageOutlineItem> confirmedPageOutline) {
        public ConfirmedContextReference(String revision, String checksum) {
            this(revision, checksum, null, List.of());
        }

        public ConfirmedContextReference {
            confirmedPageOutline = confirmedPageOutline == null ? List.of() : List.copyOf(confirmedPageOutline);
        }
    }
}
