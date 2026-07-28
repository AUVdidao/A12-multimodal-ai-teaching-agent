package com.auvdidao.a12teachingagent.pptskill;

import com.auvdidao.a12teachingagent.config.PptGeneratorProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class PptSkillFileStore {

    private final Path root;

    public PptSkillFileStore(PptGeneratorProperties properties) {
        this.root = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
    }

    public Path save(Long projectId, byte[] content) {
        if (content == null || content.length == 0) {
            throw new PptSkillGenerationException("PPT_EMPTY_FILE", "Generated PPTX is empty", org.springframework.http.HttpStatus.BAD_GATEWAY);
        }
        try {
            Path projectDir = root.resolve("project-" + projectId).normalize();
            if (!projectDir.startsWith(root)) {
                throw new IOException("Invalid project storage path");
            }
            Files.createDirectories(projectDir);
            Path target = projectDir.resolve(UUID.randomUUID() + ".pptx").normalize();
            if (!target.startsWith(root)) {
                throw new IOException("Invalid generated file path");
            }
            Files.write(target, content);
            return target;
        } catch (IOException exception) {
            throw new PptSkillGenerationException("PPT_DOWNLOAD_FAILED", "Generated PPTX could not be stored", org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, exception);
        }
    }

    public byte[] readManaged(String filePath) {
        try {
            Path path = Path.of(filePath).toAbsolutePath().normalize();
            if (!path.startsWith(root) || Files.isSymbolicLink(path) || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Generated file is outside managed storage");
            }
            return Files.readAllBytes(path);
        } catch (IOException | RuntimeException exception) {
            throw new PptSkillGenerationException("PPT_DOWNLOAD_FAILED", "Stored PPTX could not be read", org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, exception);
        }
    }

    public void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
