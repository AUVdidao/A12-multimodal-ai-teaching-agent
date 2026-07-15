package com.auvdidao.a12teachingagent.artifactexport;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.project.Project;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "a12.artifact-generator.mode", havingValue = "remote")
class RemoteArtifactGenerator implements ArtifactGenerator {
    private final RestClient client;

    RemoteArtifactGenerator(
            @Value("${a12.artifact-generator.base-url}") String baseUrl,
            @Value("${a12.artifact-generator.timeout-ms:15000}") long timeoutMs
    ) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build());
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public byte[] renderPptx(Project project, GeneratedArtifact artifact) {
        return render("/internal/file-generator/pptx", project, artifact);
    }

    @Override
    public byte[] renderDocx(Project project, GeneratedArtifact artifact) {
        return render("/internal/file-generator/docx", project, artifact);
    }

    private byte[] render(String path, Project project, GeneratedArtifact artifact) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("artifactType", artifact.getArtifactType().name());
        request.put("schemaVersion", artifact.getSchemaVersion());
        request.put("projectName", project.getProjectName());
        request.put("courseName", project.getCourseName());
        request.put("chapterTopic", project.getChapterTopic());
        request.put("title", artifact.getTitle());
        request.put("contentJson", artifact.getContentJson());
        try {
            byte[] generated = client.post().uri(path).body(request).retrieve().body(byte[].class);
            if (generated == null || generated.length == 0) {
                throw new IllegalStateException("Remote artifact generator returned an empty file.");
            }
            return generated;
        } catch (RestClientResponseException exception) {
            throw new BadRequestException("Remote artifact generator rejected the export: "
                    + exception.getResponseBodyAsString());
        } catch (RestClientException exception) {
            throw new IllegalStateException("Remote artifact generator is unavailable or timed out.", exception);
        }
    }
}
