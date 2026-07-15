package com.auvdidao.a12teachingagent.artifactexport;

import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.project.Project;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteArtifactGeneratorTest {
    @Test
    void sendsPersistedArtifactJsonToInternalGeneratorAndReturnsBytes() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/file-generator/pptx", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = new byte[]{'P', 'K', 3, 4};
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            byte[] result = new RemoteArtifactGenerator("http://127.0.0.1:" + server.getAddress().getPort(), 2_000)
                    .renderPptx(project(), artifact());
            assertThat(result).startsWith((byte) 'P', (byte) 'K');
            assertThat(requestBody.get()).contains("\"artifactType\":\"PPT\"", "\"contentJson\"").doesNotContain("filePath");
        } finally { server.stop(0); }
    }

    @Test
    void mapsGeneratorValidationFailureToExistingBadRequestContract() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/file-generator/pptx", exchange -> {
            byte[] body = "{\"code\":\"INVALID_CONTENT\",\"message\":\"content JSON does not match schema version 1.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            RemoteArtifactGenerator generator = new RemoteArtifactGenerator("http://127.0.0.1:" + server.getAddress().getPort(), 2_000);
            assertThatThrownBy(() -> generator.renderPptx(project(), artifact()))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("content JSON does not match schema version 1");
        } finally { server.stop(0); }
    }

    private static Project project() { Project project = new Project(); project.setProjectName("AI foundations"); project.setCourseName("AI"); project.setChapterTopic("Core"); return project; }
    private static GeneratedArtifact artifact() { GeneratedArtifact artifact = new GeneratedArtifact(); artifact.setArtifactType(ArtifactType.PPT); artifact.setSchemaVersion(1); artifact.setTitle("AI deck"); artifact.setContentJson("{\"slides\":[{\"title\":\"Intro\",\"points\":[\"A\"]}]}"); return artifact; }
}
