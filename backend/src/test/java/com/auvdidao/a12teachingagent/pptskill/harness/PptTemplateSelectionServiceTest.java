package com.auvdidao.a12teachingagent.pptskill.harness;

import com.auvdidao.a12teachingagent.domain.generation.PptTemplateSelection;
import com.auvdidao.a12teachingagent.domain.generation.repository.PptTemplateSelectionRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.pptskill.PptSkillGenerationException;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PptTemplateSelectionServiceTest {
    private PptTemplateSelectionRepository repository;
    private PptHarnessClient harnessClient;
    private PptTemplateSelectionService service;

    @BeforeEach
    void setUp() {
        Project project = new Project();
        project.setId(7L);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        repository = mock(PptTemplateSelectionRepository.class);
        harnessClient = mock(PptHarnessClient.class);
        service = new PptTemplateSelectionService(projectRepository, repository, mock(ProjectAccessService.class), harnessClient);
    }

    @Test
    void persistsOnlyTemplateVersionResolvedByHarness() {
        when(repository.findFirstByProjectIdOrderByUpdatedAtDescIdDesc(7L)).thenReturn(Optional.empty());
        when(repository.save(any(PptTemplateSelection.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(harnessClient.getTemplate("a12-teaching-generic", "1.0.0"))
                .thenReturn(new ObjectMapper().createObjectNode());

        PptTemplateSelectionService.Selection selected = service.select(7L, "A12-Teaching-Generic", "1.0.0");

        assertEquals("a12-teaching-generic", selected.templateId());
        verify(harnessClient).getTemplate("a12-teaching-generic", "1.0.0");
        verify(repository).save(any(PptTemplateSelection.class));
    }

    @Test
    void missingHarnessTemplateDoesNotPersistSelection() {
        doThrow(new PptSkillGenerationException("TEMPLATE_NOT_FOUND", "Presentation template is not available",
                org.springframework.http.HttpStatus.NOT_FOUND))
                .when(harnessClient).getTemplate("not-present", "1.0.0");

        PptSkillGenerationException exception = assertThrows(PptSkillGenerationException.class,
                () -> service.select(7L, "not-present", "1.0.0"));

        assertEquals("TEMPLATE_NOT_FOUND", exception.getCode());
        verify(repository, never()).save(any());
    }
}
