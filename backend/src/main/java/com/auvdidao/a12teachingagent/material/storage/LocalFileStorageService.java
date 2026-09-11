package com.auvdidao.a12teachingagent.material.storage;

import com.auvdidao.a12teachingagent.common.exception.FileStorageException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.common.exception.StorageIntegrityException;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
        return storeInDirectory(safeProjectDirectory(projectId), extension, file);
    }

    @Override
    public StoredFile storeForMission(Long missionId, String extension, MultipartFile file) {
        if (missionId == null || missionId <= 0) throw new FileStorageException("Invalid mission storage directory");
        return storeInDirectory(root.resolve("missions").resolve(missionId.toString()).normalize(), extension, file);
    }

    private StoredFile storeInDirectory(Path projectDirectory, String extension, MultipartFile file) {
        assertInsideRoot(projectDirectory);
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
            return new StoredFile(storedFilename, root.relativize(target).toString().replace('\\', '/'), sha256(target));
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

    private String sha256(Path target) throws IOException {
        try (InputStream input = Files.newInputStream(target)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input.transferTo(new java.io.OutputStream() {
                @Override public void write(int b) { digest.update((byte) b); }
                @Override public void write(byte[] b, int off, int len) { digest.update(b, off, len); }
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public Resource loadAndVerify(String storageKey, String expectedSha256) {
        Resource resource = load(storageKey);
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new StorageIntegrityException("INTEGRITY_MISMATCH: stored material metadata is invalid");
        }
        try (InputStream input = resource.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input.transferTo(new java.io.OutputStream() {
                @Override public void write(int b) { digest.update((byte) b); }
                @Override public void write(byte[] b, int off, int len) { digest.update(b, off, len); }
            });
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    expectedSha256.toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new StorageIntegrityException("INTEGRITY_MISMATCH: stored material bytes do not match the recorded hash");
            }
            return resource;
        } catch (IOException exception) {
            throw new FileStorageException("Unable to verify the stored material", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public StoredFileIdentity identity(String storageKey) {
        Path target = resolveStorageKey(storageKey);
        try {
            if (!Files.isRegularFile(target) || !Files.isReadable(target) || !target.equals(target.toRealPath())) {
                throw new ResourceNotFoundException("Stored material file is not a regular immutable file");
            }
            return new StoredFileIdentity(
                    Files.size(target),
                    sha256(target),
                    Files.getLastModifiedTime(target).toInstant().toString());
        } catch (IOException exception) {
            throw new FileStorageException("Unable to read stored material identity", exception);
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
