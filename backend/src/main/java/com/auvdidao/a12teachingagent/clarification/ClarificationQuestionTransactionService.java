package com.auvdidao.a12teachingagent.clarification;

import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ClarificationQuestionTransactionService {

    private final ClarificationQuestionRepository questionRepository;
    private final ProjectRepository projectRepository;

    public ClarificationQuestionTransactionService(
            ClarificationQuestionRepository questionRepository,
            ProjectRepository projectRepository
    ) {
        this.questionRepository = questionRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional
    public ClarificationQuestionSnapshot findValidPendingOrObsolete(
            Long projectId,
            List<String> missingFields
    ) {
        ClarificationQuestionEntity pending = latestPending(projectId);
        if (pending == null) {
            return null;
        }
        if (missingFields != null && missingFields.contains(pending.getTargetField())) {
            return snapshot(pending);
        }
        pending.setStatus(ClarificationQuestionStatus.OBSOLETE);
        questionRepository.save(pending);
        return null;
    }

    @Transactional
    public ClarificationQuestionSnapshot saveIfAbsent(
            Long projectId,
            String targetField,
            String question
    ) {
        projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        ClarificationQuestionEntity pending = latestPending(projectId);
        if (pending != null) {
            return snapshot(pending);
        }

        ClarificationQuestionEntity entity = new ClarificationQuestionEntity();
        entity.setQuestionId(UUID.randomUUID().toString());
        entity.setProjectId(projectId);
        entity.setTargetField(targetField);
        entity.setQuestion(question);
        entity.setStatus(ClarificationQuestionStatus.PENDING);
        return snapshot(questionRepository.save(entity));
    }

    private ClarificationQuestionEntity latestPending(Long projectId) {
        return questionRepository
                .findFirstByProjectIdAndStatusOrderByCreatedAtDescIdDesc(
                        projectId, ClarificationQuestionStatus.PENDING)
                .orElse(null);
    }

    private static ClarificationQuestionSnapshot snapshot(ClarificationQuestionEntity entity) {
        return new ClarificationQuestionSnapshot(
                entity.getQuestionId(), entity.getTargetField(), entity.getQuestion());
    }

    public record ClarificationQuestionSnapshot(
            String questionId,
            String targetField,
            String question
    ) {
    }
}
