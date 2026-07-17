package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisResponse;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.ai.gateway.MockAIWorkflowGateway;
import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.knowledge.KnowledgeIndexService;
import com.auvdidao.a12teachingagent.material.dto.MaterialDtos.ParseResultResponse;
import com.auvdidao.a12teachingagent.material.parse.MaterialPrototypeParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialParseServiceGatewayTest {

    private static final long PROJECT_ID = 41L;
    private static final long MATERIAL_ID = 73L;

    @Mock
    private MaterialService materialService;

    @Mock
    private UploadedMaterialRepository materialRepository;

    @Mock
    private MaterialPurposeRepository purposeRepository;

    @Mock
    private ParseResultRepository parseResultRepository;

    @Mock
    private MaterialPrototypeParser prototypeParser;

    @Mock
    private AIWorkflowGateway aiWorkflowGateway;

    @Mock
    private KnowledgeIndexService knowledgeIndexService;

    private MaterialParseService service;

    @BeforeEach
    void setUp() {
        service = new MaterialParseService(
                materialService,
                materialRepository,
                purposeRepository,
                parseResultRepository,
                prototypeParser,
                aiWorkflowGateway,
                knowledgeIndexService
        );
    }

    @Test
    void mapsMockAnalysisOntoTheLocalParseResultUsingOnlyApprovedFields() {
        Fixture fixture = fixture();
        stubAccessAndUsage(fixture);
        when(parseResultRepository.findFirstByMaterialIdOrderByCreatedAtDescIdDesc(MATERIAL_ID))
                .thenReturn(Optional.empty());
        assignParseResultIdOnFlush();
        when(prototypeParser.parse(fixture.material(), fixture.usages(), fixture.summary()))
                .thenReturn(localParsedContent());

        MockAIWorkflowGateway mockProvider = new MockAIWorkflowGateway();
        when(aiWorkflowGateway.analyzeMaterial(any()))
                .thenAnswer(invocation -> mockProvider.analyzeMaterial(invocation.getArgument(0)));

        ParseResultResponse response = service.parse(PROJECT_ID, MATERIAL_ID);

        assertThat(response.parseStatus()).isEqualTo(MaterialParseStatus.SUCCEEDED);
        assertThat(response.summary())
                .contains("本地真实提取摘要", "已从 光合作用教材.pdf", "教材依据、案例素材");
        assertThat(response.keywords())
                .containsExactly("本地关键词", "核心概念", "课堂案例", "互动练习", "易错点")
                .doesNotContain("概念定义片段");
        assertThat(response.applicableTeachingStages()).containsExactly(
                "概念讲解",
                "案例分析",
                "作为导入案例",
                "补充重点难点解释",
                "生成课堂互动题"
        );

        ArgumentCaptor<MaterialAnalysisRequest> requestCaptor = ArgumentCaptor.forClass(MaterialAnalysisRequest.class);
        verify(aiWorkflowGateway).analyzeMaterial(requestCaptor.capture());
        MaterialAnalysisRequest request = requestCaptor.getValue();
        assertThat(request.projectId()).isEqualTo(PROJECT_ID);
        assertThat(request.fileName()).isEqualTo("光合作用教材.pdf");
        assertThat(request.materialType()).isEqualTo("PDF");
        assertThat(request.purpose()).isEqualTo("教材依据、案例素材");
        assertThat(request.materialText()).contains("叶绿体中的叶绿素吸收光能");
        assertThat(request.purposeTypes()).containsExactly("TEXTBOOK_BASIS", "CASE_MATERIAL");
        assertThat(request.courseContext().courseName()).isEqualTo("生物");
        assertThat(request.courseContext().chapterTopic()).isEqualTo("光合作用");
        verify(knowledgeIndexService).index(fixture.material());
    }

    @Test
    void returnsAnExistingSuccessfulResultWithoutRepeatingParserAiOrIndexing() {
        Fixture fixture = fixture();
        stubAccessAndUsage(fixture);
        ParseResult existing = parseResult(MaterialParseStatus.SUCCEEDED);
        existing.setSummary("已经成功的摘要");
        existing.setKeywords(List.of("已存在关键词"));
        existing.setApplicableTeachingStages(List.of("概念讲解"));
        when(parseResultRepository.findFirstByMaterialIdOrderByCreatedAtDescIdDesc(MATERIAL_ID))
                .thenReturn(Optional.of(existing));

        ParseResultResponse response = service.parse(PROJECT_ID, MATERIAL_ID);

        assertThat(response.parseStatus()).isEqualTo(MaterialParseStatus.SUCCEEDED);
        assertThat(response.summary()).isEqualTo("已经成功的摘要");
        verifyNoInteractions(prototypeParser, aiWorkflowGateway, knowledgeIndexService, materialRepository);
        verify(parseResultRepository, never()).save(any());
        verify(parseResultRepository, never()).saveAndFlush(any());
    }

    @Test
    void aiFailureMovesToFailedWithoutLeakingDetailsAndCanBeRetried() {
        Fixture fixture = fixture();
        stubAccessAndUsage(fixture);
        AtomicReference<ParseResult> storedResult = new AtomicReference<>();
        when(parseResultRepository.findFirstByMaterialIdOrderByCreatedAtDescIdDesc(MATERIAL_ID))
                .thenAnswer(invocation -> Optional.ofNullable(storedResult.get()));
        when(parseResultRepository.saveAndFlush(any(ParseResult.class))).thenAnswer(invocation -> {
            ParseResult result = invocation.getArgument(0);
            if (result.getId() == null) {
                result.setId(501L);
            }
            storedResult.set(result);
            return result;
        });
        when(parseResultRepository.save(any(ParseResult.class))).thenAnswer(invocation -> {
            ParseResult result = invocation.getArgument(0);
            storedResult.set(result);
            return result;
        });
        when(prototypeParser.parse(fixture.material(), fixture.usages(), fixture.summary()))
                .thenReturn(localParsedContent());
        when(aiWorkflowGateway.analyzeMaterial(any()))
                .thenThrow(new AiWorkflowUnavailableException("sensitive Dify response body and token"))
                .thenReturn(successfulAnalysis());

        ParseResultResponse failed = service.parse(PROJECT_ID, MATERIAL_ID);

        assertThat(failed.parseStatus()).isEqualTo(MaterialParseStatus.FAILED);
        assertThat(failed.failureReason())
                .isEqualTo("Prototype parsing could not be completed. Please retry.")
                .doesNotContain("sensitive", "Dify", "token");
        assertThat(fixture.material().getParseStatus()).isEqualTo(MaterialParseStatus.FAILED);
        assertThat(fixture.material().getUploadStatus()).isEqualTo(UploadStatus.FAILED);

        ParseResultResponse retried = service.retry(PROJECT_ID, MATERIAL_ID);

        assertThat(retried.parseStatus()).isEqualTo(MaterialParseStatus.SUCCEEDED);
        assertThat(retried.failureReason()).isNull();
        assertThat(retried.summary()).contains("本地真实提取摘要", "重试后的 AI 分析");
        assertThat(fixture.material().getParseStatus()).isEqualTo(MaterialParseStatus.SUCCEEDED);
        assertThat(fixture.material().getUploadStatus()).isEqualTo(UploadStatus.PARSED);
        verify(aiWorkflowGateway, times(2)).analyzeMaterial(any());
        verify(knowledgeIndexService, times(1)).index(fixture.material());
    }

    private void stubAccessAndUsage(Fixture fixture) {
        when(materialService.requireConfirmedSummary(PROJECT_ID)).thenReturn(fixture.summary());
        when(materialService.requireMaterial(PROJECT_ID, MATERIAL_ID)).thenReturn(fixture.material());
        when(purposeRepository.findByMaterialIdOrderByIdAsc(MATERIAL_ID)).thenReturn(fixture.purposes());
    }

    private void assignParseResultIdOnFlush() {
        when(parseResultRepository.saveAndFlush(any(ParseResult.class))).thenAnswer(invocation -> {
            ParseResult result = invocation.getArgument(0);
            if (result.getId() == null) {
                result.setId(500L);
            }
            return result;
        });
    }

    private static Fixture fixture() {
        RequirementSummary summary = new RequirementSummary();
        summary.setProjectId(PROJECT_ID);
        summary.setSubject("生物");
        summary.setTopic("光合作用");

        UploadedMaterial material = new UploadedMaterial();
        material.setId(MATERIAL_ID);
        material.setProjectId(PROJECT_ID);
        material.setOriginalFileName("光合作用教材.pdf");
        material.setFileName("stored-material.pdf");
        material.setFileExtension("pdf");
        material.setFileType(MaterialFileType.PDF);
        material.setUploadStatus(UploadStatus.UPLOADED);
        material.setParseStatus(MaterialParseStatus.NOT_STARTED);

        MaterialPurpose textbook = purpose(PurposeType.TEXTBOOK_BASIS);
        MaterialPurpose caseMaterial = purpose(PurposeType.CASE_MATERIAL);
        return new Fixture(
                summary,
                material,
                List.of(textbook, caseMaterial),
                List.of(PurposeType.TEXTBOOK_BASIS, PurposeType.CASE_MATERIAL)
        );
    }

    private static MaterialPurpose purpose(PurposeType type) {
        MaterialPurpose purpose = new MaterialPurpose();
        purpose.setProjectId(PROJECT_ID);
        purpose.setMaterialId(MATERIAL_ID);
        purpose.setPurposeType(type);
        return purpose;
    }

    private static MaterialPrototypeParser.ParsedContent localParsedContent() {
        return new MaterialPrototypeParser.ParsedContent(
                "本地真实提取摘要",
                List.of("本地关键词"),
                List.of("概念讲解", "案例分析"),
                "叶绿体中的叶绿素吸收光能，并将光能转换为化学能。"
        );
    }

    private static MaterialAnalysisResponse successfulAnalysis() {
        return new MaterialAnalysisResponse(
                "WF-03",
                "SUCCEEDED",
                "重试后的 AI 分析",
                List.of("AI 关键词"),
                List.of("课堂应用"),
                List.of("不应落库的分块建议")
        );
    }

    private static ParseResult parseResult(MaterialParseStatus status) {
        ParseResult result = new ParseResult();
        result.setId(499L);
        result.setMaterialId(MATERIAL_ID);
        result.setParseStatus(status);
        return result;
    }

    private record Fixture(
            RequirementSummary summary,
            UploadedMaterial material,
            List<MaterialPurpose> purposes,
            List<PurposeType> usages
    ) {
    }
}
