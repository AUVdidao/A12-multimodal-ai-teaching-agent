package com.auvdidao.a12teachingagent.material.storage;

import com.auvdidao.a12teachingagent.common.exception.FileStorageException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path root;

    public LocalFileStorageService(StorageProperties properties) {
        this.root = Path.of(properties.getUploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new FileStorageException("Unable to initialize the material storage directory", exception);
        }
    }

    @Override
    public StoredFile store(Long projectId, String extension, MultipartFile file) {
        Path projectDirectory = safeProjectDirectory(projectId);
        String storedFilename = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path target = projectDirectory.resolve(storedFilename).normalize();
        assertInsideRoot(target);
        Path temporary = projectDirectory.resolve(storedFilename + ".part").normalize();

        try {
            Files.createDirectories(projectDirectory);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredFile(storedFilename, root.relativize(target).toString().replace('\\', '/'));
        } catch (IOException exception) {
            deletePathQuietly(temporary);
            deletePathQuietly(target);
            throw new FileStorageException("Unable to store the uploaded material", exception);
        }
    }

    @Override
    public Resource load(String storageKey) {
        Path target = resolveStorageKey(storageKey);
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            throw new ResourceNotFoundException("Stored material file is missing");
        }
        try {
            return new UrlResource(target.toUri());
        } catch (IOException exception) {
            throw new FileStorageException("Unable to read the stored material", exception);
        }
    }

    @Override
    public void deleteQuietly(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            deletePathQuietly(resolveStorageKey(storageKey));
        } catch (RuntimeException ignored) {
            // Cleanup is best effort after a failed database write.
        }
    }

    private Path safeProjectDirectory(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new FileStorageException("Invalid project storage directory");
        }
        Path directory = root.resolve(projectId.toString()).normalize();
        assertInsideRoot(directory);
        return directory;
    }

    private Path resolveStorageKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || Path.of(storageKey).isAbsolute()) {
            throw new FileStorageException("Invalid stored material key");
        }
        Path target = root.resolve(storageKey).normalize();
        assertInsideRoot(target);
        return target;
    }

    private void assertInsideRoot(Path path) {
        if (!path.startsWith(root)) {
            throw new FileStorageException("Material path escaped the configured storage directory");
        }
    }

    private static void deletePathQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Cleanup failure must not hide the original storage error.
        }
    }
}
