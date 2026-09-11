package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.SnapshotIntegrityException;
import com.auvdidao.a12teachingagent.domain.template.TemplateProcessingStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileOrigin;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileStatus;
import com.auvdidao.a12teachingagent.domain.template.TemplateProfileVersion;
import com.auvdidao.a12teachingagent.domain.template.TemplateSourceVersion;
import com.auvdidao.a12teachingagent.domain.template.TemplateStructuralSnapshot;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateProfileVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateSourceVersionRepository;
import com.auvdidao.a12teachingagent.domain.template.repository.TemplateStructuralSnapshotRepository;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/** Read-only contract gate shared by Specification and Template/Profile modules. */
@Service
public class TemplateProfileContractGate {
    private final TemplateProfileVersionRepository profileRepository;
    private final TemplateSourceVersionRepository sourceRepository;
    private final TemplateStructuralSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public TemplateProfileContractGate(TemplateProfileVersionRepository profileRepository,
                                       TemplateSourceVersionRepository sourceRepository,
                                       TemplateStructuralSnapshotRepository snapshotRepository,
                                       ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.sourceRepository = sourceRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<ProfileBinding> captureIfConsistent(Long projectId, String profileId,
                                                        Integer profileVersion, Integer capabilityViewVersion,
                                                        String capabilityViewChecksum) {
        Long id = parseId(profileId);
        if (id == null) return Optional.empty();
        return profileRepository.findByIdAndProjectId(id, projectId)
                .filter(profile -> matchesIdentity(profile, profileVersion, capabilityViewVersion, capabilityViewChecksum))
                .filter(this::isPlanningReady)
                .filter(this::isContentIntact)
                .map(profile -> new ProfileBinding(profile.getId(), profile.getTemplateId(), profile.getSourceVersionId(),
                        profile.getVersionNumber(), profile.getChecksum(), profile.getCapabilityViewVersion(), profile.getCapabilityViewChecksum()));
    }

    public ProfileBinding requirePlanningBinding(Long projectId, Long templateId, Long profileVersionId) {
        TemplateProfileVersion profile = profileRepository.findByIdAndProjectId(profileVersionId, projectId)
                .orElseThrow(() -> new ConflictException("PLANNING Template Profile is missing"));
        if (!templateId.equals(profile.getTemplateId())) {
            throw new ConflictException("PLANNING Template Profile template binding is stale");
        }
        return requireUsableBinding("PLANNING", projectId, profileVersionId.toString(), profile.getVersionNumber(),
                profile.getChecksum(), profile.getCapabilityViewVersion(), profile.getCapabilityViewChecksum());
    }

    /** Strong Analyzer-backed binding for every Specification mutation, including initial DRAFT writes. */
    public ProfileBinding requireSpecificationBinding(Long projectId, String profileId,
                                                       Integer profileVersion, Integer capabilityViewVersion,
                                                       String capabilityViewChecksum) {
        Long id = parseId(profileId);
        if (id == null) throw new ConflictException("WRITE Specification templateProfileId is invalid");
        TemplateProfileVersion profile = profileRepository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new ConflictException("WRITE Specification refers to a missing Template Profile"));
        return requireUsableBinding("WRITE", projectId, profileId, profileVersion, profile.getChecksum(),
                capabilityViewVersion, capabilityViewChecksum);
    }

    public ProfileBinding requireLockedBinding(Long projectId, String profileId, Integer profileVersion,
                                               String expectedProfileChecksum, Integer capabilityViewVersion,
                                               String capabilityViewChecksum) {
        return requireUsableBinding("LOCKED", projectId, profileId, profileVersion, expectedProfileChecksum,
                capabilityViewVersion, capabilityViewChecksum);
    }

    public ProfileBinding requireReviewBinding(Long projectId, String profileId, Integer profileVersion,
                                               String expectedProfileChecksum, Integer capabilityViewVersion,
                                               String capabilityViewChecksum) {
        return requireUsableBinding("REVIEW", projectId, profileId, profileVersion, expectedProfileChecksum,
                capabilityViewVersion, capabilityViewChecksum);
    }

    public com.auvdidao.a12teachingagent.domain.template.TemplateStructuralSnapshot loadAndVerifySnapshot(Long sourceVersionId) {
        TemplateStructuralSnapshot snapshot = snapshotRepository.findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(sourceVersionId)
                .orElseThrow(() -> new SnapshotIntegrityException("SNAPSHOT_NOT_READY: structural snapshot is missing"));
        if (snapshot.getChecksum() == null || !snapshot.getChecksum().matches("[0-9a-fA-F]{64}")
                || snapshot.getSnapshotJson() == null
                || !TemplateChecksum.sha256(snapshot.getSnapshotJson()).equalsIgnoreCase(snapshot.getChecksum())) {
            throw new SnapshotIntegrityException("SNAPSHOT_INTEGRITY_MISMATCH: structural snapshot checksum verification failed");
        }
        try {
            objectMapper.readTree(snapshot.getSnapshotJson());
        } catch (JsonProcessingException exception) {
            throw new SnapshotIntegrityException("SNAPSHOT_INTEGRITY_MISMATCH: structural snapshot JSON is invalid");
        }
        return snapshot;
    }

    private ProfileBinding requireUsableBinding(String phase, Long projectId, String profileId, Integer profileVersion,
                                                   String expectedProfileChecksum, Integer capabilityViewVersion,
                                                   String capabilityViewChecksum) {
        Long id = parseId(profileId);
        if (id == null) throw new ConflictException(phase + " Specification templateProfileId is invalid");
        TemplateProfileVersion profile = profileRepository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new ConflictException(phase + " Specification refers to a missing Template Profile"));
        if (!isPlanningReady(profile)) {
            throw new ConflictException(phase + " Specification requires a READY or CONFIRMED Template Profile");
        }
        if (profile.getOrigin() != TemplateProfileOrigin.ANALYZER_CANDIDATE
                || profile.getParserSnapshotChecksum() == null
                || profile.getRendererStatus() != TemplateProcessingStatus.SUCCEEDED
                || profile.getAnalyzerStatus() != TemplateProcessingStatus.SUCCEEDED) {
            throw new ConflictException(phase + " Specification requires a complete Analyzer-backed Template Profile");
        }
        if (!matchesIdentity(profile, profileVersion, capabilityViewVersion, capabilityViewChecksum)
                || expectedProfileChecksum == null || !expectedProfileChecksum.equalsIgnoreCase(profile.getChecksum())) {
            throw new ConflictException(phase + " Specification Template Profile binding is stale");
        }
        if (!isContentIntact(profile)) throw new ConflictException("Template Profile integrity check failed");
        TemplateSourceVersion source = sourceRepository.findById(profile.getSourceVersionId())
                .orElseThrow(() -> new ConflictException("Template Profile source version is missing"));
        if (!projectId.equals(profile.getProjectId()) || !projectId.equals(source.getProjectId())
                || !profile.getTemplateId().equals(source.getTemplateId())
                || source.getParseStatus() != TemplateProcessingStatus.SUCCEEDED
                || !snapshotChecksumMatches(profile)) {
            throw new ConflictException(phase + " Specification Template Profile is not bound to the confirmed source snapshot");
        }
        return new ProfileBinding(profile.getId(), profile.getTemplateId(), profile.getSourceVersionId(),
                profile.getVersionNumber(), profile.getChecksum(), profile.getCapabilityViewVersion(), profile.getCapabilityViewChecksum());
    }

    public void requireIntact(TemplateProfileVersion profile) {
        if (!isContentIntact(profile)) throw new ConflictException("Template Profile integrity check failed");
    }

    private boolean matchesIdentity(TemplateProfileVersion profile, Integer profileVersion,
                                    Integer capabilityViewVersion, String capabilityViewChecksum) {
        return profileVersion != null && profileVersion.equals(profile.getVersionNumber())
                && capabilityViewVersion != null && capabilityViewVersion.equals(profile.getCapabilityViewVersion())
                && capabilityViewChecksum != null && capabilityViewChecksum.equalsIgnoreCase(profile.getCapabilityViewChecksum());
    }

    private boolean isContentIntact(TemplateProfileVersion profile) {
        return profile.getProfileJson() != null && profile.getCapabilityViewJson() != null
                && TemplateChecksum.sha256(profile.getProfileJson()).equalsIgnoreCase(profile.getChecksum())
                && TemplateChecksum.sha256(profile.getCapabilityViewJson()).equalsIgnoreCase(profile.getCapabilityViewChecksum())
                && (profile.getStatus() != TemplateProfileStatus.CONFIRMED
                || (profile.getConfirmedChecksum() != null && profile.getChecksum().equalsIgnoreCase(profile.getConfirmedChecksum())));
    }

    private boolean isPlanningReady(TemplateProfileVersion profile) {
        return profile.getStatus() == TemplateProfileStatus.READY
                || profile.getStatus() == TemplateProfileStatus.CONFIRMED;
    }

    private boolean snapshotChecksumMatches(TemplateProfileVersion profile) {
        return profile.getParserSnapshotChecksum().matches("[0-9a-fA-F]{64}")
                && snapshotRepository.findTopBySourceVersionIdOrderByCreatedAtDescIdDesc(profile.getSourceVersionId())
                .map(snapshot -> profile.getParserSnapshotChecksum().equalsIgnoreCase(snapshot.getChecksum())
                        && snapshot.getSnapshotJson() != null
                        && TemplateChecksum.sha256(snapshot.getSnapshotJson()).equalsIgnoreCase(snapshot.getChecksum()))
                .orElse(false);
    }

    private Long parseId(String value) {
        try { return value == null ? null : Long.valueOf(value.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    public record ProfileBinding(Long profileId, Long templateId, Long sourceVersionId, Integer profileVersion,
                                 String profileChecksum, Integer capabilityViewVersion, String capabilityViewChecksum) { }
}
