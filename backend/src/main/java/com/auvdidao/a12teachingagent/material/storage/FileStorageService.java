package com.auvdidao.a12teachingagent.material.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

    StoredFile store(Long projectId, String extension, MultipartFile file);

    StoredFile storeForMission(Long missionId, String extension, MultipartFile file);

    Resource load(String storageKey);

    Resource loadAndVerify(String storageKey, String expectedSha256);

    /** Immutable file identity captured at approval time. */
    default StoredFileIdentity identity(String storageKey) {
        return null;
    }

    void deleteQuietly(String storageKey);

    record StoredFile(String storedFilename, String storageKey, String sha256) {
    }

    record StoredFileIdentity(long size, String sha256, String lastModifiedUtc) {
    }
}
