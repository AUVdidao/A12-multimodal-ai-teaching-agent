package com.auvdidao.a12teachingagent.pptskill.harness;

import com.auvdidao.a12teachingagent.domain.generation.PptTemplateSelection;
import com.auvdidao.a12teachingagent.domain.generation.repository.PptTemplateSelectionRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.pptskill.PptSkillGenerationException;
import com.auvdidao.a12teachingagent.security.ProjectAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class PptTemplateSelectionService {
    public static final String DEFAULT_TEMPLATE_ID = "a12-teaching-generic";
    public static final String DEFAULT_TEMPLATE_VERSION = "1.0.0";

    private final ProjectRepository projectRepository;
    private final PptTemplateSelectionRepository selectionRepository;
    private final ProjectAccessService projectAccessService;
    private final PptHarnessClient harnessClient;

    public PptTemplateSelectionService(ProjectRepository projectRepository, PptTemplateSelectionRepository selectionRepository,
                                       ProjectAccessService projectAccessService, PptHarnessClient harnessClient) {
        this.projectRepository = projectRepository;
        this.selectionRepository = selectionRepository;
        this.projectAccessService = projectAccessService;
        this.harnessClient = harnessClient;
    }

    @Transactional(readOnly = true)
    public Selection get(Long projectId) {
        requireProject(projectId);
        return selectionRepository.findFirstByProjectIdOrderByUpdatedAtDescIdDesc(projectId)
                .map(item -> new Selection(item.getTemplateId(), item.getTemplateVersion()))
                .orElse(new Selection(DEFAULT_TEMPLATE_ID, DEFAULT_TEMPLATE_VERSION));
    }

    @Transactional
    public Selection select(Long projectId, String templateId, String templateVersion) {
        requireProject(projectId);
        String normalizedId = required(templateId, "templateId", 128);
        String normalizedVersion = required(templateVersion, "templateVersion", 64);
        // The selection is an execution contract. Persist only a template version that Harness can resolve.
        harnessClient.getTemplate(normalizedId, normalizedVersion);
        PptTemplateSelection selection = selectionRepository.findFirstByProjectIdOrderByUpdatedAtDescIdDesc(projectId)
                .orElseGet(PptTemplateSelection::new);
        selection.setProjectId(projectId);
        selection.setTemplateId(normalizedId);
        selection.setTemplateVersion(normalizedVersion);
        selectionRepository.save(selection);
        return new Selection(normalizedId, normalizedVersion);
    }

    private void requireProject(Long projectId) {
        Project project = projectRepository.findById(projectId).filter(item -> item.getDeletedAt() == null)
                .orElseThrow(() -> new PptSkillGenerationException("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND));
        projectAccessService.requireAccess(project);
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength || !value.matches("[A-Za-z0-9._-]+")) {
            throw new PptSkillGenerationException("INVALID_TEMPLATE_SELECTION", field + " is invalid", HttpStatus.BAD_REQUEST);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public record Selection(String templateId, String templateVersion) { }
}
