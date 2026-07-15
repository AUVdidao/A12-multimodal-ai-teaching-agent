package com.auvdidao.a12.generator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class GeneratorDtos {
    private GeneratorDtos() { }

    public record RenderRequest(
            @NotBlank @Size(max = 32) String artifactType,
            @NotNull Integer schemaVersion,
            @Size(max = 500) String projectName,
            @Size(max = 500) String courseName,
            @Size(max = 500) String chapterTopic,
            @Size(max = 500) String title,
            @NotBlank @Size(max = 1_048_576) String contentJson
    ) { }

    public record PackageRequest(@NotEmpty @Size(max = 8) List<PackageEntry> entries) { }

    public record PackageEntry(@NotBlank @Size(max = 120) String filename,
                               @NotBlank @Size(max = 32) String format,
                               @NotNull RenderRequest request) { }
}
