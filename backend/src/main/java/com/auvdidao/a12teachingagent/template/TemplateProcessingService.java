package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.StorageIntegrityException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.template.Template;
import com.auvdidao.a12teachingagent.domain.template.TemplateAnalysisResult;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingOperation;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingRun;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateRenderedSlideSet;
import com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion;
import com.auvdidao.a12teachingagent.domain.template.TemplateStructuralSnapshot;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProcessingRunRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateAnalysisResultRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateRenderedSlideSetRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateStructuralSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.auvdidao.a12teachingagent.template.TemplateDtos.*;

@Service
public class TemplateProcessingService {

    private static final String PARSE_FAILURE = "PARSER_FAILED: template structural parsing could not be completed. Please retry.";
    private static final String RENDER_UNIMPLEMENTED = "真实 PPTX 页面渲染尚未配置；请勿将此记录视为预览通过。";
    private static final String ANALYZE_UNIMPLEMENTED = "多模态模板分析尚未配置；请勿将此记录视为 Analyzer 通过。";
    private static final String ANALYZE_NOT_READY = "ANALYZER_NOT_READY: Parser and Renderer must succeed before Analyzer can run.";

    private final TemplateService templateService;
    private final TemplateParser parser;
    private final TemplateRenderer renderer;
    private final TemplateAnalyzer analyzer;
    private final TemplateProcessingRunRepository runRepository;
    private final TemplateAnalysisResultRepository analysisResultRepository;
    private final TemplateStructuralSnapshotRepository snapshotRepository;
    private final TemplateRenderedSlideSetRepository renderedRepository;
    private final TemplateSourceVersionRepository sourceRepository;
    private final ObjectMapper objectMapper;
    private final TemplateProfileContractGate profileContractGate;

    public TemplateProcessingService(
            TemplateService templateService,
            TemplateParser parser,
            TemplateRenderer renderer,
            TemplateAnalyzer analyzer,
            TemplateProcessingRunRepository runRepository,
            TemplateAnalysisResultRepository analysisResultRepository,
            TemplateStructuralSnapshotRepository snapshotRepository,
            TemplateRenderedSlideSetRepository renderedRepository,
            TemplateSourceVersionRepository sourceRepository,
            ObjectMapper objectMapper,
            TemplateProfileContractGate profileContractGate
    ) {
        this.templateService = templateService;
        this.parser = parser;
        this.renderer = renderer;
        this.analyzer = analyzer;
        this.runRepository = runRepository;
        this.analysisResultRepository = analysisResultRepository;
        this.snapshotRepository = snapshotRepository;
        this.renderedRepository = renderedRepository;
        this.sourceRepository = sourceRepository;
        this.objectMapper = objectMapper;
        this.profileContractGate = profileContractGate;
    }

    @Transactional
    public ProcessingStatusResponse run(Long projectId, Long templateId, Long sourceVersionId, TemplateProcessingOperation operation) {
        Template template = templateService.requireTemplate(projectId, templateId);
        TemplateSourceVersion source = templateService.requireSource(projectId, templateId, sourceVersionId);
        TemplateProcessingRun run = new TemplateProcessingRun();
        run.setTemplateId(template.getId());
        run.setSourceVersionId(source.getId());
        run.setOperation(operation);
        run.setAttempt((int) runRepository.countBySourceVersionIdAndOperation(source.getId(), operation) + 1);
        run.setStatus(TemplateProcessingStatus.PROCESSING);
        run.setStartedAt(LocalDateTime.now());
        run = runRepository.saveAndFlush(run);
        setSourceStatus(source, operation, TemplateProcessingStatus.PROCESSING);

        try {
            Resource resource = templateService.download(projectId, templateId, sourceVersionId).resource();
            if (operation == TemplateProcessingOperation.PARSER) {
                TemplateParser.ParseResult result = parser.parse(resource);
                TemplateStructuralSnapshot snapshot = new TemplateStructuralSnapshot();
                snapshot.setTemplateId(template.getId());
                snapshot.setSourceVersionId(source.getId());
                snapshot.setProcessingRunId(run.getId());
                snapshot.setSlideCount(result.slideCount());
                snapshot.setChecksum(result.checksum());
                snapshot.setSnapshotJson(result.snapshotJson());
                snapshot = snapshotRepository.saveAndFlush(snapshot);
                succeed(run, "db://template-structural-snapshots/" + snapshot.getId(), "apache-poi-readonly");
                setSourceStatus(source, operation, TemplateProcessingStatus.SUCCEEDED);
            } else if (operation == TemplateProcessingOperation.RENDERER) {
                TemplateRenderer.RenderResult result = renderer.render(resource);
                if (!result.implemented()) {
                    finish(run, TemplateProcessingStatus.NOT_IMPLEMENTED, result.adapter(), null, RENDER_UNIMPLEMENTED);
                    TemplateRenderedSlideSet rendered = new TemplateRenderedSlideSet();
                    rendered.setTemplateId(template.getId());
                    rendered.setSourceVersionId(source.getId());
                    rendered.setProcessingRunId(run.getId());
                    rendered.setStatus(TemplateProcessingStatus.NOT_IMPLEMENTED);
                    rendered.setStatusMessage(RENDER_UNIMPLEMENTED);
                    renderedRepository.saveAndFlush(rendered);
                    setSourceStatus(source, operation, TemplateProcessingStatus.NOT_IMPLEMENTED);
                } else if (!isValidRenderResult(source, result)) {
                    String reason = "RENDERER_OUTPUT_INVALID: preview identity or page count did not match the uploaded source";
                    finish(run, TemplateProcessingStatus.FAILED, "renderer-integrity-gate", null, reason);
                    TemplateRenderedSlideSet rendered = new TemplateRenderedSlideSet();
                    rendered.setTemplateId(template.getId());
                    rendered.setSourceVersionId(source.getId());
                    rendered.setProcessingRunId(run.getId());
                    rendered.setStatus(TemplateProcessingStatus.FAILED);
                    rendered.setStatusMessage(reason);
                    renderedRepository.saveAndFlush(rendered);
                    setSourceStatus(source, operation, TemplateProcessingStatus.FAILED);
                } else {
                    finish(run, TemplateProcessingStatus.SUCCEEDED, result.adapter(), result.previewReference(), null);
                    TemplateRenderedSlideSet rendered = new TemplateRenderedSlideSet();
                    rendered.setTemplateId(template.getId());
                    rendered.setSourceVersionId(source.getId());
                    rendered.setProcessingRunId(run.getId());
                    rendered.setStatus(TemplateProcessingStatus.SUCCEEDED);
                    rendered.setSlideCount(result.slideCount());
                    rendered.setPreviewReference(result.previewReference());
                    rendered.setSourceSha256(result.sourceSha256());
                    rendered.setOutputSha256(result.outputSha256());
                    rendered.setOutputSizeBytes(result.outputSizeBytes());
                    rendered.setAdapterVersion(result.adapterVersion());
                    renderedRepository.saveAndFlush(rendered);
                    setSourceStatus(source, operation, TemplateProcessingStatus.SUCCEEDED);
                }
            } else if (operation == TemplateProcessingOperation.ANALYZER) {
                String analysisRunId = "template-analysis-" + run.getId();
                TemplateRenderedSlideSet rendered = renderedRepository.findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(source.getId()).orElse(null);
                if (source.getParseStatus() != TemplateProcessingStatus.SUCCEEDED
                        || source.getRenderStatus() != TemplateProcessingStatus.SUCCEEDED
                        || rendered == null
                        || rendered.getStatus() != TemplateProcessingStatus.SUCCEEDED
                        || rendered.getPreviewReference() == null) {
                    finish(run, TemplateProcessingStatus.NOT_READY, "analyzer-precondition-gate", null, ANALYZE_NOT_READY);
                    persistAnalysisResult(template, source, run, rendered, null, null,
                            TemplateProcessingStatus.NOT_READY, ANALYZE_NOT_READY, analysisRunId);
                    setSourceStatus(source, operation, TemplateProcessingStatus.NOT_READY);
                    return toStatus(runRepository.findById(run.getId()).orElse(run));
                }
                JsonNode snapshot = readJson(profileContractGate.loadAndVerifySnapshot(source.getId()).getSnapshotJson());
                TemplateAnalyzer.AnalysisResult result = analyzer.analyze(new TemplateAnalyzer.AnalysisRequest(
                        snapshot,
                        rendered.getPreviewReference(),
                        source.getProjectId(),
                        source.getId(),
                        source.getCreatedByUserId(),
                        source.getSha256(),
                        analysisRunId
                ));
                if (!result.implemented()) {
                    finish(run, TemplateProcessingStatus.NOT_IMPLEMENTED, result.adapter(), null, ANALYZE_UNIMPLEMENTED);
                    persistAnalysisResult(template, source, run, rendered, snapshot, result,
                            TemplateProcessingStatus.NOT_IMPLEMENTED, ANALYZE_UNIMPLEMENTED, analysisRunId);
                    setSourceStatus(source, operation, TemplateProcessingStatus.NOT_IMPLEMENTED);
                } else if (!isValidAnalysisResult(result, analysisRunId, source)) {
                    String reason = "ANALYZER_OUTPUT_INVALID: structured Candidate Profile or audit binding is invalid";
                    finish(run, TemplateProcessingStatus.FAILED, "analyzer-integrity-gate", null,
                            reason);
                    persistAnalysisResult(template, source, run, rendered, snapshot, result,
                            TemplateProcessingStatus.FAILED, reason, analysisRunId);
                    setSourceStatus(source, operation, TemplateProcessingStatus.FAILED);
                } else {
                    finish(run, TemplateProcessingStatus.SUCCEEDED, result.adapter(),
                            "analysis://template-analysis-results/" + analysisRunId, null);
                    persistAnalysisResult(template, source, run, rendered, snapshot, result,
                            TemplateProcessingStatus.SUCCEEDED, null, analysisRunId);
                    setSourceStatus(source, operation, TemplateProcessingStatus.SUCCEEDED);
                }
            } else {
                throw new BadRequestException("Unsupported template processing operation");
            }
        } catch (StorageIntegrityException exception) {
            finish(run, TemplateProcessingStatus.FAILED, "storage-integrity-gate", null, "INTEGRITY_MISMATCH: stored PPTX bytes failed verification");
            setSourceStatus(source, operation, TemplateProcessingStatus.FAILED);
        } catch (com.auvdidao.a12teachingagent.common.exception.SnapshotIntegrityException exception) {
            finish(run, TemplateProcessingStatus.FAILED, "snapshot-integrity-gate", null, exception.getMessage());
            setSourceStatus(source, operation, TemplateProcessingStatus.FAILED);
        } catch (RuntimeException | java.io.IOException exception) {
            String code = switch (operation) {
                case PARSER -> "PARSER_FAILED";
                case RENDERER -> "RENDERER_FAILED";
                case ANALYZER -> "ANALYZER_FAILED";
            };
            String safeReason = code + ": processing could not be completed; retry is allowed";
            finish(run, TemplateProcessingStatus.FAILED, "adapter-failed", null, safeReason);
            if (operation == TemplateProcessingOperation.ANALYZER) {
                persistAnalysisResult(template, source, run, null, null, null,
                        TemplateProcessingStatus.FAILED, safeReason, "template-analysis-" + run.getId());
            }
            setSourceStatus(source, operation, TemplateProcessingStatus.FAILED);
        }
        return toStatus(runRepository.findById(run.getId()).orElse(run));
    }

    private boolean isValidRenderResult(TemplateSourceVersion source, TemplateRenderer.RenderResult result) {
        if (result.adapter() == null || result.adapter().isBlank()
                || result.adapterVersion() == null || result.adapterVersion().isBlank()
                || result.previewReference() == null || result.previewReference().isBlank()
                || result.previewReference().startsWith("/") || result.previewReference().contains("\\")
                || result.previewReference().contains("..") || !result.previewReference().startsWith("template-renders/")
                || result.slideCount() == null || result.slideCount() <= 0
                || result.sourceSha256() == null || !result.sourceSha256().matches("[0-9a-fA-F]{64}")
                || !result.sourceSha256().equalsIgnoreCase(source.getSha256())
                || result.outputSha256() == null || !result.outputSha256().matches("[0-9a-fA-F]{64}")
                || result.outputSizeBytes() == null || result.outputSizeBytes() <= 0) {
            return false;
        }
        return snapshotRepository.findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(source.getId())
                .map(snapshot -> snapshot.getSlideCount() != null && snapshot.getSlideCount().equals(result.slideCount()))
                .orElse(false);
    }

    private boolean isValidAnalysisResult(TemplateAnalyzer.AnalysisResult result, String analysisRunId,
                                          TemplateSourceVersion source) {
        return result.candidateProfile() != null && result.candidateProfile().isObject()
                && result.provider() != null && !result.provider().isBlank()
                && result.model() != null && !result.model().isBlank()
                && analysisRunId.equals(result.analysisRunId())
                && result.inputSha256() != null && result.inputSha256().matches("[0-9a-fA-F]{64}")
                && result.outputSha256() != null && result.outputSha256().matches("[0-9a-fA-F]{64}")
                && source.getSha256() != null && source.getSha256().matches("[0-9a-fA-F]{64}");
    }

    private void persistAnalysisResult(Template template, TemplateSourceVersion source,
                                       TemplateProcessingRun run, TemplateRenderedSlideSet rendered,
                                       JsonNode snapshot, TemplateAnalyzer.AnalysisResult result,
                                       TemplateProcessingStatus status, String failureReason,
                                       String analysisRunId) {
        TemplateAnalysisResult audit = new TemplateAnalysisResult();
        audit.setTemplateId(template.getId());
        audit.setProjectId(source.getProjectId());
        audit.setSourceVersionId(source.getId());
        audit.setOwnerUserId(source.getCreatedByUserId());
        audit.setProcessingRunId(run.getId());
        // The service-owned run id is authoritative; adapter output cannot
        // redirect an audit record to another attempt.
        audit.setAnalysisRunId(analysisRunId);
        audit.setSourceSha256(source.getSha256());
        audit.setParserSnapshotChecksum(snapshotRepository.findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(source.getId())
                .map(TemplateStructuralSnapshot::getChecksum).orElse(null));
        if (rendered != null) {
            audit.setRenderedSlideSetId(rendered.getId());
            audit.setRenderedPreviewReference(rendered.getPreviewReference());
            audit.setRenderedOutputSha256(rendered.getOutputSha256());
            audit.setRenderedOutputSizeBytes(rendered.getOutputSizeBytes());
        }
        audit.setAdapter(result == null ? "analyzer-precondition-gate" : result.adapter());
        audit.setAdapterVersion("v1");
        if (result != null) {
            audit.setProvider(result.provider());
            audit.setModel(result.model());
            audit.setInputSha256(result.inputSha256());
            audit.setOutputSha256(result.outputSha256());
            if (result.candidateProfile() != null && result.candidateProfile().isObject()
                    && status == TemplateProcessingStatus.SUCCEEDED) {
                String candidateJson = TemplateChecksum.canonicalJson(objectMapper, result.candidateProfile());
                audit.setCandidateProfileJson(candidateJson);
                audit.setCandidateProfileSha256(TemplateChecksum.sha256(candidateJson));
            }
        }
        audit.setStatus(status);
        audit.setFailureReason(failureReason);
        analysisResultRepository.saveAndFlush(audit);
    }

    @Transactional(readOnly = true)
    public SourceVersionResponse detail(Long projectId, Long templateId, Long sourceVersionId) {
        TemplateSourceVersion source = templateService.requireSource(projectId, templateId, sourceVersionId);
        List<ProcessingRunResponse> runs = runRepository.findBySourceVersionIdOrderByCreatedAtAsc(source.getId()).stream()
                .map(run -> new ProcessingRunResponse(run.getId(), run.getOperation(), run.getStatus(), run.getAttempt(), run.getAdapter(),
                        run.getOutputReference(), run.getFailureReason(), run.getStartedAt(), run.getCompletedAt())).toList();
        StructuralSnapshotResponse snapshot = snapshotRepository.findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(source.getId())
                .map(value -> {
                    var verified = profileContractGate.loadAndVerifySnapshot(source.getId());
                    return new StructuralSnapshotResponse(verified.getId(), verified.getSlideCount(), verified.getChecksum(), readJson(verified.getSnapshotJson()), verified.getCreatedAt());
                }).orElse(null);
        RenderedSlideSetResponse rendered = renderedRepository.findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(source.getId())
                .map(value -> new RenderedSlideSetResponse(value.getId(), value.getStatus(), value.getSlideCount(), value.getPreviewReference(), value.getStatusMessage(),
                        value.getSourceSha256(), value.getOutputSha256(), value.getOutputSizeBytes(), value.getAdapterVersion(), value.getCreatedAt()))
                .orElse(null);
        return templateService.toSourceResponse(projectId, source, runs, snapshot, rendered, true);
    }

    private void setSourceStatus(TemplateSourceVersion source, TemplateProcessingOperation operation, TemplateProcessingStatus status) {
        if (operation == TemplateProcessingOperation.PARSER) source.setParseStatus(status);
        if (operation == TemplateProcessingOperation.RENDERER) source.setRenderStatus(status);
        if (operation == TemplateProcessingOperation.ANALYZER) source.setAnalysisStatus(status);
        sourceRepository.saveAndFlush(source);
    }

    private void succeed(TemplateProcessingRun run, String outputReference, String adapter) {
        finish(run, TemplateProcessingStatus.SUCCEEDED, adapter, outputReference, null);
    }

    private void finish(TemplateProcessingRun run, TemplateProcessingStatus status, String adapter, String outputReference, String failureReason) {
        run.setStatus(status);
        run.setAdapter(adapter);
        run.setOutputReference(outputReference);
        run.setFailureReason(failureReason);
        run.setCompletedAt(LocalDateTime.now());
        runRepository.saveAndFlush(run);
    }

    private ProcessingStatusResponse toStatus(TemplateProcessingRun run) {
        return new ProcessingStatusResponse(run.getSourceVersionId(), run.getOperation(), run.getStatus(), run.getAttempt(), run.getAdapter(), run.getOutputReference(), run.getFailureReason());
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new ResourceNotFoundException("Template structural snapshot is unavailable");
        }
    }
}
