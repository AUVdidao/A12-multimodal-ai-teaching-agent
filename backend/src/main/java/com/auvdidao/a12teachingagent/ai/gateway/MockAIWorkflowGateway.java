package com.auvdidao.a12teachingagent.ai.gateway;

import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.ClarificationResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.GenerationPlanResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeRetrievalResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.KnowledgeSnippet;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.MaterialAnalysisResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.PlanSection;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryData;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RequirementSummaryResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RevisionRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.RevisionResponse;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentRequest;
import com.auvdidao.a12teachingagent.ai.dto.AiWorkflowDtos.TeachingIntentResponse;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MockAIWorkflowGateway {

    private static final String WORKFLOW = "mock-ai-workflow";

    public ClarificationResponse clarifyRequirement(ClarificationRequest request) {
        String rawRequirement = defaultString(request.rawRequirement());
        List<String> missingFields = new ArrayList<>();
        Map<String, String> suggestedFields = new LinkedHashMap<>();

        if (!containsAny(rawRequirement, "年级", "学生", "小学", "初中", "高中", "五年级")) {
            missingFields.add("targetAudience");
            suggestedFields.put("targetAudience", "小学五年级");
        }
        if (!containsAny(rawRequirement, "分钟", "课时", "45")) {
            missingFields.add("lessonDurationMinutes");
            suggestedFields.put("lessonDurationMinutes", "40");
        }
        if (!containsAny(rawRequirement, "PPT", "课件", "教案", "互动", "练习")) {
            missingFields.add("outputTypes");
            suggestedFields.put("outputTypes", "PPT, DOCX, INTERACTION");
        }

        List<String> questions = missingFields.stream()
                .map(this::questionForField)
                .toList();

        String nextAction = missingFields.isEmpty()
                ? "需求信息已足够，可以进入结构化摘要确认。"
                : "请先补充缺失字段，再生成需求摘要。";

        return new ClarificationResponse(WORKFLOW, missingFields, questions, suggestedFields, nextAction);
    }

    public RequirementSummaryResponse summarizeRequirement(RequirementSummaryRequest request) {
        String rawRequirement = defaultString(request.rawRequirement());
        RequirementSummaryData summary = new RequirementSummaryData(
                inferCourse(rawRequirement),
                inferTopic(rawRequirement),
                inferAudience(rawRequirement),
                containsAny(rawRequirement, "45", "一课时") ? 45 : 40,
                List.of("理解核心概念", "能够结合真实情境进行解释", "完成课堂互动练习"),
                List.of("概念迁移", "课堂参与度", "生成内容与资料依据的一致性"),
                inferOutputTypes(rawRequirement),
                modeLabel(request.generationMode()),
                containsAny(rawRequirement, "游戏", "互动") ? "互动问答" : "课堂提问"
        );

        return new RequirementSummaryResponse(
                WORKFLOW,
                summary,
                List.of("默认采用一课时课堂节奏", "默认输出课件、教案和一个轻量互动环节"),
                "请确认以上教学需求摘要是否准确，确认后将进入资料上传与知识库增强。"
        );
    }

    public MaterialAnalysisResponse analyzeMaterial(MaterialAnalysisRequest request) {
        String fileName = defaultString(request.fileName());
        String purpose = defaultIfBlank(request.purpose(), "教学知识补充");

        return new MaterialAnalysisResponse(
                WORKFLOW,
                "PARSED",
                "已从 " + fileName + " 中抽取与“" + purpose + "”相关的教学要点，适合作为课件例子和教案依据。",
                List.of("核心概念", "课堂案例", "互动练习", "易错点"),
                List.of("作为导入案例", "补充重点难点解释", "生成课堂互动题"),
                List.of("概念定义片段", "生活化案例片段", "课堂检测题片段")
        );
    }

    public KnowledgeRetrievalResponse retrieveKnowledge(KnowledgeRetrievalRequest request) {
        String courseName = defaultString(request.courseName());
        String chapterTopic = defaultString(request.chapterTopic());

        List<KnowledgeSnippet> snippets = List.of(
                new KnowledgeSnippet(
                        chapterTopic + "核心概念拆解",
                        "uploaded-material-summary",
                        "围绕" + courseName + "《" + chapterTopic + "》提炼概念定义、适用条件和典型误区。",
                        0.96
                ),
                new KnowledgeSnippet(
                        chapterTopic + "生活化案例",
                        "mock-local-knowledge-base",
                        "建议使用贴近学生经验的情境导入，帮助学生把抽象知识迁移到真实问题。",
                        0.91
                ),
                new KnowledgeSnippet(
                        chapterTopic + "课堂互动题",
                        "mock-local-knowledge-base",
                        "设计 3 道由易到难的问题，用于检查学生是否理解重点与难点。",
                        0.88
                )
        );

        return new KnowledgeRetrievalResponse(
                WORKFLOW,
                snippets,
                "Mock 检索已返回 3 条知识片段，后续可替换为真实向量检索结果。"
        );
    }

    public TeachingIntentResponse buildTeachingIntent(TeachingIntentRequest request) {
        RequirementSummaryData summary = request.requirementSummary();
        List<String> outputTypes = summary == null ? List.of("PPT", "DOCX", "INTERACTION") : safeList(summary.outputTypes());

        return new TeachingIntentResponse(
                WORKFLOW,
                "intent-mock-" + request.projectId(),
                List.of("围绕教学目标组织内容", "突出重点难点", "加入可演示的课堂互动"),
                safeList(request.knowledgeSnippets()).stream()
                        .map(snippet -> snippet.title() + "：" + snippet.sourceName())
                        .limit(3)
                        .toList(),
                List.of("课前导入提问", "课堂即时问答", "课末巩固练习"),
                outputTypes.isEmpty() ? List.of("PPT", "DOCX", "INTERACTION") : outputTypes,
                "请确认教学意图、资料依据和输出类型，确认后将生成课件方案。"
        );
    }

    public GenerationPlanResponse createGenerationPlan(GenerationPlanRequest request) {
        String topic = defaultString(request.chapterTopic());
        String audience = defaultIfBlank(request.targetAudience(), "目标学生");

        List<PlanSection> pptOutline = List.of(
                new PlanSection("封面与学习目标", List.of(topic, audience, "本节课学习目标"), "需求摘要"),
                new PlanSection("情境导入", List.of("生活案例", "引导问题", "学生已有经验"), "资料摘要"),
                new PlanSection("核心概念讲解", List.of("概念定义", "关键步骤", "易错点提醒"), "知识片段"),
                new PlanSection("课堂互动", List.of("选择题", "开放提问", "小组讨论"), "Mock 互动方案"),
                new PlanSection("巩固练习", List.of("基础题", "迁移题", "即时反馈"), "知识库命中内容"),
                new PlanSection("总结与作业", List.of("本课总结", "课后练习", "拓展任务"), "教案结构")
        );

        List<PlanSection> docOutline = List.of(
                new PlanSection("教学目标", List.of("知识目标", "能力目标", "素养目标"), "需求摘要"),
                new PlanSection("教学重难点", List.of("重点说明", "难点突破", "易错提醒"), "知识片段"),
                new PlanSection("教学过程", List.of("导入", "讲授", "互动", "练习", "总结"), "生成方案"),
                new PlanSection("评价与作业", List.of("课堂评价", "课后作业", "拓展建议"), "输出要求")
        );

        return new GenerationPlanResponse(
                WORKFLOW,
                "plan-mock-" + request.projectId(),
                pptOutline,
                docOutline,
                List.of("生成 3 道互动问答", "每题包含答案与解析", "支持前端预览和后续导出"),
                request.generationMode() == GenerationMode.HIGH_QUALITY ? "约 45 秒" : "约 15 秒",
                "请确认生成方案，确认后进入 PPT / Word / 互动内容生成。"
        );
    }

    public RevisionResponse reviseArtifact(RevisionRequest request) {
        String instruction = defaultString(request.instruction());

        return new RevisionResponse(
                WORKFLOW,
                "已根据修改意见调整内容表达和互动安排。",
                List.of("学习目标", "课堂互动", "总结练习"),
                request.currentContent() + "\n\n[Mock 修改结果] 已执行修改指令：" + instruction,
                "建议保存为新版本，版本说明：" + instruction
        );
    }

    private String questionForField(String field) {
        return switch (field) {
            case "targetAudience" -> "这节课面向哪个年级或学段的学生？";
            case "lessonDurationMinutes" -> "这节课预计多少分钟或几个课时？";
            case "outputTypes" -> "需要生成 PPT、Word 教案、互动内容中的哪些产物？";
            default -> "请补充 " + field + "。";
        };
    }

    private String inferCourse(String rawRequirement) {
        if (containsAny(rawRequirement, "语文")) {
            return "语文";
        }
        if (containsAny(rawRequirement, "英语")) {
            return "英语";
        }
        if (containsAny(rawRequirement, "科学")) {
            return "科学";
        }
        return "数学";
    }

    private String inferTopic(String rawRequirement) {
        if (containsAny(rawRequirement, "分数")) {
            return "分数的意义";
        }
        if (containsAny(rawRequirement, "面积")) {
            return "图形面积";
        }
        if (containsAny(rawRequirement, "古诗")) {
            return "古诗阅读";
        }
        return "核心知识点讲解";
    }

    private String inferAudience(String rawRequirement) {
        if (containsAny(rawRequirement, "五年级")) {
            return "小学五年级";
        }
        if (containsAny(rawRequirement, "初中")) {
            return "初中学生";
        }
        if (containsAny(rawRequirement, "高中")) {
            return "高中学生";
        }
        return "小学高年级";
    }

    private List<String> inferOutputTypes(String rawRequirement) {
        List<String> outputTypes = new ArrayList<>();
        if (containsAny(rawRequirement, "PPT", "课件")) {
            outputTypes.add("PPT");
        }
        if (containsAny(rawRequirement, "教案", "Word")) {
            outputTypes.add("DOCX");
        }
        if (containsAny(rawRequirement, "互动", "练习", "游戏")) {
            outputTypes.add("INTERACTION");
        }
        return outputTypes.isEmpty() ? List.of("PPT", "DOCX", "INTERACTION") : outputTypes;
    }

    private String modeLabel(GenerationMode generationMode) {
        if (generationMode == null) {
            return "标准教学媒体风格";
        }
        return switch (generationMode) {
            case HIGH_QUALITY -> "高质量图文讲解风格";
            case ECONOMY -> "简洁省时风格";
            case MOCK -> "Mock AI 演示风格";
            case STANDARD -> "标准教学媒体风格";
        };
    }

    private static boolean containsAny(String text, String... candidates) {
        String normalized = defaultString(text).toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (normalized.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return defaultString(value).isBlank() ? fallback : value;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
