package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.ProjectStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummaryStatus;
import com.auvdidao.a12teachingagent.domain.requirement.repository.RequirementSummaryRepository;
import com.auvdidao.a12teachingagent.material.parse.MaterialPrototypeParser;
import com.auvdidao.a12teachingagent.material.parse.MaterialParsingException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Rollback
class MaterialParseRetryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequirementSummaryRepository summaryRepository;

    @Autowired
    private UploadedMaterialRepository materialRepository;

    @Autowired
    private MaterialPurposeRepository purposeRepository;

    @MockBean
    private MaterialPrototypeParser prototypeParser;

    @Test
    void failedPrototypeParseCanRetryWithoutExposingStackTrace() throws Exception {
        Fixture fixture = createFixture();
        when(prototypeParser.parse(any(), anyList(), any()))
                .thenThrow(new MaterialParsingException("sensitive parser detail"))
                .thenReturn(new MaterialPrototypeParser.ParsedContent(
                        "重试后的确定性原型摘要",
                        List.of("光合作用", "教材依据", "概念讲解"),
                        List.of("概念讲解")
                ));

        mockMvc.perform(post(
                        "/api/projects/{projectId}/materials/{materialId}/parse",
                        fixture.projectId(),
                        fixture.materialId()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus", is("FAILED")))
                .andExpect(jsonPath("$.data.failureReason", containsString("Please retry")))
                .andExpect(jsonPath("$.data.failureReason", org.hamcrest.Matchers.not(containsString("sensitive"))));

        mockMvc.perform(post(
                        "/api/projects/{projectId}/materials/{materialId}/parse/retry",
                        fixture.projectId(),
                        fixture.materialId()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parseStatus", is("SUCCEEDED")))
                .andExpect(jsonPath("$.data.summary", containsString("重试后的确定性原型摘要")))
                .andExpect(jsonPath("$.data.summary", containsString("AI 教学分析")));

        mockMvc.perform(get("/api/projects/{projectId}/knowledge/overview", fixture.projectId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunks", hasSize(1)));
    }

    private Fixture createFixture() {
        Project project = new Project();
        project.setProjectName("Parse retry test");
        project.setCourseName("生物");
        project.setChapterTopic("光合作用");
        project.setGenerationMode(GenerationMode.STANDARD);
        project.setStatus(ProjectStatus.REQUIREMENT_CONFIRMED);
        project = projectRepository.save(project);

        RequirementSummary summary = new RequirementSummary();
        summary.setProjectId(project.getId());
        summary.setGradeLevel("八年级");
        summary.setSubject("生物");
        summary.setTopic("光合作用");
        summary.setLessonDuration("45分钟");
        summary.setTeachingGoals("解释光合作用过程");
        summary.setKeyPoints("光合作用条件");
        summary.setDifficultPoints("能量转化");
        summary.setOutputTypes(List.of("PPT"));
        summary.setGenerationMode(GenerationMode.STANDARD);
        summary.setStatus(RequirementSummaryStatus.CONFIRMED);
        summary.setConfirmedAt(LocalDateTime.now());
        summaryRepository.save(summary);

        UploadedMaterial material = new UploadedMaterial();
        material.setProjectId(project.getId());
        material.setOriginalFileName("光合作用教材.pdf");
        material.setFileName("safe.pdf");
        material.setFilePath(project.getId() + "/safe.pdf");
        material.setFileExtension("pdf");
        material.setFileType(MaterialFileType.PDF);
        material.setContentType("application/pdf");
        material.setFileSize(10L);
        material.setUploadStatus(UploadStatus.UPLOADED);
        material.setParseStatus(MaterialParseStatus.NOT_STARTED);
        material = materialRepository.save(material);

        MaterialPurpose purpose = new MaterialPurpose();
        purpose.setProjectId(project.getId());
        purpose.setMaterialId(material.getId());
        purpose.setPurposeType(PurposeType.TEXTBOOK_BASIS);
        purposeRepository.save(purpose);
        return new Fixture(project.getId(), material.getId());
    }

    private record Fixture(Long projectId, Long materialId) {
    }
}
