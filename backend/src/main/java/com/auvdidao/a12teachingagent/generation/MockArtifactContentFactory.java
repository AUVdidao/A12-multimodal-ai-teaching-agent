package com.auvdidao.a12teachingagent.generation;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntentEvidence;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.CourseInfo;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.DocSection;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.GenerationPlanResponse;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.InteractionContent;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.InteractionQuestion;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.LessonPlanContent;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.PlanSection;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.PptContent;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.PptSlide;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.TeachingProcessStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class MockArtifactContentFactory {

    public PptContent buildPpt(Project project, TeachingIntent intent, GenerationPlanResponse plan) {
        String title = projectTitle(project);
        String topic = topic(project);
        List<String> goals = teachingGoals(intent, topic);
        List<String> agenda = plan.pptOutline().stream().map(PlanSection::title).toList();

        List<PptSlide> slides = List.of(
                slide(1, "COVER", title, "TITLE",
                        List.of("课程：" + courseName(project), "授课对象：" + audience(project)),
                        "介绍课程背景、授课对象与本节主题。"),
                slide(2, "AGENDA", "课程目录", "AGENDA", agenda,
                        "说明本节课的内容结构与课堂节奏。"),
                slide(3, "OBJECTIVES", "教学目标", "BULLETS", goals,
                        "逐项说明可观察、可评价的学习目标。"),
                slide(4, "CONTENT", "知识内容：" + topic, "CONTENT_WITH_SIDEBAR",
                        sectionPoints(plan.pptOutline(), 2, topic + "的核心概念与关键步骤"),
                        "围绕核心概念展开讲解，并及时检查学生理解。"),
                slide(5, "CASE", "案例分析", "CASE_STUDY",
                        List.of("案例主题：在真实情境中应用" + topic, "分析依据：" + contentBasis(intent), "形成可解释的结论"),
                        "引导学生识别情境信息、应用知识并说明判断依据。"),
                slide(6, "INTERACTION", "课堂互动", "QUIZ", plan.interactionPlan(),
                        "先独立思考，再交流答案，最后根据解释完成纠正。"),
                slide(7, "SUMMARY", "总结与延伸", "SUMMARY",
                        List.of("回顾：" + topic, "达成目标：" + goals.get(0), "课后继续完成迁移练习"),
                        "总结本节关键结论，布置课后任务并提示下一步学习。")
        );

        return new PptContent(title, "清晰教学", slides);
    }

    public LessonPlanContent buildLessonPlan(Project project, TeachingIntent intent, GenerationPlanResponse plan) {
        String title = projectTitle(project);
        String topic = topic(project);
        List<String> goals = teachingGoals(intent, topic);
        int totalMinutes = project.getLessonDurationMinutes() == null || project.getLessonDurationMinutes() <= 0
                ? 45
                : project.getLessonDurationMinutes();
        int[] durations = processDurations(totalMinutes);

        List<TeachingProcessStep> process = List.of(
                new TeachingProcessStep("课程导入", durations[0], "用真实情境提出与" + topic + "相关的问题。",
                        "呈现情境并追问已有经验。", "观察情境，提出初步判断。"),
                new TeachingProcessStep("知识讲解", durations[1], sectionDescription(plan.docOutline(), 0, "讲解" + topic + "的核心概念。"),
                        "示范概念分析和关键步骤。", "记录要点并回答检查性问题。"),
                new TeachingProcessStep("案例分析", durations[2], "结合案例应用" + topic + "并说明依据。",
                        "提供案例线索并组织讨论。", "小组分析、表达结论与依据。"),
                new TeachingProcessStep("课堂互动", durations[3], String.join("；", plan.interactionPlan()),
                        "发起问题，收集答案并反馈。", "独立作答、同伴讨论并订正。"),
                new TeachingProcessStep("总结评价", durations[4], "回顾学习目标，形成" + topic + "的知识结构。",
                        "归纳重点并布置课后任务。", "完成自评并记录待解决问题。")
        );

        CourseInfo courseInfo = new CourseInfo(
                title,
                courseName(project),
                topic,
                audience(project),
                totalMinutes,
                generationMode(project)
        );

        List<String> keyPoints = List.of(
                topic + "的核心概念、关键步骤与适用条件",
                sectionDescription(plan.docOutline(), 1, "知识迁移与课堂表达")
        );
        List<String> difficultPoints = List.of(
                "区分相近概念并解释判断依据",
                "将" + topic + "迁移到新的案例情境"
        );
        List<String> methods = distinctValues(intent.getTeachingApproach(), intent.getInteractionMode(), "案例驱动与讲练结合");
        List<String> classroomActivities = plan.interactionPlan();
        List<String> homework = List.of(
                "完成一份" + topic + "概念梳理",
                "选择一个真实案例，说明所用知识和分析过程"
        );
        List<String> resources = resourceNotes(intent);
        List<DocSection> sections = List.of(
                new DocSection(1, "基本信息", List.of(
                        "课程：" + courseInfo.courseName(),
                        "主题：" + courseInfo.chapterTopic(),
                        "授课对象：" + courseInfo.targetAudience(),
                        "课时：" + courseInfo.lessonDurationMinutes() + " 分钟"
                )),
                new DocSection(2, "教学目标", goals),
                new DocSection(3, "教学重点", keyPoints),
                new DocSection(4, "教学难点", difficultPoints),
                new DocSection(5, "教学方法", methods),
                new DocSection(6, "教学过程", process.stream().map(step ->
                        step.stage() + "（" + step.durationMinutes() + " 分钟）：" + step.content()
                                + " 教师活动：" + step.teacherActivity()
                                + " 学生活动：" + step.studentActivity()
                ).toList()),
                new DocSection(7, "课堂活动", classroomActivities),
                new DocSection(8, "课后作业", homework),
                new DocSection(9, "资源说明", resources)
        );

        return new LessonPlanContent(
                title + "教案",
                courseInfo,
                goals,
                keyPoints,
                difficultPoints,
                methods,
                process,
                classroomActivities,
                homework,
                resources,
                sections
        );
    }

    public InteractionContent buildInteraction(Project project) {
        String topic = topic(project);
        List<InteractionQuestion> questions = List.of(
                new InteractionQuestion(
                        "q1",
                        "本节学习“" + topic + "”最核心的目标是什么？",
                        List.of("只记住孤立术语", "理解核心概念并能解释其应用", "跳过案例直接得出结论", "只完成课后作业"),
                        1,
                        "B",
                        "理解概念并能解释应用，才能支持后续案例分析和知识迁移。"
                ),
                new InteractionQuestion(
                        "q2",
                        "分析“" + topic + "”相关案例时，合理的第一步是什么？",
                        List.of("识别情境中的关键信息和问题", "先选择答案再寻找理由", "忽略适用条件", "只复述案例原文"),
                        0,
                        "A",
                        "先识别问题和关键信息，才能选择合适的概念与方法。"
                ),
                new InteractionQuestion(
                        "q3",
                        "哪种课堂活动最能检查学生是否真正理解“" + topic + "”？",
                        List.of("机械抄写定义", "仅观看教师演示", "对新情境作答并解释依据", "跳过反馈直接进入下一章"),
                        2,
                        "C",
                        "在新情境中作答并解释依据，可以同时检查理解、迁移和表达。"
                )
        );
        return new InteractionContent(topic + "知识检查", "每题选择一个答案，提交后查看解释。", questions);
    }

    private static PptSlide slide(
            int index,
            String kind,
            String title,
            String layout,
            List<String> points,
            String speakerNotes
    ) {
        return new PptSlide(index, kind, title, layout, List.copyOf(points), speakerNotes);
    }

    private static List<String> sectionPoints(List<PlanSection> sections, int preferredIndex, String fallback) {
        if (sections.isEmpty()) {
            return List.of(fallback);
        }
        PlanSection section = sections.get(Math.min(preferredIndex, sections.size() - 1));
        return List.of(section.title(), section.description());
    }

    private static String sectionDescription(List<PlanSection> sections, int preferredIndex, String fallback) {
        if (sections.isEmpty()) {
            return fallback;
        }
        return sections.get(Math.min(preferredIndex, sections.size() - 1)).description();
    }

    private static List<String> teachingGoals(TeachingIntent intent, String topic) {
        List<String> goals = new ArrayList<>(intent.getGenerationGoals());
        if (goals.isEmpty() && hasText(intent.getGenerationGoal())) {
            goals.add(intent.getGenerationGoal().trim());
        }
        if (goals.isEmpty()) {
            goals.add("理解" + topic + "的核心概念");
        }
        if (goals.size() < 2) {
            goals.add("能够结合案例解释" + topic + "的应用过程");
        }
        if (goals.size() < 3) {
            goals.add("能够完成课堂检测并根据反馈修正理解");
        }
        return List.copyOf(goals);
    }

    private static List<String> resourceNotes(TeachingIntent intent) {
        LinkedHashSet<String> notes = new LinkedHashSet<>();
        if (hasText(intent.getContentBasis())) {
            notes.add("内容依据：" + intent.getContentBasis().trim());
        }
        intent.getEvidenceItems().stream()
                .map(TeachingIntentEvidence::getSourceFilename)
                .filter(MockArtifactContentFactory::hasText)
                .map(String::trim)
                .map(value -> "参考资料：" + value)
                .forEach(notes::add);
        if (notes.isEmpty()) {
            notes.add("内容依据：已确认教学意图");
        }
        return List.copyOf(notes);
    }

    private static List<String> distinctValues(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (hasText(value)) {
                result.add(value.trim());
            }
        }
        return List.copyOf(result);
    }

    private static int[] processDurations(int totalMinutes) {
        int total = Math.max(totalMinutes, 5);
        int introduction = Math.max(1, total * 10 / 100);
        int explanation = Math.max(1, total * 35 / 100);
        int caseStudy = Math.max(1, total * 20 / 100);
        int interaction = Math.max(1, total * 20 / 100);
        int summary = Math.max(1, total - introduction - explanation - caseStudy - interaction);
        return new int[]{introduction, explanation, caseStudy, interaction, summary};
    }

    private static String projectTitle(Project project) {
        return firstNonBlank(project.getProjectName(), project.getChapterTopic(), project.getCourseName(), "未命名教学项目");
    }

    private static String courseName(Project project) {
        return firstNonBlank(project.getCourseName(), project.getProjectName(), "未命名课程");
    }

    private static String topic(Project project) {
        return firstNonBlank(project.getChapterTopic(), project.getProjectName(), project.getCourseName(), "课程主题");
    }

    private static String audience(Project project) {
        return firstNonBlank(project.getTargetAudience(), "目标学习者");
    }

    private static String contentBasis(TeachingIntent intent) {
        return firstNonBlank(intent.getContentBasis(), "已确认教学意图");
    }

    private static String generationMode(Project project) {
        GenerationMode mode = project.getGenerationMode();
        return mode == null ? GenerationMode.STANDARD.name() : mode.name();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
