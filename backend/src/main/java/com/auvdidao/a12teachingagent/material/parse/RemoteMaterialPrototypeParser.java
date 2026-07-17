package com.auvdidao.a12teachingagent.material.parse;

import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.material.storage.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(name = "a12.material-parser.mode", havingValue = "remote")
public class RemoteMaterialPrototypeParser implements MaterialPrototypeParser {

    private final FileStorageService fileStorageService;
    private final RestClient client;
    private final long maxRequestBytes;

    @Autowired
    public RemoteMaterialPrototypeParser(
            FileStorageService fileStorageService,
            @Value("${a12.material-parser.base-url}") String baseUrl,
            @Value("${a12.material-parser.timeout-ms:10000}") long timeoutMs,
            @Value("${a12.material-parser.max-request-bytes:20971520}") long maxRequestBytes
    ) {
        this(fileStorageService, remoteClient(baseUrl, timeoutMs), maxRequestBytes);
    }

    RemoteMaterialPrototypeParser(FileStorageService fileStorageService, RestClient client, long maxRequestBytes) {
        this.fileStorageService = fileStorageService;
        this.client = client;
        this.maxRequestBytes = maxRequestBytes;
    }

    @Override
    public ParsedContent parse(
            UploadedMaterial material,
            List<PurposeType> usageTypes,
            RequirementSummary requirementSummary
    ) {
        byte[] fileBytes = readBoundedContent(material);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", namedResource(fileBytes, material.getOriginalFileName()));
        body.add("fileType", material.getFileType().name());
        body.add("topic", topic(requirementSummary));
        for (PurposeType usageType : usageTypes == null ? List.<PurposeType>of() : usageTypes) {
            body.add("usageTypes", usageType.name());
        }

        try {
            RemoteParseResponse response = client.post()
                    .uri("/internal/file-parser/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(RemoteParseResponse.class);
            if (response == null || response.summary() == null || response.keywords() == null || response.teachingStages() == null) {
                throw new MaterialParsingException("Remote material parser returned an invalid response.");
            }
            String analysisText = response.analysisText() == null || response.analysisText().isBlank()
                    ? response.summary()
                    : response.analysisText();
            return new ParsedContent(
                    response.summary(),
                    response.keywords(),
                    response.teachingStages(),
                    analysisText
            );
        } catch (RestClientResponseException exception) {
            throw new MaterialParsingException("Remote material parser rejected the material: HTTP " + exception.getStatusCode().value() + ".");
        } catch (RestClientException exception) {
            throw new MaterialParsingException("Remote material parser is unavailable or timed out.");
        }
    }

    private byte[] readBoundedContent(UploadedMaterial material) {
        if (material == null || material.getFileType() == null || material.getFilePath() == null) {
            throw new MaterialParsingException("Material format or stored content is missing.");
        }
        if (material.getFileSize() != null && material.getFileSize() > maxRequestBytes) {
            throw new MaterialParsingException("Material file exceeds the remote parsing size limit.");
        }
        try {
            Resource resource = fileStorageService.load(material.getFilePath());
            if (resource.contentLength() > maxRequestBytes) {
                throw new MaterialParsingException("Material file exceeds the remote parsing size limit.");
            }
            try (InputStream input = resource.getInputStream()) {
                byte[] bytes = input.readNBytes((int) maxRequestBytes + 1);
                if (bytes.length == 0) {
                    throw new MaterialParsingException("Material file is empty.");
                }
                if (bytes.length > maxRequestBytes) {
                    throw new MaterialParsingException("Material file exceeds the remote parsing size limit.");
                }
                return bytes;
            }
        } catch (MaterialParsingException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MaterialParsingException("Material file could not be opened for remote parsing.");
        }
    }

    private static ByteArrayResource namedResource(byte[] content, String originalFilename) {
        String filename = originalFilename == null || originalFilename.isBlank() ? "material.bin" : originalFilename;
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static RestClient remoteClient(String baseUrl, long timeoutMs) {
        Duration timeout = Duration.ofMillis(Math.max(timeoutMs, 1_000));
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build()
        );
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private static String topic(RequirementSummary summary) {
        return summary == null || summary.getTopic() == null || summary.getTopic().isBlank()
                ? "当前课题"
                : summary.getTopic().strip();
    }

    private record RemoteParseResponse(
            String summary,
            List<String> keywords,
            List<String> teachingStages,
            String analysisText
    ) {
    }
}
