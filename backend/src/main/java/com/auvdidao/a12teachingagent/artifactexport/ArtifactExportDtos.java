package com.auvdidao.a12teachingagent.artifactexport;

import com.auvdidao.a12teachingagent.domain.common.ExportType;

import java.util.List;

public final class ArtifactExportDtos {

    private ArtifactExportDtos() {
    }

    public record ExportCatalog(
            Long projectId,
            String projectName,
            List<ExportOption> formats
    ) {
    }

    public record ExportOption(
            ExportType format,
            String label,
            String description,
            String mediaType,
            String extension,
            Long artifactId,
            Long versionId,
            Integer versionNumber,
            String filename,
            String downloadUrl
    ) {
    }

    public record GeneratedExport(
            String filename,
            String mediaType,
            byte[] content
    ) {
    }
}
