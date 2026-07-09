package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.ai.MockAIWorkflowGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClarificationController.class)
@Import(MockAIWorkflowGateway.class)
@TestPropertySource(properties = {
        "DIFY_API_KEY=",
        "DIFY_WORKFLOW_ID="
})
class ClarificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void completeRequirementReturnsCompleteTrue() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gradeLevel": "五年级",
                                  "subject": "数学",
                                  "topic": "分数的意义",
                                  "lessonDuration": "45分钟",
                                  "teachingGoals": "理解分数表示整体与部分的关系",
                                  "outputTypes": ["PPT", "DOCX"],
                                  "rawRequirementText": "帮我设计一节五年级数学课。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.complete", is(true)))
                .andExpect(jsonPath("$.data.missingFields", hasSize(0)))
                .andExpect(jsonPath("$.data.questions", hasSize(0)));
    }

    @Test
    void missingGradeLevelReturnsGradeLevelMissing() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "subject": "数学",
                                  "topic": "分数的意义",
                                  "lessonDuration": "45分钟",
                                  "teachingGoals": "理解分数表示整体与部分的关系",
                                  "outputTypes": ["PPT"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complete", is(false)))
                .andExpect(jsonPath("$.data.missingFields[*].field", hasItem("gradeLevel")))
                .andExpect(jsonPath("$.data.questions", hasItem("请补充本节课面向哪个年级学生？")));
    }

    @Test
    void missingTopicReturnsTopicMissing() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gradeLevel": "五年级",
                                  "subject": "数学",
                                  "lessonDuration": "45分钟",
                                  "teachingGoals": "理解分数表示整体与部分的关系",
                                  "outputTypes": ["PPT"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complete", is(false)))
                .andExpect(jsonPath("$.data.missingFields[*].field", hasItem("topic")))
                .andExpect(jsonPath("$.data.questions", hasItem("请补充本节课的具体课题。")));
    }

    @Test
    void missingTeachingGoalsReturnsTeachingGoalsMissing() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gradeLevel": "五年级",
                                  "subject": "数学",
                                  "topic": "分数的意义",
                                  "lessonDuration": "45分钟",
                                  "outputTypes": ["PPT"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complete", is(false)))
                .andExpect(jsonPath("$.data.missingFields[*].field", hasItem("teachingGoals")))
                .andExpect(jsonPath("$.data.questions", hasItem("请补充本节课希望学生达成的教学目标。")));
    }

    @Test
    void multipleMissingFieldsReturnMultipleQuestions() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rawRequirementText": "帮我生成一节数学课 PPT"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complete", is(false)))
                .andExpect(jsonPath("$.data.missingFields[*].field", hasItem("gradeLevel")))
                .andExpect(jsonPath("$.data.missingFields[*].field", hasItem("topic")))
                .andExpect(jsonPath("$.data.missingFields[*].field", hasItem("lessonDuration")))
                .andExpect(jsonPath("$.data.missingFields[*].field", hasItem("teachingGoals")))
                .andExpect(jsonPath("$.data.missingFields[*].field", hasItem("outputTypes")))
                .andExpect(jsonPath("$.data.questions", hasItem("请补充本节课面向哪个年级学生？")))
                .andExpect(jsonPath("$.data.questions", hasItem("请补充本节课的具体课题。")))
                .andExpect(jsonPath("$.data.questions", hasItem("本节课预计多少分钟？")))
                .andExpect(jsonPath("$.data.questions", hasItem("请补充本节课希望学生达成的教学目标。")))
                .andExpect(jsonPath("$.data.questions", hasItem("你希望生成 PPT、教案还是互动内容？")));
    }

    @Test
    void mockClarificationWorksWithoutDifyKey() throws Exception {
        mockMvc.perform(post("/api/projects/1/clarification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rawRequirementText": "帮我生成一节数学课 PPT"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.message", is("success")))
                .andExpect(jsonPath("$.data.questions", hasSize(greaterThan(0))));
    }
}
