package com.auvdidao.a12teachingagent.lessonforge.material;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.domain.lessonforge.LessonForgeMaterialBinding;
import com.auvdidao.a12teachingagent.domain.lessonforge.repository.LessonForgeMaterialBindingRepository;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.auvdidao.a12teachingagent.domain.project.repository.ProjectRepository;
import com.auvdidao.a12teachingagent.lessonforge.material.LessonForgeMaterialDtos.IngestRequest;
import com.auvdidao.a12teachingagent.material.MaterialService;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LessonForgeMaterialServiceTest {
    private static final long OWNER = 101L;
    private static final long PROJECT = 44L;
    private static final long MISSION = 7L;
    private static final long MISSION_FILE = 9L;

    @Mock CurrentUserService currentUserService;
    @Mock ProjectRepository projectRepository;
    @Mock UploadedMaterialRepository materialRepository;
    @Mock LessonForgeMaterialBindingRepository bindingRepository;
    @Mock FileStorageService fileStorageService;
    @Mock MaterialService materialService;

    private LessonForgeMaterialService service;
    private MockMultipartFile file;
    private IngestRequest request;
    private String sha;

    @BeforeEach
    void setUp() throws Exception {
        service = new LessonForgeMaterialService(currentUserService, projectRepository, materialRepository,
                bindingRepository, fileStorageService, materialService);
        byte[] bytes = "TCP three-way handshake".getBytes(StandardCharsets.UTF_8);
        file = new MockMultipartFile("file", "textbook.pdf", "application/pdf", bytes);
        sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        request = new IngestRequest(MISSION, MISSION_FILE, OWNER, OWNER, sha, (long) bytes.length,
                "lessonforge:mission-file:9:" + sha);
        when(currentUserService.requireRole(UserRole.TEACHER))
                .thenReturn(new AuthenticatedUser(null, OWNER, "lessonforge", "LessonForge", UserRole.TEACHER));
        Project project = new Project();
        project.setId(PROJECT);
        project.setOwnerUserId(OWNER);
        lenient().when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project));
        lenient().when(materialService.validateFile(file)).thenReturn(new MaterialService.ValidatedFile(
                "textbook.pdf", "pdf", "application/pdf",
                new MaterialService.FileRule(MaterialFileType.PDF, Set.of("application/pdf"))));
        lenient().when(bindingRepository.findByMissionFileIdAndSourceSha256(MISSION_FILE, sha)).thenReturn(Optional.empty());
        lenient().when(bindingRepository.findByMissionFileIdOrderByIdAsc(MISSION_FILE)).thenReturn(List.of());
    }

    @Test
    void acceptsLessonForgeMaterialWithoutCallingLegacyRequirementSummaryUpload() {
        UploadedMaterial material = new UploadedMaterial();
        material.setId(55L);
        material.setProjectId(PROJECT);
        when(fileStorageService.store(PROJECT, "pdf", file))
                .thenReturn(new FileStorageService.StoredFile("stored.pdf", "44/stored.pdf", sha));
        when(materialRepository.saveAndFlush(any(UploadedMaterial.class))).thenReturn(material);
        when(bindingRepository.saveAndFlush(any(LessonForgeMaterialBinding.class))).thenAnswer(invocation -> {
            LessonForgeMaterialBinding binding = invocation.getArgument(0);
            binding.setId(71L);
            return binding;
        });

        var response = service.ingest(PROJECT, request, file);

        assertThat(response.bindingId()).isEqualTo(71L);
        assertThat(response.materialId()).isEqualTo(55L);
        assertThat(response.sourceSha256()).isEqualTo(sha);
        verify(materialService, never()).upload(anyLong(), any(MultipartFile.class), anyString());
        verify(fileStorageService).store(PROJECT, "pdf", file);
    }

    @Test
    void retriesSameMissionFileAndHashWithoutCreatingAnotherMaterial() {
        LessonForgeMaterialBinding binding = binding(71L);
        UploadedMaterial material = new UploadedMaterial();
        material.setId(55L);
        material.setProjectId(PROJECT);
        when(bindingRepository.findByMissionFileIdAndSourceSha256(MISSION_FILE, sha)).thenReturn(Optional.of(binding));
        when(materialRepository.findByIdAndProjectId(55L, PROJECT)).thenReturn(Optional.of(material));

        var response = service.ingest(PROJECT, request, file);

        assertThat(response.bindingId()).isEqualTo(71L);
        assertThat(response.materialId()).isEqualTo(55L);
        verifyNoInteractions(fileStorageService);
        verify(materialRepository, never()).saveAndFlush(any());
        verify(bindingRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsBytesWhoseHashDoesNotMatchDeclaredCrossServiceIdentity() {
        IngestRequest wrong = new IngestRequest(MISSION, MISSION_FILE, OWNER, OWNER,
                "0".repeat(64), file.getSize(), request.description());

        assertThatThrownBy(() -> service.ingest(PROJECT, wrong, file))
                .isInstanceOf(ConflictException.class)
                .hasMessage("LESSONFORGE_SOURCE_SHA256_MISMATCH");
        verifyNoInteractions(fileStorageService, materialRepository, bindingRepository);
    }

    @Test
    void rejectsOwnerOrActorMismatchEvenWhenTheProjectExists() {
        IngestRequest wrong = new IngestRequest(MISSION, MISSION_FILE, OWNER + 1, OWNER, sha, file.getSize(), null);

        assertThatThrownBy(() -> service.ingest(PROJECT, wrong, file))
                .isInstanceOf(com.auvdidao.a12teachingagent.common.exception.ForbiddenException.class)
                .hasMessage("LESSONFORGE_OWNER_ACTOR_MISMATCH");
        verifyNoInteractions(projectRepository, fileStorageService, materialRepository, bindingRepository);
    }

    private LessonForgeMaterialBinding binding(long id) {
        LessonForgeMaterialBinding binding = new LessonForgeMaterialBinding();
        binding.setId(id);
        binding.setMissionId(MISSION);
        binding.setMissionFileId(MISSION_FILE);
        binding.setOwnerUserId(OWNER);
        binding.setActorUserId(OWNER);
        binding.setRagProjectId(PROJECT);
        binding.setRagMaterialId(55L);
        binding.setSourceSha256(sha);
        binding.setSourceSize(file.getSize());
        binding.setOriginalFilename("textbook.pdf");
        binding.setBindingStatus("BOUND");
        return binding;
    }
}
