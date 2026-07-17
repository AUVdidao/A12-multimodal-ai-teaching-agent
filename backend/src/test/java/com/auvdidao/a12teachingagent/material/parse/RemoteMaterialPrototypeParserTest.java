package com.auvdidao.a12teachingagent.material.parse;

import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.material.storage.LocalFileStorageService;
import com.auvdidao.a12teachingagent.material.storage.StorageProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteMaterialPrototypeParserTest {

    @TempDir
    Path tempDirectory;

    private HttpServer server;
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> requestBody = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/file-parser/parse", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            int status = responseStatus.get();
            byte[] response = (status == 200
                    ? "{\"summary\":\"remote summary\",\"keywords\":[\"RemoteContractMarker\"],\"teachingStages\":[\"概念讲解\"],\"analysisText\":\"RemoteContractMarker extracted text\"}"
                    : "{\"code\":\"EXTRACTION_FAILED\",\"message\":\"fixture failure\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void postsStoredFileBytesAndReturnsTheRemoteContract() throws Exception {
        RemoteMaterialPrototypeParser parser = parser();
        UploadedMaterial material = storedMaterial("RemoteContractMarker");

        MaterialPrototypeParser.ParsedContent parsed = parser.parse(
                material,
                List.of(PurposeType.TEXTBOOK_BASIS),
                requirement()
        );

        assertThat(parsed.summary()).isEqualTo("remote summary");
        assertThat(parsed.keywords()).containsExactly("RemoteContractMarker");
        assertThat(parsed.analysisText()).isEqualTo("RemoteContractMarker extracted text");
        assertThat(requestBody.get()).contains("RemoteContractMarker", "Content-Disposition: form-data; name=\"file\"");
        assertThat(requestBody.get()).doesNotContain(tempDirectory.toString());
    }

    @Test
    void turnsRemoteFailuresIntoMaterialParsingFailures() throws Exception {
        responseStatus.set(422);

        assertThatThrownBy(() -> parser().parse(storedMaterial("RemoteContractMarker"), List.of(), requirement()))
                .isInstanceOf(MaterialParsingException.class)
                .hasMessageContaining("Remote material parser rejected");
    }

    private RemoteMaterialPrototypeParser parser() {
        StorageProperties properties = new StorageProperties();
        properties.setUploadDir(tempDirectory.resolve("uploads").toString());
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new RemoteMaterialPrototypeParser(
                new LocalFileStorageService(properties),
                RestClient.builder().baseUrl(baseUrl).build(),
                20 * 1024 * 1024
        );
    }

    private UploadedMaterial storedMaterial(String text) throws Exception {
        Path projectDir = tempDirectory.resolve("uploads").resolve("1");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("fixture.md"), text, StandardCharsets.UTF_8);
        UploadedMaterial material = new UploadedMaterial();
        material.setProjectId(1L);
        material.setOriginalFileName("fixture.md");
        material.setFilePath("1/fixture.md");
        material.setFileType(MaterialFileType.MD);
        material.setFileSize((long) text.getBytes(StandardCharsets.UTF_8).length);
        return material;
    }

    private static RequirementSummary requirement() {
        RequirementSummary summary = new RequirementSummary();
        summary.setTopic("Remote contract topic");
        return summary;
    }
}
