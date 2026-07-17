package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Rollback
class M2WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequirementSummaryRepository summaryRepository;

    @Autowired
    private KnowledgeChunkRepository chunkRepository;

    @Autowired
    private StorageProperties storageProperties;

    @BeforeEach
    void prepareStorage() throws IOException {
        deleteStorage();
    }

    @AfterEach
    void cleanStorage() throws IOException {
        deleteStorage();
    }

    @Test
    void parseRequiresUsageBinding() throws Exception {
        Project project = createProject("光合作用", true);
        long materialId = upload(project.getId(), "光合作用教材.pdf");

        mockMvc.perform(post("/api/projects/{projectId}/materials/{materialId}/parse", project.getId(), materialId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("usage")));
    }

    @Test
    void parseRequiresConfirmedSummary() throws Exception {
        Project project = createProject("光合作用", false);

        mockMvc.perform(post("/api/projects/{projectId}/materials/1/parse", project.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("confirmed requirement summary")));
    }

    @Test
    void returnsNotStartedBeforeParsing() throws Exception {
        Project project = createProject("光合作用", true);
        long materialId = upload(project.getId(), "光合作用教材.pdf");

        mockMvc.perform(get("/api/projects/{projectId}/materials/{materialId}/parse-result", project.getId(), materialId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", nullValue()))
                .andExpect(jsonPath("$.data.parseStatus", is("NOT_STARTED")))
                .andExpect(jsonPath("$.data.prototype", is(true)));
    }

    @Test
    void parsesDeterministicallyAndCreatesThreeDistinctChunks() throws Exception {
        Pipeline pipeline = parsedPipeline("光合作用", "光合作用教材.pdf", List.of("TEXTBOOK_BASIS", "CASE_MATERIAL"));

        mockMvc.perform(get(
                        "/api/projects/{projectId}/materials/{materialId}/parse-result",
                        pipeline.projectId(),
                        pipeline.materialId()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.data.summary", containsString("确定性原型摘要")))
                .andExpect(jsonPath("$.data.summary", containsString("光合作用")))
                .andExpect(jsonPath("$.data.keywords.length()", greaterThan(2)))
                .andExpect(jsonPath("$.data.applicableTeachingStages", hasItems(
                        "概念讲解",
                        "课堂导入",
                        "案例分析",
                        "作为导入案例"
                )));

        mockMvc.perform(get("/api/projects/{projectId}/knowledge/overview", pipeline.projectId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexedMaterialCount", is(1)))
                .andExpect(jsonPath("$.data.chunkCount", is(3)))
                .andExpect(jsonPath("$.data.chunks[0].title", containsString("核心摘要")))
                .andExpect(jsonPath("$.data.chunks[1].title", containsString("教学应用")))
                .andExpect(jsonPath("$.data.chunks[2].title", containsString("目标关联")));
    }

    @Test
    void repeatedParseAndIndexAreIdempotent() throws Exception {
        Pipeline pipeline = parsedPipeline("光合作用", "光合作用教材.pdf", List.of("TEXTBOOK_BASIS"));
        Number firstResultId = JsonPath.read(parse(pipeline.projectId(), pipeline.materialId()).andReturn()
                .getResponse().getContentAsString(), "$.data.id");

        parse(pipeline.projectId(), pipeline.materialId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(firstResultId.intValue())));
        index(pipeline.projectId(), pipeline.materialId()).andExpect(jsonPath("$.data", hasSize(3)));
        index(pipeline.projectId(), pipeline.materialId()).andExpect(jsonPath("$.data", hasSize(3)));

        org.assertj.core.api.Assertions.assertThat(chunkRepository.countByProjectId(pipeline.projectId())).isEqualTo(3);
    }

    @Test
    void refusesToIndexUnparsedMaterial() throws Exception {
        Project project = createProject("光合作用", true);
        long materialId = upload(project.getId(), "光合作用教材.pdf");
        bindUsages(project.getId(), materialId, List.of("TEXTBOOK_BASIS"));

        index(project.getId(), materialId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(409)));
    }

    @Test
    void searchMatchesKeywordsAndExplainsRealSource() throws Exception {
        Pipeline pipeline = parsedPipeline("光合作用", "光合作用教材.pdf", List.of("TEXTBOOK_BASIS"));

        search(pipeline.projectId(), "光合作用", 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prototype", is(true)))
                .andExpect(jsonPath("$.data.algorithm", containsString("确定性")))
                .andExpect(jsonPath("$.data.hits", not(empty())))
                .andExpect(jsonPath("$.data.hits[0].sourceFilename", is("光合作用教材.pdf")))
                .andExpect(jsonPath("$.data.hits[0].hitReason", not("")))
                .andExpect(jsonPath("$.data.hits[0].score", greaterThan(0.0)));
    }

    @Test
    void searchUsesTitleContentAndUsageWeights() throws Exception {
        Pipeline pipeline = parsedPipeline("一次函数", "一次函数案例.pdf", List.of("TEXTBOOK_BASIS", "CASE_MATERIAL"));

        search(pipeline.projectId(), "教材依据", 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits", not(empty())))
                .andExpect(jsonPath("$.data.hits[0].hitReason", containsString("教材依据")));
        search(pipeline.projectId(), "教学应用", 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits[0].title", containsString("教学应用")));
        search(pipeline.projectId(), "教师已确认", 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits", not(empty())));
    }

    @Test
    void searchIsStableAndHonorsLimit() throws Exception {
        Pipeline pipeline = parsedPipeline("光合作用", "光合作用教材.pdf", List.of("TEXTBOOK_BASIS"));
        MvcResult first = search(pipeline.projectId(), "光合作用", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits", hasSize(1)))
                .andReturn();
        MvcResult second = search(pipeline.projectId(), "光合作用", 1).andReturn();

        Number firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.data.hits[0].chunkId");
        Number secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.data.hits[0].chunkId");
        org.assertj.core.api.Assertions.assertThat(secondId.longValue()).isEqualTo(firstId.longValue());
    }

    @Test
    void searchRejectsBlankQueryAndInvalidLimit() throws Exception {
        Project project = createProject("光合作用", true);

        search(project.getId(), " ", 10).andExpect(status().isBadRequest());
        search(project.getId(), "光合作用", 0).andExpect(status().isBadRequest());
        search(project.getId(), "光合作用", 21).andExpect(status().isBadRequest());
    }

    @Test
    void searchReturnsEmptyInsteadOfInventingHits() throws Exception {
        Pipeline pipeline = parsedPipeline("光合作用", "光合作用教材.pdf", List.of("TEXTBOOK_BASIS"));

        search(pipeline.projectId(), "量子宇宙飞船完全无关词", 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits", hasSize(0)));
    }

    @Test
    void knowledgeIsIsolatedAcrossProjects() throws Exception {
        Pipeline biology = parsedPipeline("光合作用", "生物教材.pdf", List.of("TEXTBOOK_BASIS"));
        Pipeline math = parsedPipeline("一次函数", "数学教材.pdf", List.of("TEXTBOOK_BASIS"));

        search(math.projectId(), "光合作用", 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits", hasSize(0)));
        search(biology.projectId(), "光合作用", 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits[0].sourceFilename", is("生物教材.pdf")));
    }

    @Test
    void knowledgeRejectsMissingProject() throws Exception {
        search(999999L, "光合作用", 10).andExpect(status().isNotFound());
    }

    @Test
    void teachingIntentRequiresMaterialParsedDataAndKnowledge() throws Exception {
        Project noMaterial = createProject("光合作用", true);
        mockMvc.perform(post("/api/projects/{projectId}/teaching-intents/generate", noMaterial.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("uploaded material")));

        Project unparsed = createProject("一次函数", true);
        long materialId = upload(unparsed.getId(), "一次函数教材.pdf");
        bindUsages(unparsed.getId(), materialId, List.of("TEXTBOOK_BASIS"));
        mockMvc.perform(post("/api/projects/{projectId}/teaching-intents/generate", unparsed.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("successfully parsed")));

        parse(unparsed.getId(), materialId).andExpect(status().isOk());
        chunkRepository.deleteByMaterialId(materialId);
        chunkRepository.flush();
        mockMvc.perform(post("/api/projects/{projectId}/teaching-intents/generate", unparsed.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Knowledge chunks")));
    }

    @Test
    void teachingIntentRequiresActualSearchHit() throws Exception {
        Pipeline pipeline = parsedPipeline("光合作用", "光合作用教材.pdf", List.of("TEXTBOOK_BASIS"));
        RequirementSummary summary = summaryRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(pipeline.projectId()).orElseThrow();
        summary.setTopic("量子宇宙飞船完全无关词");
        summaryRepository.saveAndFlush(summary);

        mockMvc.perform(post("/api/projects/{projectId}/teaching-intents/generate", pipeline.projectId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("search hit")));
    }

    @Test
    void generatesRefreshSafeEvidenceBackedTeachingIntent() throws Exception {
        Pipeline pipeline = parsedPipeline("光合作用", "光合作用教材.pdf", List.of("TEXTBOOK_BASIS", "CASE_MATERIAL"));
        MvcResult first = generateIntent(pipeline.projectId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.data.generationGoal", containsString("光合作用")))
                .andExpect(jsonPath("$.data.contentBasis", containsString("教师已确认需求为主")))
                .andExpect(jsonPath("$.data.evidenceItems", not(empty())))
                .andExpect(jsonPath("$.data.evidenceItems[0].sourceFilename", is("光合作用教材.pdf")))
                .andExpect(jsonPath("$.data.evidenceItems[0].knowledgeChunkId", notNullValue()))
                .andExpect(jsonPath("$.data.evidenceItems[0].hitReason", not("")))
                .andReturn();

        Number firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.data.id");
        generateIntent(pipeline.projectId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(firstId.intValue())));
        mockMvc.perform(get("/api/projects/{projectId}/teaching-intents/latest", pipeline.projectId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(firstId.intValue())));
        mockMvc.perform(get("/api/projects/{projectId}/teaching-intents/workspace", pipeline.projectId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent.totalHours", is(1)));
    }

    @Test
    void latestIntentIsNullBeforeGeneration() throws Exception {
        Project project = createProject("光合作用", true);

        mockMvc.perform(get("/api/projects/{projectId}/teaching-intents/latest", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", nullValue()));
    }

    @Test
    void editsDraftThenConfirmsIdempotentlyAndLocksIt() throws Exception {
        Pipeline pipeline = parsedPipeline("光合作用", "光合作用教材.pdf", List.of("TEXTBOOK_BASIS"));
        long intentId = intentId(generateIntent(pipeline.projectId()).andReturn());

        updateIntent(pipeline.projectId(), intentId, "探究光合作用条件")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationGoal", is("探究光合作用条件")))
                .andExpect(jsonPath("$.data.status", is("DRAFT")));

        String firstConfirmedAt = JsonPath.read(confirmIntent(pipeline.projectId(), intentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.data.confirmedAt", notNullValue()))
                .andReturn().getResponse().getContentAsString(), "$.data.confirmedAt");
        confirmIntent(pipeline.projectId(), intentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmedAt", is(firstConfirmedAt)));

        updateIntent(pipeline.projectId(), intentId, "确认后不允许编辑")
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(projectRepository.findById(pipeline.projectId()).orElseThrow().getStatus())
                .isEqualTo(ProjectStatus.INTENT_CONFIRMED);
    }

    @Test
    void teachingIntentIsIsolatedAcrossProjects() throws Exception {
        Pipeline owner = parsedPipeline("光合作用", "光合作用教材.pdf", List.of("TEXTBOOK_BASIS"));
        Project other = createProject("一次函数", true);
        long intentId = intentId(generateIntent(owner.projectId()).andReturn());

        updateIntent(other.getId(), intentId, "错误项目")
                .andExpect(status().isNotFound());
        confirmIntent(other.getId(), intentId)
                .andExpect(status().isNotFound());
    }

    private Pipeline parsedPipeline(String topic, String filename, List<String> usages) throws Exception {
        Project project = createProject(topic, true);
        long materialId = upload(project.getId(), filename);
        bindUsages(project.getId(), materialId, usages);
        parse(project.getId(), materialId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus", is("SUCCEEDED")));
        return new Pipeline(project.getId(), materialId);
    }

    private long upload(Long projectId, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                "application/pdf",
                "prototype material".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        Number value = JsonPath.read(mockMvc.perform(multipart("/api/projects/{projectId}/materials", projectId).file(file))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
        return value.longValue();
    }

    private void bindUsages(Long projectId, long materialId, List<String> usages) throws Exception {
        String values = usages.stream().map(value -> "\"" + value + "\"").collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(put("/api/projects/{projectId}/materials/{materialId}/usages", projectId, materialId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usageTypes\":[" + values + "],\"note\":\"M2 workflow test\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions parse(Long projectId, long materialId) throws Exception {
        return mockMvc.perform(post("/api/projects/{projectId}/materials/{materialId}/parse", projectId, materialId));
    }

    private org.springframework.test.web.servlet.ResultActions index(Long projectId, long materialId) throws Exception {
        return mockMvc.perform(post("/api/projects/{projectId}/materials/{materialId}/index", projectId, materialId));
    }

    private org.springframework.test.web.servlet.ResultActions search(Long projectId, String query, int limit) throws Exception {
        return mockMvc.perform(post("/api/projects/{projectId}/knowledge/search", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"" + query + "\",\"limit\":" + limit + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions generateIntent(Long projectId) throws Exception {
        return mockMvc.perform(post("/api/projects/{projectId}/teaching-intents/generate", projectId));
    }

    private org.springframework.test.web.servlet.ResultActions updateIntent(Long projectId, long intentId, String goal) throws Exception {
        return mockMvc.perform(put("/api/projects/{projectId}/teaching-intents/{intentId}", projectId, intentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "generationGoal": "%s",
                          "contentBasis": "教师需求优先，资料作为增强证据",
                          "teachingApproach": "概念讲解与案例分析",
                          "interactionMode": "观察、讨论与反馈",
                          "outputTypes": ["PPT", "LESSON_PLAN"],
                          "stylePreference": "清晰自然"
                        }
                        """.formatted(goal)));
    }

    private org.springframework.test.web.servlet.ResultActions confirmIntent(Long projectId, long intentId) throws Exception {
        return mockMvc.perform(post("/api/projects/{projectId}/teaching-intents/{intentId}/confirm", projectId, intentId));
    }

    private long intentId(MvcResult result) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
        return value.longValue();
    }

    private Project createProject(String topic, boolean confirmedSummary) {
        Project project = new Project();
        project.setProjectName("M2 workflow test - " + topic);
        project.setCourseName(topic.equals("一次函数") ? "数学" : "生物");
        project.setChapterTopic(topic);
        project.setTargetAudience("八年级");
        project.setLessonDurationMinutes(45);
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(confirmedSummary ? ProjectStatus.REQUIREMENT_CONFIRMED : ProjectStatus.CREATED);
        project = projectRepository.save(project);

        if (confirmedSummary) {
            RequirementSummary summary = new RequirementSummary();
            summary.setProjectId(project.getId());
            summary.setGradeLevel("八年级");
            summary.setSubject(project.getCourseName());
            summary.setTopic(topic);
            summary.setLessonDuration("45分钟");
            summary.setTeachingGoals("理解并能应用" + topic + "的核心概念");
            summary.setKeyPoints(topic + "的基本规律");
            summary.setDifficultPoints(topic + "的证据推理");
            summary.setOutputTypes(List.of("PPT", "LESSON_PLAN"));
            summary.setStylePreference("清晰自然");
            summary.setGenerationMode(GenerationMode.STANDARD);
            summary.setStatus(RequirementSummaryStatus.CONFIRMED);
            summary.setConfirmedAt(LocalDateTime.now());
            summaryRepository.save(summary);
        }
        return project;
    }

    private Path storageRoot() {
        return Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
    }

    private void deleteStorage() throws IOException {
        Path root = storageRoot();
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }

    private record Pipeline(Long projectId, Long materialId) {
    }
}
