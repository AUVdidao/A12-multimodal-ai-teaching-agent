package com.auvdidao.a12teachingagent.auth;

import com.auvdidao.a12teachingagent.domain.approval.ApprovalRequest;
import com.auvdidao.a12teachingagent.domain.approval.ApprovalStatus;
import com.auvdidao.a12teachingagent.domain.approval.repository.ApprovalRequestRepository;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.common.BaseCreatedEntity;
import com.auvdidao.a12teachingagent.domain.common.DialogRole;
import com.auvdidao.a12teachingagent.domain.common.ExportType;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.InputType;
import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.TaskPriority;
import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.common.TeachingTaskStatus;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.course.ClassGroup;
import com.auvdidao.a12teachingagent.domain.course.Course;
import com.auvdidao.a12teachingagent.domain.course.repository.ClassGroupRepository;
import com.auvdidao.a12teachingagent.domain.course.repository.CourseRepository;
import com.auvdidao.a12teachingagent.domain.dialog.DialogMessage;
import com.auvdidao.a12teachingagent.domain.dialog.repository.DialogMessageRepository;
import com.auvdidao.a12teachingagent.domain.exportrecord.ExportRecord;
import com.auvdidao.a12teachingagent.domain.exportrecord.repository.ExportRecordRepository;
import com.auvdidao.a12teachingagent.domain.generation.ArtifactVersion;
import com.auvdidao.a12teachingagent.domain.generation.EditRecord;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.generation.GenerationPlan;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntentEvidence;
import com.auvdidao.a12teachingagent.domain.generation.repository.ArtifactVersionRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.EditRecordRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GeneratedArtifactRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.GenerationPlanRepository;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import com.auvdidao.a12teachingagent.domain.identity.AppUser;
import com.auvdidao.a12teachingagent.domain.identity.repository.AppUserRepository;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.ProjectVisit;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectVisitRepository;
import com.auvdidao.a12teachingagent.domain.publication.Publication;
import com.auvdidao.a12teachingagent.domain.publication.PublicationStatus;
import com.auvdidao.a12teachingagent.domain.publication.repository.PublicationRepository;
import com.auvdidao.a12teachingagent.domain.qa.Question;
import com.auvdidao.a12teachingagent.domain.qa.QuestionAnswer;
import com.auvdidao.a12teachingagent.domain.qa.QuestionStatus;
import com.auvdidao.a12teachingagent.domain.qa.repository.QuestionAnswerRepository;
import com.auvdidao.a12teachingagent.domain.qa.repository.QuestionRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementInput;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementInputRepository;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.domain.teachingtask.TeachingTask;
import com.auvdidao.a12teachingagent.domain.teachingtask.repository.TeachingTaskRepository;
import com.auvdidao.a12teachingagent.generation.MockArtifactContentFactory;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.GenerationPlanResponse;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.PlanSection;
import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import com.auvdidao.a12teachingagent.security.A12SecurityProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Order(30)
public class DemoShowcaseSeeder implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoShowcaseSeeder.class);
    private static final String DEMO_COURSE_CODE = "A12-AI-FOUNDATIONS";

    private static final List<DemoProjectSpec> PROJECT_SPECS = List.of(
            new DemoProjectSpec(
                    "人工智能基础概念与应用", "人工智能基础", "机器学习入门", "大学一年级计算机专业",
                    90, "理解人工智能核心概念，并在真实案例中建立负责任的 AI 应用意识。",
                    ProjectStatus.FINALIZED, GenerationMode.QUALITY, 0
            ),
            new DemoProjectSpec(
                    "高中物理：电磁感应专题", "高中物理", "电磁感应", "高二学生",
                    45, "从实验现象出发理解磁通量变化与感应电流方向。",
                    ProjectStatus.REQUIREMENT_CONFIRMED, GenerationMode.STANDARD, 1
            ),
            new DemoProjectSpec(
                    "初中生物：细胞的结构", "初中生物", "细胞结构与功能", "七年级学生",
                    45, "结合显微观察资料认识细胞结构及其功能。",
                    ProjectStatus.MATERIAL_READY, GenerationMode.STANDARD, 2
            ),
            new DemoProjectSpec(
                    "高中数学：立体几何", "高中数学", "空间几何体与线面关系", "高一学生",
                    90, "通过模型观察与推理训练空间想象和规范证明能力。",
                    ProjectStatus.INTENT_CONFIRMED, GenerationMode.HIGH_QUALITY, 3
            ),
            new DemoProjectSpec(
                    "初中化学：氧化还原反应", "初中化学", "氧化还原反应", "九年级学生",
                    45, "从生活现象归纳氧化还原反应特征，并完成课堂检测。",
                    ProjectStatus.GENERATED, GenerationMode.STANDARD, 4
            ),
            new DemoProjectSpec(
                    "小学英语：日常交际用语", "小学英语", "日常问候与表达", "五年级学生",
                    40, "使用情境对话练习常用问候和礼貌表达。",
                    ProjectStatus.CREATED, GenerationMode.ECONOMY, 5
            )
    );

    private final A12SecurityProperties properties;
    private final StorageProperties storageProperties;
    private final AppUserRepository userRepository;
    private final CourseRepository courseRepository;
    private final ClassGroupRepository classGroupRepository;
    private final ProjectRepository projectRepository;
    private final ProjectVisitRepository projectVisitRepository;
    private final RequirementInputRepository requirementInputRepository;
    private final RequirementSummaryRepository requirementSummaryRepository;
    private final DialogMessageRepository dialogMessageRepository;
    private final UploadedMaterialRepository materialRepository;
    private final MaterialPurposeRepository materialPurposeRepository;
    private final ParseResultRepository parseResultRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final TeachingIntentRepository teachingIntentRepository;
    private final GenerationPlanRepository generationPlanRepository;
    private final ArtifactVersionRepository artifactVersionRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final EditRecordRepository editRecordRepository;
    private final ExportRecordRepository exportRecordRepository;
    private final TeachingTaskRepository taskRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final PublicationRepository publicationRepository;
    private final QuestionRepository questionRepository;
    private final QuestionAnswerRepository answerRepository;
    private final MockArtifactContentFactory artifactContentFactory;
    private final ObjectMapper objectMapper;

    public DemoShowcaseSeeder(
            A12SecurityProperties properties,
            StorageProperties storageProperties,
            AppUserRepository userRepository,
            CourseRepository courseRepository,
            ClassGroupRepository classGroupRepository,
            ProjectRepository projectRepository,
            ProjectVisitRepository projectVisitRepository,
            RequirementInputRepository requirementInputRepository,
            RequirementSummaryRepository requirementSummaryRepository,
            DialogMessageRepository dialogMessageRepository,
            UploadedMaterialRepository materialRepository,
            MaterialPurposeRepository materialPurposeRepository,
            ParseResultRepository parseResultRepository,
            KnowledgeChunkRepository knowledgeChunkRepository,
            TeachingIntentRepository teachingIntentRepository,
            GenerationPlanRepository generationPlanRepository,
            ArtifactVersionRepository artifactVersionRepository,
            GeneratedArtifactRepository artifactRepository,
            EditRecordRepository editRecordRepository,
            ExportRecordRepository exportRecordRepository,
            TeachingTaskRepository taskRepository,
            ApprovalRequestRepository approvalRepository,
            PublicationRepository publicationRepository,
            QuestionRepository questionRepository,
            QuestionAnswerRepository answerRepository,
            MockArtifactContentFactory artifactContentFactory,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.classGroupRepository = classGroupRepository;
        this.projectRepository = projectRepository;
        this.projectVisitRepository = projectVisitRepository;
        this.requirementInputRepository = requirementInputRepository;
        this.requirementSummaryRepository = requirementSummaryRepository;
        this.dialogMessageRepository = dialogMessageRepository;
        this.materialRepository = materialRepository;
        this.materialPurposeRepository = materialPurposeRepository;
        this.parseResultRepository = parseResultRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.teachingIntentRepository = teachingIntentRepository;
        this.generationPlanRepository = generationPlanRepository;
        this.artifactVersionRepository = artifactVersionRepository;
        this.artifactRepository = artifactRepository;
        this.editRecordRepository = editRecordRepository;
        this.exportRecordRepository = exportRecordRepository;
        this.taskRepository = taskRepository;
        this.approvalRepository = approvalRepository;
        this.publicationRepository = publicationRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.artifactContentFactory = artifactContentFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isDemoSeedEnabled()) {
            return;
        }

        AppUser leader = requireUser("leader");
        AppUser teacher = requireUser("teacher");
        AppUser student = requireUser("student");
        Course course = courseRepository.findByCourseCodeIgnoreCase(DEMO_COURSE_CODE).orElseThrow();
        ClassGroup classGroup = classGroupRepository.findByCourseIdOrderByClassNameAsc(course.getId())
                .stream().findFirst().orElseThrow();
        course.setCourseName("人工智能基础");
        course.setDescription("面向大学一年级学生的人工智能基础与应用课程");

        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<Project> projects = PROJECT_SPECS.stream()
                .map(spec -> upsertProject(teacher.getId(), spec, now))
                .toList();

        Project completeProject = projects.get(0);
        RequirementSeed completeRequirement = seedRequirement(completeProject, PROJECT_SPECS.get(0), now.minusHours(7));
        seedDialogues(completeProject, now.minusHours(6));
        List<MaterialSeed> completeMaterials = seedMaterials(completeProject, PROJECT_SPECS.get(0), now.minusHours(5));
        TeachingIntent completeIntent = seedIntent(completeProject, completeRequirement.summary(), completeMaterials, now.minusHours(4));
        ArtifactVersion completeVersion = seedGeneration(completeProject, completeIntent, true, now.minusHours(3));

        seedRequirement(projects.get(1), PROJECT_SPECS.get(1), now.minusHours(5));

        RequirementSeed biologyRequirement = seedRequirement(projects.get(2), PROJECT_SPECS.get(2), now.minusHours(5));
        seedMaterials(projects.get(2), PROJECT_SPECS.get(2), now.minusHours(4));

        RequirementSeed mathRequirement = seedRequirement(projects.get(3), PROJECT_SPECS.get(3), now.minusHours(5));
        List<MaterialSeed> mathMaterials = seedMaterials(projects.get(3), PROJECT_SPECS.get(3), now.minusHours(4));
        seedIntent(projects.get(3), mathRequirement.summary(), mathMaterials, now.minusHours(3));

        RequirementSeed chemistryRequirement = seedRequirement(projects.get(4), PROJECT_SPECS.get(4), now.minusHours(5));
        List<MaterialSeed> chemistryMaterials = seedMaterials(projects.get(4), PROJECT_SPECS.get(4), now.minusHours(4));
        TeachingIntent chemistryIntent = seedIntent(
                projects.get(4), chemistryRequirement.summary(), chemistryMaterials, now.minusHours(3)
        );
        ArtifactVersion chemistryVersion = seedGeneration(projects.get(4), chemistryIntent, false, now.minusHours(2));

        projects.get(5).setDeletedAt(now.minusDays(1));
        seedVisits(teacher.getId(), projects, now);
        seedTasks(leader.getId(), teacher.getId(), course, classGroup, projects, now);

        ApprovalRequest approved = seedApproval(
                completeProject, completeVersion, teacher.getId(), leader.getId(), ApprovalStatus.APPROVED, now.minusHours(2)
        );
        seedApproval(
                projects.get(4), chemistryVersion, teacher.getId(), leader.getId(), ApprovalStatus.SUBMITTED, now.minusMinutes(45)
        );
        Publication publication = seedPublication(
                approved, completeProject, completeVersion, course, classGroup, leader.getId(), now.minusMinutes(90)
        );
        seedQuestions(publication, completeProject, student.getId(), teacher.getId(), now);

        projectRepository.saveAll(projects);
        LOGGER.info("Demo showcase seed completed: {} projects, full generation, approval, publication and Q&A data", projects.size());
    }

    private AppUser requireUser(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalStateException("Demo account is missing: " + username));
    }

    private Project upsertProject(Long teacherId, DemoProjectSpec spec, LocalDateTime now) {
        Project project = projectRepository.findByOwnerUserIdAndProjectNameIgnoreCase(teacherId, spec.name())
                .orElseGet(() -> {
                    Project created = new Project();
                    created.setCreatedAt(now.minusDays(7L - spec.order()));
                    created.setUpdatedAt(now.minusHours(spec.order() + 1L));
                    return created;
                });
        project.setProjectName(spec.name());
        project.setCourseName(spec.course());
        project.setChapterTopic(spec.topic());
        project.setTargetAudience(spec.audience());
        project.setLessonDurationMinutes(spec.minutes());
        project.setProjectDescription(spec.description());
        project.setOwnerUserId(teacherId);
        project.setGenerationMode(spec.mode());
        project.setStatus(spec.status());
        if (spec.order() != 5) {
            project.setDeletedAt(null);
        }
        return projectRepository.save(project);
    }

    private RequirementSeed seedRequirement(Project project, DemoProjectSpec spec, LocalDateTime timestamp) {
        RequirementInput input = requirementInputRepository
                .findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId())
                .orElseGet(() -> created(new RequirementInput(), timestamp));
        input.setProjectId(project.getId());
        input.setGradeLevel(spec.audience());
        input.setSubject(spec.course());
        input.setTopic(spec.topic());
        input.setBaselineLevel("具备本学段基础知识，适合从案例和问题出发学习");
        input.setLessonDuration(spec.minutes() + " 分钟");
        input.setTeachingGoals("理解" + spec.topic() + "的核心概念；能够结合案例解释并完成迁移任务");
        input.setKeyPoints(spec.topic() + "的关键概念、条件和分析步骤");
        input.setDifficultPoints("在新情境中选择依据并清晰表达推理过程");
        input.setStylePreference("清晰、活跃、案例驱动");
        input.setInteractionType("课堂问答、小组讨论与即时测验");
        input.setOutputTypes(List.of("OUTLINE", "PPT", "LESSON_PLAN", "ACTIVITY", "ASSESSMENT"));
        input.setRawRequirementText(spec.description());
        input.setContent(spec.description());
        input.setInputType(InputType.TEXT);
        input = requirementInputRepository.save(input);

        RequirementSummary summary = requirementSummaryRepository
                .findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId())
                .orElseGet(() -> created(new RequirementSummary(), timestamp.plusMinutes(30)));
        summary.setProjectId(project.getId());
        summary.setSourceRequirementId(input.getId());
        summary.setGradeLevel(spec.audience());
        summary.setSubject(spec.course());
        summary.setTopic(spec.topic());
        summary.setBaselineLevel(input.getBaselineLevel());
        summary.setLessonDuration(input.getLessonDuration());
        summary.setTeachingGoals(input.getTeachingGoals());
        summary.setKeyPoints(input.getKeyPoints());
        summary.setDifficultPoints(input.getDifficultPoints());
        summary.setOutputTypes(input.getOutputTypes());
        summary.setStylePreference(input.getStylePreference());
        summary.setInteractionType(input.getInteractionType());
        summary.setGenerationMode(spec.mode());
        summary.setStatus(RequirementSummaryStatus.CONFIRMED);
        summary.setConfirmedAt(timestamp.plusMinutes(30));
        summary = requirementSummaryRepository.save(summary);
        return new RequirementSeed(input, summary);
    }

    private void seedDialogues(Project project, LocalDateTime timestamp) {
        String sessionId = "demo-project-" + project.getId() + "-clarification";
        List<DialogLine> lines = List.of(
                new DialogLine(DialogRole.ASSISTANT, 1, "这节课最希望学生理解和掌握什么？"),
                new DialogLine(DialogRole.TEACHER, 1, "理解人工智能、机器学习和深度学习的关系，并能说明典型应用。"),
                new DialogLine(DialogRole.ASSISTANT, 2, "学生目前的基础和计划课时是多少？"),
                new DialogLine(DialogRole.TEACHER, 2, "大学一年级计算机专业学生，2 课时，具备基础编程经验。"),
                new DialogLine(DialogRole.ASSISTANT, 3, "需要怎样的互动形式与输出成果？"),
                new DialogLine(DialogRole.TEACHER, 3, "采用案例讨论和课堂测验，输出 PPT、教案、活动与习题。")
        );
        List<DialogMessage> existing = dialogMessageRepository.findByProjectIdOrderByCreatedAtAscIdAsc(project.getId());
        for (int index = 0; index < lines.size(); index++) {
            DialogLine line = lines.get(index);
            boolean present = existing.stream().anyMatch(message ->
                    message.getRole() == line.role() && line.roundNo().equals(message.getRoundNo()));
            if (!present) {
                DialogMessage message = created(new DialogMessage(), timestamp.plusMinutes(index * 4L));
                message.setProjectId(project.getId());
                message.setSessionId(sessionId);
                message.setRole(line.role());
                message.setRoundNo(line.roundNo());
                message.setContent(line.content());
                dialogMessageRepository.save(message);
            }
        }
    }

    private List<MaterialSeed> seedMaterials(Project project, DemoProjectSpec spec, LocalDateTime timestamp) {
        List<MaterialSeed> result = new ArrayList<>();
        List<MaterialSpec> specs = materialSpecs(spec);
        for (int index = 0; index < specs.size(); index++) {
            result.add(seedMaterial(project, specs.get(index), timestamp.plusMinutes(index * 20L)));
        }
        return List.copyOf(result);
    }

    private MaterialSeed seedMaterial(Project project, MaterialSpec spec, LocalDateTime timestamp) {
        String storageKey = project.getId() + "/" + spec.filename();
        long fileSize = writeDemoMaterial(storageKey, spec);
        UploadedMaterial material = materialRepository
                .findByProjectIdAndOriginalFileNameIgnoreCase(project.getId(), spec.filename())
                .orElseGet(() -> created(new UploadedMaterial(), timestamp));
        material.setProjectId(project.getId());
        material.setFileName(spec.filename());
        material.setOriginalFileName(spec.filename());
        material.setFileExtension("md");
        material.setContentType("text/markdown");
        material.setMaterialDescription(spec.description());
        material.setFileType(MaterialFileType.MD);
        material.setFilePath(storageKey);
        material.setFileSize(fileSize);
        material.setUploadStatus(UploadStatus.PARSED);
        material.setParseStatus(MaterialParseStatus.SUCCEEDED);
        material = materialRepository.save(material);

        if (!materialPurposeRepository.existsByMaterialIdAndPurposeType(material.getId(), spec.purpose())) {
            MaterialPurpose purpose = created(new MaterialPurpose(), timestamp.plusMinutes(2));
            purpose.setProjectId(project.getId());
            purpose.setMaterialId(material.getId());
            purpose.setPurposeType(spec.purpose());
            purpose.setPurposeDescription(spec.description());
            materialPurposeRepository.save(purpose);
        }

        ParseResult parseResult = parseResultRepository
                .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(material.getId())
                .orElseGet(() -> created(new ParseResult(), timestamp.plusMinutes(5)));
        parseResult.setMaterialId(material.getId());
        parseResult.setSummary(spec.summary());
        parseResult.setKeywords(spec.keywords());
        parseResult.setApplicableTeachingStages(List.of("课程导入", "知识讲解", "案例分析", "课堂练习"));
        parseResult.setParseStatus(MaterialParseStatus.SUCCEEDED);
        parseResult.setFailureReason(null);
        parseResult.setParsedAt(timestamp.plusMinutes(5));
        parseResultRepository.save(parseResult);

        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int index = 0; index < spec.chunks().size(); index++) {
            int chunkNo = index + 1;
            ChunkSpec chunkSpec = spec.chunks().get(index);
            LocalDateTime chunkTimestamp = timestamp.plusMinutes(6L + index);
            KnowledgeChunk chunk = knowledgeChunkRepository.findByMaterialIdAndChunkNo(material.getId(), chunkNo)
                    .orElseGet(() -> created(new KnowledgeChunk(), chunkTimestamp));
            chunk.setProjectId(project.getId());
            chunk.setMaterialId(material.getId());
            chunk.setChunkNo(chunkNo);
            chunk.setTitle(chunkSpec.title());
            chunk.setContent(chunkSpec.content());
            chunk.setKeywords(chunkSpec.keywords());
            chunk.setUsageTypes(List.of(spec.purpose(), PurposeType.KNOWLEDGE_SUPPLEMENT));
            chunk.setSourceFilename(spec.filename());
            chunks.add(knowledgeChunkRepository.save(chunk));
        }
        return new MaterialSeed(material, List.copyOf(chunks));
    }

    private TeachingIntent seedIntent(
            Project project,
            RequirementSummary summary,
            List<MaterialSeed> materials,
            LocalDateTime timestamp
    ) {
        TeachingIntent intent = teachingIntentRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId())
                .orElseGet(() -> created(new TeachingIntent(), timestamp));
        intent.setProjectId(project.getId());
        intent.setRequirementSummaryId(summary.getId());
        intent.setGenerationGoal("形成以理解、应用和表达为核心的完整课堂学习体验");
        intent.setGenerationGoals(List.of("知识理解", "概念掌握", "应用能力", "思维提升"));
        intent.setContentBasis("官方课程目标、本地知识片段与上传资料证据");
        intent.setPrimaryBasis("OFFICIAL_OUTLINE");
        intent.setSupplementalBasis(List.of("LOCAL_KNOWLEDGE", "MATERIAL_EVIDENCE"));
        intent.setTeachingApproach("问题驱动、案例分析、讲练结合");
        intent.setInteractionMode("课堂问答、小组讨论、选择题测验");
        intent.setTargetAudience(project.getTargetAudience());
        intent.setTotalHours(Math.max(1, project.getLessonDurationMinutes() / 45));
        intent.setTeachingFormat("线上线下混合式教学");
        intent.setOutputTypes(List.of("OUTLINE", "PPT", "LESSON_PLAN", "ACTIVITY", "ASSESSMENT"));
        intent.setStylePreference("结构清晰、案例充分、适合课堂投影");
        intent.setNotes("优先使用可观察证据连接概念理解与真实应用。演示数据由系统启动时自动补齐。");
        intent.setEvidenceItems(materials.stream().map(this::evidence).toList());
        intent.setStatus(TeachingIntentStatus.CONFIRMED);
        intent.setConfirmedAt(timestamp);
        return teachingIntentRepository.save(intent);
    }

    private TeachingIntentEvidence evidence(MaterialSeed seed) {
        KnowledgeChunk chunk = seed.chunks().get(0);
        TeachingIntentEvidence evidence = new TeachingIntentEvidence();
        evidence.setMaterialId(seed.material().getId());
        evidence.setKnowledgeChunkId(chunk.getId());
        evidence.setSourceFilename(seed.material().getOriginalFileName());
        evidence.setUsageTypes("TEXTBOOK_BASIS,KNOWLEDGE_SUPPLEMENT");
        evidence.setHitReason("资料主题、知识片段关键词与教学目标高度匹配");
        evidence.setContentExcerpt(chunk.getContent());
        return evidence;
    }

    private ArtifactVersion seedGeneration(
            Project project,
            TeachingIntent intent,
            boolean finalVersion,
            LocalDateTime timestamp
    ) {
        List<PlanSection> pptOutline = List.of(
                section(1, "课程导入", "以真实问题激活已有经验并明确学习任务"),
                section(2, "核心概念", "建立概念、条件和关系的结构化理解"),
                section(3, "案例分析", "在真实案例中识别信息、选择依据并解释结论"),
                section(4, "课堂互动", "通过问答、小组讨论和即时测验检查理解"),
                section(5, "迁移练习", "将本节方法迁移到新的任务情境"),
                section(6, "总结评价", "回顾目标、形成结构并布置课后延伸")
        );
        List<PlanSection> docOutline = List.of(
                section(1, "课程基本信息", "记录课程、对象、课时和教学条件"),
                section(2, "教学目标与重难点", "明确可观察、可评价的目标"),
                section(3, "教学过程", "按导入、讲解、案例、互动和总结组织课堂"),
                section(4, "评价与作业", "设置形成性评价、课堂练习和课后任务")
        );
        List<String> interactions = List.of("概念判断投票", "案例证据小组讨论", "三题即时知识检查");

        GenerationPlan plan = generationPlanRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(project.getId())
                .orElseGet(() -> created(new GenerationPlan(), timestamp));
        plan.setProjectId(project.getId());
        plan.setTeachingIntentId(intent.getId());
        plan.setProvider("MOCK");
        plan.setPptOutline(writeJson(pptOutline));
        plan.setDocOutline(writeJson(docOutline));
        plan.setInteractionPlan(writeJson(interactions));
        plan.setConfirmed(true);
        plan = generationPlanRepository.save(plan);

        ArtifactVersion version = artifactVersionRepository
                .findFirstByProjectIdAndGenerationPlanIdOrderByVersionNumberAsc(project.getId(), plan.getId())
                .orElseGet(() -> created(new ArtifactVersion(), timestamp.plusMinutes(10)));
        version.setProjectId(project.getId());
        version.setGenerationPlanId(plan.getId());
        version.setVersionNumber(1);
        version.setDescription(finalVersion ? "已确认的演示成果版本" : "等待审核的生成初稿");
        version.setFinalVersion(finalVersion);
        version = artifactVersionRepository.save(version);

        GenerationPlanResponse response = new GenerationPlanResponse(
                plan.getId(), project.getId(), "MOCK", pptOutline, docOutline, interactions,
                true, plan.getCreatedAt(), plan.getUpdatedAt()
        );
        upsertArtifact(project, plan, version, ArtifactType.PPT, project.getProjectName() + "教学课件",
                artifactContentFactory.buildPpt(project, intent, response), timestamp.plusMinutes(12));
        upsertArtifact(project, plan, version, ArtifactType.DOCX, project.getProjectName() + "教案",
                artifactContentFactory.buildLessonPlan(project, intent, response), timestamp.plusMinutes(13));
        upsertArtifact(project, plan, version, ArtifactType.INTERACTION, project.getProjectName() + "课堂互动",
                artifactContentFactory.buildInteraction(project), timestamp.plusMinutes(14));

        if (editRecordRepository.findByProjectIdOrderByCreatedAtAsc(project.getId()).isEmpty()) {
            EditRecord edit = created(new EditRecord(), timestamp.plusMinutes(20));
            edit.setProjectId(project.getId());
            edit.setVersionId(version.getId());
            edit.setEditInstruction("增加一个贴近学生生活的应用案例，并强化课堂互动。 ");
            edit.setEditResult("已在案例分析和课堂互动部分加入情境任务与即时反馈。 ");
            editRecordRepository.save(edit);
        }
        if (finalVersion) {
            upsertExport(project.getId(), ExportType.PPTX, project.getProjectName() + "-教学课件.pptx", timestamp.plusMinutes(25));
            upsertExport(project.getId(), ExportType.DOCX, project.getProjectName() + "-教案.docx", timestamp.plusMinutes(26));
            upsertExport(project.getId(), ExportType.PACKAGE, project.getProjectName() + "-成果包.zip", timestamp.plusMinutes(27));
        }
        return version;
    }

    private void upsertArtifact(
            Project project,
            GenerationPlan plan,
            ArtifactVersion version,
            ArtifactType type,
            String title,
            Object content,
            LocalDateTime timestamp
    ) {
        GeneratedArtifact artifact = artifactRepository
                .findByProjectIdAndVersionIdAndArtifactType(project.getId(), version.getId(), type)
                .orElseGet(() -> created(new GeneratedArtifact(), timestamp));
        artifact.setProjectId(project.getId());
        artifact.setGenerationPlanId(plan.getId());
        artifact.setVersionId(version.getId());
        artifact.setArtifactType(type);
        artifact.setTitle(title);
        artifact.setSchemaVersion(1);
        artifact.setContentJson(writeJson(content));
        artifact.setFilePath(null);
        artifactRepository.save(artifact);
    }

    private void upsertExport(Long projectId, ExportType type, String fileName, LocalDateTime timestamp) {
        // Existing demo databases may contain historical duplicate export rows.
        // The seed is idempotent, so reuse the oldest record instead of requiring uniqueness.
        ExportRecord record = exportRecordRepository.findFirstByProjectIdAndExportTypeOrderByCreatedAtAsc(projectId, type)
                .orElseGet(() -> created(new ExportRecord(), timestamp));
        record.setProjectId(projectId);
        record.setExportType(type);
        record.setFileName(fileName);
        record.setFilePath("exports/" + projectId + "/" + fileName);
        exportRecordRepository.save(record);
    }

    private void seedVisits(Long teacherId, List<Project> projects, LocalDateTime now) {
        for (int index = 0; index < projects.size() - 1; index++) {
            Project project = projects.get(index);
            LocalDateTime visitCreatedAt = now.minusDays(index + 1L);
            ProjectVisit visit = projectVisitRepository.findByUserIdAndProjectId(teacherId, project.getId())
                    .orElseGet(() -> created(new ProjectVisit(), visitCreatedAt));
            visit.setUserId(teacherId);
            visit.setProjectId(project.getId());
            visit.setLastVisitedAt(now.minusMinutes(index * 35L + 10));
            visit.setVisitCount(8 - index);
            projectVisitRepository.save(visit);
        }
    }

    private void seedTasks(
            Long leaderId,
            Long teacherId,
            Course course,
            ClassGroup classGroup,
            List<Project> projects,
            LocalDateTime now
    ) {
        List<TaskSpec> tasks = List.of(
                new TaskSpec("完善人工智能课程教学需求", projects.get(0), TaskPriority.URGENT, TeachingTaskStatus.IN_PROGRESS, 1),
                new TaskSpec("补充细胞结构课堂实验资料", projects.get(2), TaskPriority.HIGH, TeachingTaskStatus.ASSIGNED, 2),
                new TaskSpec("复核立体几何教学意图", projects.get(3), TaskPriority.MEDIUM, TeachingTaskStatus.SUBMITTED, 3),
                new TaskSpec("整理氧化还原反应互动练习", projects.get(4), TaskPriority.LOW, TeachingTaskStatus.COMPLETED, -1)
        );
        for (TaskSpec spec : tasks) {
            TeachingTask task = taskRepository.findByCreatedByAndTaskNameIgnoreCase(leaderId, spec.name())
                    .orElseGet(() -> created(new TeachingTask(), now.minusDays(2)));
            task.setTaskName(spec.name());
            task.setCourseId(course.getId());
            task.setClassId(classGroup.getId());
            task.setChapterTitle(spec.project().getChapterTopic());
            task.setAssigneeId(teacherId);
            task.setRequirements("基于真实项目数据完成当前阶段工作，并提交可验证结果。 ");
            task.setPriority(spec.priority());
            task.setDueAt(now.plusDays(spec.dueDays()));
            task.setCreatedBy(leaderId);
            task.setLinkedProjectId(spec.project().getId());
            task.setTaskStatus(spec.status());
            if (spec.status() == TeachingTaskStatus.SUBMITTED) {
                task.setSubmissionNote("已完成教学意图草案，等待教研负责人复核。 ");
                task.setSubmittedAt(now.minusHours(2));
            }
            if (spec.status() == TeachingTaskStatus.COMPLETED) {
                task.setCompletedAt(now.minusHours(4));
                task.setReviewNote("内容完整，已通过验收。 ");
            }
            taskRepository.save(task);
        }
    }

    private ApprovalRequest seedApproval(
            Project project,
            ArtifactVersion version,
            Long teacherId,
            Long leaderId,
            ApprovalStatus status,
            LocalDateTime timestamp
    ) {
        ApprovalRequest request = approvalRepository.findByActiveArtifactVersionId(version.getId())
                .orElseGet(() -> created(new ApprovalRequest(), timestamp));
        request.setArtifactVersionId(version.getId());
        request.setActiveArtifactVersionId(version.getId());
        request.setProjectId(project.getId());
        request.setSubmittedBy(teacherId);
        request.setReviewerId(leaderId);
        request.setStatus(status);
        request.setReviewNote(status == ApprovalStatus.APPROVED
                ? "教学目标、证据链和生成成果完整，可以发布到班级。"
                : "等待教研负责人审核生成成果。 ");
        request.setSubmittedAt(timestamp);
        request.setReviewedAt(status == ApprovalStatus.APPROVED ? timestamp.plusMinutes(20) : null);
        return approvalRepository.save(request);
    }

    private Publication seedPublication(
            ApprovalRequest approval,
            Project project,
            ArtifactVersion version,
            Course course,
            ClassGroup classGroup,
            Long leaderId,
            LocalDateTime timestamp
    ) {
        Publication publication = publicationRepository
                .findByApprovalRequestIdAndClassId(approval.getId(), classGroup.getId())
                .orElseGet(() -> created(new Publication(), timestamp));
        publication.setApprovalRequestId(approval.getId());
        publication.setArtifactVersionId(version.getId());
        publication.setProjectId(project.getId());
        publication.setCourseId(course.getId());
        publication.setClassId(classGroup.getId());
        publication.setTitle(project.getProjectName() + "课堂学习包");
        publication.setSummary("包含教学课件、配套教案和三题课堂互动，面向班级学生开放阅读与提问。 ");
        publication.setPublishedBy(leaderId);
        publication.setStatus(PublicationStatus.PUBLISHED);
        publication.setPublishedAt(timestamp);
        publication.setWithdrawnAt(null);
        return publicationRepository.save(publication);
    }

    private void seedQuestions(
            Publication publication,
            Project project,
            Long studentId,
            Long teacherId,
            LocalDateTime now
    ) {
        List<QuestionSpec> questions = List.of(
                new QuestionSpec("机器学习和传统程序有什么区别？", "两者都能完成任务，核心差异体现在哪里？", QuestionStatus.OPEN),
                new QuestionSpec("训练数据为什么会影响模型结果？", "如果样本不均衡，模型可能出现什么问题？", QuestionStatus.ANSWERED),
                new QuestionSpec("如何判断一个 AI 应用是否可靠？", "除了准确率，还应该关注哪些证据和风险？", QuestionStatus.CLOSED)
        );
        for (int index = 0; index < questions.size(); index++) {
            QuestionSpec spec = questions.get(index);
            LocalDateTime questionCreatedAt = now.minusHours(3L - index);
            Question question = questionRepository
                    .findByPublicationIdAndStudentIdAndTitleIgnoreCase(publication.getId(), studentId, spec.title())
                    .orElseGet(() -> created(new Question(), questionCreatedAt));
            question.setPublicationId(publication.getId());
            question.setProjectId(project.getId());
            question.setStudentId(studentId);
            question.setTitle(spec.title());
            question.setContent(spec.content());
            question.setStatus(spec.status());
            question.setAnsweredAt(spec.status() == QuestionStatus.OPEN ? null : now.minusMinutes(50L - index * 10L));
            question.setClosedAt(spec.status() == QuestionStatus.CLOSED ? now.minusMinutes(20) : null);
            question = questionRepository.save(question);
            if (spec.status() != QuestionStatus.OPEN && !answerRepository.existsByQuestionIdAndTeacherId(question.getId(), teacherId)) {
                QuestionAnswer answer = created(new QuestionAnswer(), now.minusMinutes(45L - index * 10L));
                answer.setQuestionId(question.getId());
                answer.setTeacherId(teacherId);
                answer.setContent(index == 1
                        ? "训练数据决定模型能看到的规律。样本不均衡会让模型偏向多数类别，因此要检查分布、补充样本并分组评估。"
                        : "可靠性不仅看准确率，还要看数据来源、适用边界、可解释证据、公平性、隐私和失败后的人工兜底。 ");
                answerRepository.save(answer);
            }
        }
    }

    private long writeDemoMaterial(String storageKey, MaterialSpec spec) {
        Path root = Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("Demo material path escaped the storage directory");
        }
        StringBuilder content = new StringBuilder("# ").append(spec.description()).append("\n\n")
                .append(spec.summary()).append("\n\n");
        for (ChunkSpec chunk : spec.chunks()) {
            content.append("## ").append(chunk.title()).append("\n\n")
                    .append(chunk.content()).append("\n\n");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content.toString(), StandardCharsets.UTF_8);
            return Files.size(target);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write demo material " + storageKey, exception);
        }
    }

    private List<MaterialSpec> materialSpecs(DemoProjectSpec project) {
        String slug = "demo-" + (project.order() + 1);
        return List.of(
                new MaterialSpec(
                        slug + "-course-outline.md",
                        project.topic() + "课程纲要与概念框架",
                        PurposeType.TEXTBOOK_BASIS,
                        "资料梳理了" + project.topic() + "的核心概念、学习目标、适用条件和课堂组织建议。",
                        List.of(project.topic(), "核心概念", "教学目标", "评价"),
                        List.of(
                                new ChunkSpec("核心概念", project.topic() + "需要从定义、条件、关系和典型例子四个层面建立理解。", List.of(project.topic(), "定义", "条件")),
                                new ChunkSpec("学习目标", "学生应能识别关键信息、选择知识依据并用清晰语言解释结论。", List.of("学习目标", "解释", "迁移")),
                                new ChunkSpec("评价建议", "使用课堂提问、案例分析和三题即时测验形成可观察的学习证据。", List.of("评价", "测验", "证据"))
                        )
                ),
                new MaterialSpec(
                        slug + "-case-library.md",
                        project.topic() + "真实案例与课堂活动",
                        PurposeType.CASE_MATERIAL,
                        "资料提供两个真实情境案例、分组讨论提示和课后迁移任务，可直接用于课堂活动。",
                        List.of(project.topic(), "案例教学", "课堂活动", "迁移"),
                        List.of(
                                new ChunkSpec("情境案例", "从学生熟悉的生活或专业场景提出问题，引导识别事实、条件与待解决任务。", List.of("案例", "情境", "问题")),
                                new ChunkSpec("讨论任务", "小组需要给出结论、知识依据和证据，并比较不同方案的适用边界。", List.of("讨论", "证据", "边界")),
                                new ChunkSpec("迁移练习", "更换一个条件后重新判断结果，说明哪些结论保持不变、哪些需要修正。", List.of("迁移", "反思", "修正"))
                        )
                )
        );
    }

    private static PlanSection section(int order, String title, String description) {
        return new PlanSection(order, title, description);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize demo content", exception);
        }
    }

    private static <T extends BaseCreatedEntity> T created(T entity, LocalDateTime timestamp) {
        entity.setCreatedAt(timestamp);
        return entity;
    }

    private record DemoProjectSpec(
            String name,
            String course,
            String topic,
            String audience,
            int minutes,
            String description,
            ProjectStatus status,
            GenerationMode mode,
            int order
    ) {
    }

    private record RequirementSeed(RequirementInput input, RequirementSummary summary) {
    }

    private record MaterialSeed(UploadedMaterial material, List<KnowledgeChunk> chunks) {
    }

    private record MaterialSpec(
            String filename,
            String description,
            PurposeType purpose,
            String summary,
            List<String> keywords,
            List<ChunkSpec> chunks
    ) {
    }

    private record ChunkSpec(String title, String content, List<String> keywords) {
    }

    private record DialogLine(DialogRole role, Integer roundNo, String content) {
    }

    private record TaskSpec(
            String name,
            Project project,
            TaskPriority priority,
            TeachingTaskStatus status,
            int dueDays
    ) {
    }

    private record QuestionSpec(String title, String content, QuestionStatus status) {
    }
}
