package com.auvdidao.a12teachingagent.domain.lessonforge.repository;

import com.auvdidao.a12teachingagent.domain.lessonforge.LessonForgeMaterialBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LessonForgeMaterialBindingRepository extends JpaRepository<LessonForgeMaterialBinding, Long> {

    Optional<LessonForgeMaterialBinding> findByMissionFileIdAndSourceSha256(Long missionFileId, String sourceSha256);

    List<LessonForgeMaterialBinding> findByMissionFileIdOrderByIdAsc(Long missionFileId);

    Optional<LessonForgeMaterialBinding> findByMissionIdAndRagProjectIdAndRagMaterialId(
            Long missionId, Long ragProjectId, Long ragMaterialId);

    List<LessonForgeMaterialBinding> findByMissionIdAndRagProjectIdOrderByIdAsc(
            Long missionId, Long ragProjectId);

    Optional<LessonForgeMaterialBinding> findByRagProjectIdAndRagMaterialId(
            Long ragProjectId, Long ragMaterialId);
}
