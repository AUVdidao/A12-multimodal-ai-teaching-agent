package com.auvdidao.a12teachingagent.material.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

    StoredFile store(Long projectId, String extension, MultipartFile file);

    Resource load(String storageKey);

    void deleteQuietly(String storageKey);

    record StoredFile(String storedFilename, String storageKey) {
    }
}
