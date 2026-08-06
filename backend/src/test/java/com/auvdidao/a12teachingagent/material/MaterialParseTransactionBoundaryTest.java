package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.ai.gateway.AIWorkflowGateway;
import com.auvdidao.a12teachingagent.ai.exception.AiWorkflowUnavailableException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
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
import com.auvdidao.a12teachingagent.material.chunk.TextCleaner;
import com.auvdidao.a12teachingagent.material.parse.MaterialParsingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialParseTransactionBoundaryTest {

    private static final Long PROJECT_ID = 17L;
    private static final Long MATERIAL_ID = 29L;

    @Mock
    private MaterialService materialService;
    @Mock
    private UploadedMaterialRepository materialRepository;
    @Mock
    private MaterialPurposeRepository purposeRepository;
    @Mock
    private ParseResultRepository parseResultRepository;
    @Mock
    private KnowledgeIndexService knowledgeIndexService;
    @Mock
    private MaterialPrototypeParser prototypeParser;
    @Mock
    private AIWorkflowGateway aiWorkflowGateway;

    @Test
    void parseEntryPointsDoNotHoldALongDatabaseTransaction() throws Exception {
        assertFalse(hasTransactionalAnnotation("parse", Long.class, Long.class));
        assertFalse(hasTransactionalAnnotation("parse", Long.class, Long.class, boolean.class));
        assertFalse(hasTransactionalAnnotation("retry", Long.class, Long.class));
    }

    @Test
    void externalParserAndAiRunBetweenShortTransactionCalls() {
        MaterialParseTransactionService transactionService = mock(MaterialParseTransactionService.class);
        MaterialParseService service = new MaterialParseService(
                materialService,
                materialRepository,
                purposeRepository,
                parseResultRepository,
                prototypeParser,
                aiWorkflowGateway,
                knowledgeIndexService,
                new TextCleaner(),
                transactionService
        );

        UploadedMaterial material = new UploadedMaterial();
        RequirementSummary summary = new RequirementSummary();
        ParseResult result = new ParseResult();
        MaterialParseTransactionService.ParsePreparation preparation =
                new MaterialParseTransactionService.ParsePreparation(
                        PROJECT_ID,
                        MATERIAL_ID,
                        material,
                        summary,
                        List.of(PurposeType.TEXTBOOK_BASIS),
                        System.nanoTime(),
                        result,
                        null
                );
        ParseResultResponse response = new ParseResultResponse(
                null,
                MATERIAL_ID,
                MaterialParseStatus.SUCCEEDED,
                "summary",
                List.of("keyword"),
                List.of("lecture"),
                null,
                LocalDateTime.now(),
                true,
                "text",
                null,
                List.of("text"),
                1,
                5L
        );

        when(transactionService.prepare(PROJECT_ID, MATERIAL_ID, false, false)).thenReturn(preparation);
        when(prototypeParser.parse(material, preparation.usages(), summary))
                .thenReturn(new MaterialPrototypeParser.ParsedContent(
                        "summary",
                        List.of("keyword"),
                        List.of("lecture"),
                        "analysis",
                        "text",
                        null,
                        List.of("text")
                ));
        when(aiWorkflowGateway.analyzeMaterial(any()))
                .thenThrow(new AiWorkflowUnavailableException(
                        "KIMI_TIMEOUT: provider request timed out",
                        "KIMI_TIMEOUT",
                        504
                ));
        when(transactionService.complete(any())).thenReturn(response);

        assertThat(service.parse(PROJECT_ID, MATERIAL_ID)).isSameAs(response);

        InOrder order = inOrder(transactionService, prototypeParser, aiWorkflowGateway);
        order.verify(transactionService).prepare(PROJECT_ID, MATERIAL_ID, false, false);
        order.verify(prototypeParser).parse(material, preparation.usages(), summary);
        order.verify(aiWorkflowGateway).analyzeMaterial(any());
        order.verify(transactionService).complete(any());
    }

    @Test
    void parserFailureUsesFailureTransactionAndDoesNotComplete() {
        MaterialParseTransactionService transactionService = mock(MaterialParseTransactionService.class);
        MaterialParseService service = new MaterialParseService(
                materialService,
                materialRepository,
                purposeRepository,
                parseResultRepository,
                prototypeParser,
                aiWorkflowGateway,
                knowledgeIndexService,
                new TextCleaner(),
                transactionService
        );

        UploadedMaterial material = new UploadedMaterial();
        RequirementSummary summary = new RequirementSummary();
        ParseResult result = new ParseResult();
        MaterialParseTransactionService.ParsePreparation preparation =
                new MaterialParseTransactionService.ParsePreparation(
                        PROJECT_ID,
                        MATERIAL_ID,
                        material,
                        summary,
                        List.of(PurposeType.TEXTBOOK_BASIS),
                        System.nanoTime(),
                        result,
                        null
                );
        ParseResultResponse failedResponse = new ParseResultResponse(
                null,
                MATERIAL_ID,
                MaterialParseStatus.FAILED,
                null,
                List.of(),
                List.of(),
                "Prototype parsing could not be completed. Please retry.",
                LocalDateTime.now(),
                true,
                null,
                null,
                List.of(),
                null,
                8L
        );

        when(transactionService.prepare(PROJECT_ID, MATERIAL_ID, false, false)).thenReturn(preparation);
        when(prototypeParser.parse(material, preparation.usages(), summary))
                .thenThrow(new MaterialParsingException("parser failure"));
        when(transactionService.fail(any())).thenReturn(failedResponse);

        assertThat(service.parse(PROJECT_ID, MATERIAL_ID)).isSameAs(failedResponse);

        verify(transactionService).fail(any(MaterialParseTransactionService.ParseFailure.class));
        verify(transactionService, never()).complete(any());
        verify(aiWorkflowGateway, never()).analyzeMaterial(any());
    }

    @Test
    void processingResultIsRejectedBeforeExternalWork() {
        MaterialParseTransactionService transactionService = new MaterialParseTransactionService(
                materialService,
                materialRepository,
                purposeRepository,
                parseResultRepository,
                knowledgeIndexService
        );
        UploadedMaterial material = new UploadedMaterial();
        MaterialPurpose purpose = new MaterialPurpose();
        purpose.setMaterialId(MATERIAL_ID);
        purpose.setPurposeType(PurposeType.TEXTBOOK_BASIS);
        ParseResult processingResult = new ParseResult();
        processingResult.setParseStatus(MaterialParseStatus.PROCESSING);

        when(materialService.requireConfirmedSummary(PROJECT_ID)).thenReturn(new RequirementSummary());
        when(materialService.requireMaterial(PROJECT_ID, MATERIAL_ID)).thenReturn(material);
        when(purposeRepository.findByMaterialIdOrderByIdAsc(MATERIAL_ID)).thenReturn(List.of(purpose));
        when(parseResultRepository.findFirstByMaterialIdOrderByCreatedAtDescIdDesc(MATERIAL_ID))
                .thenReturn(Optional.of(processingResult));

        assertThatThrownBy(() -> transactionService.prepare(PROJECT_ID, MATERIAL_ID, false, false))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Material parsing is already in progress");

        verify(materialRepository, never()).saveAndFlush(any());
        verify(parseResultRepository, never()).saveAndFlush(any());
    }

    private boolean hasTransactionalAnnotation(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = MaterialParseService.class.getMethod(methodName, parameterTypes);
        return method.isAnnotationPresent(Transactional.class);
    }
}
