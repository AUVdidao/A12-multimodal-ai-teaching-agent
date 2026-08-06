package com.auvdidao.a12teachingagent.workspace;

import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.DialogRole;
import com.auvdidao.a12teachingagent.domain.common.ExportType;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.dialog.DialogMessage;
import com.auvdidao.a12teachingagent.domain.dialog.repository.DialogMessageRepository;
import com.auvdidao.a12teachingagent.domain.exportrecord.ExportRecord;
import com.auvdidao.a12teachingagent.domain.exportrecord.repository.ExportRecordRepository;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntentEvidence;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementInputRepository;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Rollback
class WorkspaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequirementInputRepository requirementRepository;

    @Autowired
    private RequirementSummaryRepository summaryRepository;

    @Autowired
    private DialogMessageRepository dialogRepository;

    @Autowired
    private UploadedMaterialRepository materialRepository;

    @Autowired
    private MaterialPurposeRepository purposeRepository;

    @Autowired
    private ParseResultRepository parseResultRepository;

    @Autowired
    private KnowledgeChunkRepository chunkRepository;

    @Autowired
    private TeachingIntentRepository intentRepository;

    @Autowired
    private GeneratedArtifactRepository artifactRepository;

    @Autowired
    private ArtifactVersionRepository versionRepository;

    @Autowired
    private ExportRecordRepository exportRepository;

    @Test
    void teacherWorkspaceAndProjectPageUsePersistedProjects() throws Exception {
        Project project = createProject("人工智能基础概念与应用");
        createRequirement(project, false);

        mockMvc.perform(get("/api/workspace/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.projectCount", is(1)))
                .andExpect(jsonPath("$.data.metrics.pendingTaskCount", is(1)))
                .andExpect(jsonPath("$.data.continueProjects[0].projectName", is("人工智能基础概念与应用")))
                .andExpect(jsonPath("$.data.continueProjects[0].stage", is("REQUIREMENTS")))
                .andExpect(jsonPath("$.data.pendingTasks[0].derived", is(true)))
                .andExpect(jsonPath("$.data.pendingTasks[0].actionPath", containsString("/requirements")));

        mockMvc.perform(get("/api/workspace/projects")
                        .param("query", "人工智能")
                        .param("stage", "REQUIREMENTS")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.items[0].id", is(project.getId().intValue())))
                .andExpect(jsonPath("$.data.items[0].progress", greaterThan(0)));

        mockMvc.perform(get("/api/workspace/projects")
                        .param("stage", "\u9700\u6c42\u6f84\u6e05\u4e2d")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.items[0].stage", is("REQUIREMENTS")));
    }

    @Test
    void requirementWorkspaceReturnsNineFieldCompletenessAndCanClearDialogues() throws Exception {
        Project project = createProject("机器学习入门");
        RequirementInput requirement = createRequirement(project, true);
        createDialogue(project, "workspace-session", DialogRole.TEACHER, "希望案例丰富一些", 1);
        createDialogue(project, "workspace-session", DialogRole.ASSISTANT, "已记录教学风格。", 1);

        mockMvc.perform(get("/api/projects/{projectId}/requirements/workspace", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestRequirement.id", is(requirement.getId().intValue())))
                .andExpect(jsonPath("$.data.latestRequirement.baselineLevel", is("有编程基础，对 AI 了解不多")))
                .andExpect(jsonPath("$.data.latestRequirement.stylePreference", is("活泼，案例为主")))
                .andExpect(jsonPath("$.data.latestRequirement.interactionType", is("课堂问答互动")))
                .andExpect(jsonPath("$.data.completeness.total", is(9)))
                .andExpect(jsonPath("$.data.completeness.collected", is(9)))
                .andExpect(jsonPath("$.data.completeness.percentage", is(100)))
                .andExpect(jsonPath("$.data.completeness.complete", is(true)))
                .andExpect(jsonPath("$.data.dialogues", hasSize(2)))
                .andExpect(jsonPath("$.data.canGenerateSummary", is(true)));

        mockMvc.perform(delete("/api/projects/{projectId}/dialogues", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedCount", is(2)));

        mockMvc.perform(get("/api/projects/{projectId}/dialogues", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void emptyOutputTypesKeepRequirementIncomplete() throws Exception {
        Project project = createProject("光合作用");
        RequirementInput requirement = createRequirement(project, true);
        requirement.setOutputTypes(List.of());
        requirementRepository.save(requirement);

        mockMvc.perform(get("/api/projects/{projectId}/requirements/workspace", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completeness.total", is(9)))
                .andExpect(jsonPath("$.data.completeness.complete", is(false)))
                .andExpect(jsonPath("$.data.completeness.collected", is(8)))
                .andExpect(jsonPath("$.data.completeness.percentage", is(88)));
    }

    @Test
    void extendedRequirementFieldsFlowIntoSummaryWorkspace() throws Exception {
        Project project = createProject("人工智能伦理");

        mockMvc.perform(post("/api/projects/{projectId}/requirements", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gradeLevel": "大学一年级",
                                  "subject": "人工智能",
                                  "topic": "人工智能伦理",
                                  "baselineLevel": "了解基本人工智能概念",
                                  "lessonDuration": "2课时",
                                  "teachingGoals": "理解人工智能伦理风险",
                                  "keyPoints": "公平与透明",
                                  "difficultPoints": "责任边界",
                                  "stylePreference": "案例研讨",
                                  "interactionType": "小组辩论",
                                  "outputTypes": ["PPT", "DOCX"],
                                  "rawRequirementText": "设计一节人工智能伦理课程"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baselineLevel", is("了解基本人工智能概念")))
                .andExpect(jsonPath("$.data.interactionType", is("小组辩论")));

        mockMvc.perform(post("/api/projects/{projectId}/requirement-summaries/generate", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baselineLevel", is("了解基本人工智能概念")))
                .andExpect(jsonPath("$.data.stylePreference", is("案例研讨")))
                .andExpect(jsonPath("$.data.interactionType", is("小组辩论")));

        mockMvc.perform(get("/api/projects/{projectId}/requirement-summaries/workspace", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.topic", is("人工智能伦理")))
                .andExpect(jsonPath("$.data.summary.baselineLevel", is("了解基本人工智能概念")))
                .andExpect(jsonPath("$.data.source.sourceType", is("TEACHER_REQUIREMENT")))
                .andExpect(jsonPath("$.data.canConfirm", is(true)));
    }

    @Test
    void materialWorkspaceReportsWhetherSummaryGateIsSatisfied() throws Exception {
        Project project = createProject("上传策略状态");

        mockMvc.perform(get("/api/projects/{projectId}/materials/workspace", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadPolicy.requiresConfirmedSummary", is(true)))
                .andExpect(jsonPath("$.data.uploadPolicy.uploadEnabled", is(false)));

        RequirementInput requirement = createRequirement(project, true);
        createSummary(project, requirement, true);

        mockMvc.perform(get("/api/projects/{projectId}/materials/workspace", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadPolicy.uploadEnabled", is(true)));
    }

    @Test
    void materialAndKnowledgeWorkspacesExposeRealParseAndSourceData() throws Exception {
        Project project = createProject("机器学习中的过拟合");
        RequirementInput requirement = createRequirement(project, true);
        createSummary(project, requirement, true);
        UploadedMaterial material = createParsedMaterial(project, "机器学习基础.pdf");
        ParseResult parseResult = parseResultRepository
                .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(material.getId())
                .orElseThrow();
        parseResult.setSections(List.of(
                "Section 01", "Section 02", "Section 03", "Section 04",
                "Section 05", "Section 06", "Section 07", "Section 08"
        ));
        parseResultRepository.saveAndFlush(parseResult);
        createKnowledgeChunk(project, material, "过拟合的定义与解决方法", "过拟合会降低模型在新数据上的泛化能力。", List.of("过拟合", "泛化"));

        mockMvc.perform(get("/api/projects/{projectId}/materials/workspace", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadPolicy.maxFileSizeMb", is(20)))
                .andExpect(jsonPath("$.data.uploadPolicy.supportedExtensions", hasItem("mp4")))
                .andExpect(jsonPath("$.data.uploadPolicy.uploadEnabled", is(true)))
                .andExpect(jsonPath("$.data.statistics.total", is(1)))
                .andExpect(jsonPath("$.data.statistics.parsed", is(1)))
                .andExpect(jsonPath("$.data.statistics.indexed", is(1)))
                .andExpect(jsonPath("$.data.materials[0].parsePreview.summary", containsString("机器学习")))
                .andExpect(jsonPath("$.data.materials[0].parsePreview.sectionCount", is(8)))
                .andExpect(jsonPath("$.data.materials[0].parsePreview.sectionsPreview", hasSize(6)))
                .andExpect(jsonPath("$.data.materials[0].parsePreview.sectionsPreview[0]", is("Section 01")))
                .andExpect(jsonPath("$.data.materials[0].parsePreview.sectionsPreview[5]", is("Section 06")))
                .andExpect(jsonPath("$.data.materials[0].parsePreview.sections").doesNotExist())
                .andExpect(jsonPath("$.data.materials[0].usageTypes", contains("TEXTBOOK_BASIS")));

        mockMvc.perform(get("/api/projects/{projectId}/materials/{materialId}/parse-result",
                        project.getId(), material.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sections", contains(
                        "Section 01", "Section 02", "Section 03", "Section 04",
                        "Section 05", "Section 06", "Section 07", "Section 08"
                )));

        mockMvc.perform(post("/api/projects/{projectId}/knowledge/workspace-search", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "过拟合",
                                  "matchMode": "PRECISE",
                                  "caseSensitive": false,
                                  "page": 0,
                                  "size": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.hits[0].scorePercent", greaterThan(0)))
                .andExpect(jsonPath("$.data.hits[0].sourceFilename", is("机器学习基础.pdf")))
                .andExpect(jsonPath("$.data.hits[0].sourceLocation", is("知识片段 #1")))
                .andExpect(jsonPath("$.data.hits[0].hitReason", containsString("关键词")));
    }

    @Test
    void projectOverviewAndStructuredIntentUseRealCountsAndRemainConfirmable() throws Exception {
        Project project = createProject("人工智能基础概念与应用");
        RequirementInput requirement = createRequirement(project, true);
        RequirementSummary summary = createSummary(project, requirement, true);
        UploadedMaterial material = createParsedMaterial(project, "人工智能导论.pdf");
        KnowledgeChunk chunk = createKnowledgeChunk(
                project, material, "人工智能基本概念", "人工智能研究使机器表现出智能行为的方法。", List.of("人工智能")
        );
        TeachingIntent intent = createIntent(project, summary, material, chunk);
        createGeneratedData(project);

        mockMvc.perform(get("/api/projects/{projectId}/workspace-overview", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.uploadedMaterialCount", is(1)))
                .andExpect(jsonPath("$.data.metrics.knowledgeChunkCount", is(1)))
                .andExpect(jsonPath("$.data.metrics.pptCount", is(1)))
                .andExpect(jsonPath("$.data.metrics.versionCount", is(1)))
                .andExpect(jsonPath("$.data.timeline", hasSize(5)))
                .andExpect(jsonPath("$.data.timeline[2].code", is("OUTLINE")))
                .andExpect(jsonPath("$.data.timeline[2].state", is("CURRENT")))
                .andExpect(jsonPath("$.data.timeline[3].code", is("LESSON_PLAN")))
                .andExpect(jsonPath("$.data.timeline[3].state", is("PENDING")))
                .andExpect(jsonPath("$.data.timeline[3].completedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.quickActions[2].enabled", is(true)));

        mockMvc.perform(get("/api/projects/{projectId}/teaching-intents/workspace", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent.id", is(intent.getId().intValue())))
                .andExpect(jsonPath("$.data.options.generationGoals", hasSize(5)))
                .andExpect(jsonPath("$.data.evidenceCount", is(1)));

        mockMvc.perform(put("/api/projects/{projectId}/teaching-intents/{intentId}/workspace", project.getId(), intent.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "generationGoals": ["知识理解", "应用能力"],
                                  "primaryBasis": "官方课程大纲",
                                  "supplementalBasis": ["人工智能导论.pdf"],
                                  "targetAudience": "大学本科一年级",
                                  "totalHours": 16,
                                  "teachingFormat": "线上线下混合式教学",
                                  "outputTypes": ["教学PPT", "课堂活动"],
                                  "stylePreference": "案例为主",
                                  "notes": "突出重点与责任意识"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent.generationGoals", contains("知识理解", "应用能力")))
                .andExpect(jsonPath("$.data.intent.primaryBasis", is("官方课程大纲")))
                .andExpect(jsonPath("$.data.intent.totalHours", is(16)))
                .andExpect(jsonPath("$.data.intent.notes", is("突出重点与责任意识")))
                .andExpect(jsonPath("$.data.canConfirm", is(true)));

        mockMvc.perform(post("/api/projects/{projectId}/teaching-intents/{intentId}/confirm", project.getId(), intent.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CONFIRMED")));
    }

    @Test
    void workspaceEndpointsRejectUnknownProjectsAndInvalidSearchMode() throws Exception {
        mockMvc.perform(get("/api/projects/999999/workspace-overview"))
                .andExpect(status().isNotFound());

        Project project = createProject("检索边界测试");
        mockMvc.perform(post("/api/projects/{projectId}/knowledge/workspace-search", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"测试", "matchMode":"VECTOR"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private Project createProject(String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setCourseName("人工智能基础");
        project.setChapterTopic(name);
        project.setTargetAudience("大学本科一年级");
        project.setLessonDurationMinutes(90);
        project.setProjectDescription("理解人工智能的基本概念、发展历程与典型应用");
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.CREATED);
        return projectRepository.save(project);
    }

    private RequirementInput createRequirement(Project project, boolean complete) {
        RequirementInput requirement = new RequirementInput();
        requirement.setProjectId(project.getId());
        requirement.setGradeLevel("大学本科一年级");
        requirement.setSubject("人工智能");
        requirement.setTopic(project.getChapterTopic());
        requirement.setLessonDuration("2课时");
        requirement.setTeachingGoals("理解人工智能基本概念并能分析典型应用");
        requirement.setKeyPoints("人工智能定义与发展历程");
        requirement.setDifficultPoints("机器学习与深度学习的区别");
        requirement.setOutputTypes(List.of("PPT", "DOCX", "INTERACTION"));
        requirement.setRawRequirementText("设计一节人工智能基础课程");
        if (complete) {
            requirement.setBaselineLevel("有编程基础，对 AI 了解不多");
            requirement.setStylePreference("活泼，案例为主");
            requirement.setInteractionType("课堂问答互动");
        }
        return requirementRepository.save(requirement);
    }

    private RequirementSummary createSummary(Project project, RequirementInput requirement, boolean confirmed) {
        RequirementSummary summary = new RequirementSummary();
        summary.setProjectId(project.getId());
        summary.setSourceRequirementId(requirement.getId());
        summary.setGradeLevel(requirement.getGradeLevel());
        summary.setSubject(requirement.getSubject());
        summary.setTopic(requirement.getTopic());
        summary.setBaselineLevel(requirement.getBaselineLevel());
        summary.setLessonDuration(requirement.getLessonDuration());
        summary.setTeachingGoals(requirement.getTeachingGoals());
        summary.setKeyPoints(requirement.getKeyPoints());
        summary.setDifficultPoints(requirement.getDifficultPoints());
        summary.setOutputTypes(requirement.getOutputTypes());
        summary.setStylePreference(requirement.getStylePreference());
        summary.setInteractionType(requirement.getInteractionType());
        summary.setGenerationMode(GenerationMode.STANDARD);
        summary.setStatus(confirmed ? RequirementSummaryStatus.CONFIRMED : RequirementSummaryStatus.DRAFT);
        if (confirmed) summary.setConfirmedAt(LocalDateTime.now());
        return summaryRepository.save(summary);
    }

    private void createDialogue(Project project, String session, DialogRole role, String content, int round) {
        DialogMessage message = new DialogMessage();
        message.setProjectId(project.getId());
        message.setSessionId(session);
        message.setRole(role);
        message.setContent(content);
        message.setRoundNo(round);
        dialogRepository.save(message);
    }

    private UploadedMaterial createParsedMaterial(Project project, String filename) {
        UploadedMaterial material = new UploadedMaterial();
        material.setProjectId(project.getId());
        material.setFileName("stored-" + filename);
        material.setOriginalFileName(filename);
        material.setFileExtension("pdf");
        material.setContentType("application/pdf");
        material.setFileType(MaterialFileType.PDF);
        material.setFilePath("tests/" + filename);
        material.setFileSize(1024L);
        material.setUploadStatus(UploadStatus.PARSED);
        material.setParseStatus(MaterialParseStatus.SUCCEEDED);
        material = materialRepository.save(material);

        MaterialPurpose purpose = new MaterialPurpose();
        purpose.setProjectId(project.getId());
        purpose.setMaterialId(material.getId());
        purpose.setPurposeType(PurposeType.TEXTBOOK_BASIS);
        purpose.setPurposeDescription("课程核心教材");
        purposeRepository.save(purpose);

        ParseResult result = new ParseResult();
        result.setMaterialId(material.getId());
        result.setParseStatus(MaterialParseStatus.SUCCEEDED);
        result.setSummary("机器学习与人工智能基本概念的确定性原型摘要");
        result.setKeywords(List.of("人工智能", "机器学习", "过拟合"));
        result.setApplicableTeachingStages(List.of("概念讲解", "案例分析"));
        result.setParsedAt(LocalDateTime.now());
        parseResultRepository.save(result);
        return material;
    }

    private KnowledgeChunk createKnowledgeChunk(
            Project project,
            UploadedMaterial material,
            String title,
            String content,
            List<String> keywords
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setProjectId(project.getId());
        chunk.setMaterialId(material.getId());
        chunk.setChunkNo(1);
        chunk.setTitle(title);
        chunk.setContent(content);
        chunk.setKeywords(keywords);
        chunk.setUsageTypes(List.of(PurposeType.TEXTBOOK_BASIS));
        chunk.setSourceFilename(material.getOriginalFileName());
        return chunkRepository.save(chunk);
    }

    private TeachingIntent createIntent(
            Project project,
            RequirementSummary summary,
            UploadedMaterial material,
            KnowledgeChunk chunk
    ) {
        TeachingIntentEvidence evidence = new TeachingIntentEvidence();
        evidence.setMaterialId(material.getId());
        evidence.setKnowledgeChunkId(chunk.getId());
        evidence.setSourceFilename(material.getOriginalFileName());
        evidence.setUsageTypes(PurposeType.TEXTBOOK_BASIS.name());
        evidence.setHitReason("命中项目主题");
        evidence.setContentExcerpt(chunk.getContent());

        TeachingIntent intent = new TeachingIntent();
        intent.setProjectId(project.getId());
        intent.setRequirementSummaryId(summary.getId());
        intent.setGenerationGoal("理解人工智能基本概念");
        intent.setContentBasis("已确认需求与本地知识库");
        intent.setTeachingApproach("案例讲解与课堂讨论");
        intent.setInteractionMode("课堂问答");
        intent.setOutputTypes(List.of("PPT", "DOCX"));
        intent.setStylePreference("案例为主");
        intent.setEvidenceItems(List.of(evidence));
        intent.setStatus(TeachingIntentStatus.DRAFT);
        return intentRepository.save(intent);
    }

    private void createGeneratedData(Project project) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setProjectId(project.getId());
        artifact.setArtifactType(ArtifactType.PPT);
        artifact.setTitle("人工智能基础教学PPT");
        artifact.setContentJson("{}");
        artifactRepository.save(artifact);

        ArtifactVersion version = new ArtifactVersion();
        version.setProjectId(project.getId());
        version.setVersionNumber(1);
        version.setDescription("首个生成版本");
        version.setFinalVersion(false);
        versionRepository.save(version);

        ExportRecord export = new ExportRecord();
        export.setProjectId(project.getId());
        export.setExportType(ExportType.PPTX);
        export.setFileName("人工智能基础.pptx");
        export.setFilePath("tests/人工智能基础.pptx");
        exportRepository.save(export);
    }
}
