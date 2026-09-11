package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.CompositionModels;
import com.auvdidao.a12.pptengine.contract.ContractGate;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.JsonSchemaCatalog;
import com.auvdidao.a12.pptengine.executor.ExecutorModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exports a controlled synthetic fixture for the separate Compose-network smoke. */
@SpringBootTest
@AutoConfigureMockMvc
class ComposeCrossServiceArtifactReceiptTest {

    private static final Instant FIXTURE_TIME = Instant.parse("2026-08-28T02:00:00Z");
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final Path EXPORT_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "a12-compose-cross-service-smoke");

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ContractGate contractGate;
    @Autowired
    private JsonSchemaCatalog schemaCatalog;

    @Test
    void exportsSyntheticComposeAndExecuteRequestsFromRealComposePlanResponse() throws Exception {
        deleteTree(EXPORT_ROOT);
        Files.createDirectories(EXPORT_ROOT.resolve("template"));
        Files.createDirectories(EXPORT_ROOT.resolve("asset"));
        Path source = EXPORT_ROOT.resolve("template/source.pptx");
        Path asset = EXPORT_ROOT.resolve("asset/approved.png");
        writeFixture(source);
        Files.write(asset, ONE_PIXEL_PNG);
        Files.setLastModifiedTime(source, FileTime.from(FIXTURE_TIME));
        Files.setLastModifiedTime(asset, FileTime.from(FIXTURE_TIME));

        CompositionModels.EngineComposePlanRequest base = CompositionTestSupport.request(objectMapper);
        String assetHash = sha256(asset);
        CompositionModels.ApprovedAssetManifestEntry entry = new CompositionModels.ApprovedAssetManifestEntry(
                "asset-001", ContractTypes.AssetResolution.APPROVED_ASSET, "approved-asset-001",
                ContractTypes.AssetType.IMAGE, assetHash, "asset/approved.png", Files.size(asset),
                OffsetDateTime.ofInstant(FIXTURE_TIME, java.time.ZoneOffset.UTC));
        CompositionModels.EngineComposePlanRequest request = CompositionTestSupport.withManifestEntries(
                objectMapper, base, "job-compose-cross-service", 1, List.of(entry));
        Files.writeString(EXPORT_ROOT.resolve("compose-request.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));

        String composeResponse = mockMvc.perform(post("/internal/v1/compose-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode composeTree = objectMapper.readTree(composeResponse);
        CompositionModels.ComposedPresentationPlan plan = objectMapper.treeToValue(
                composeTree.get("plan"), CompositionModels.ComposedPresentationPlan.class);
        assertThat(plan).isNotNull();
        assertThat(plan.slides()).hasSize(1);

        ExecutorModels.ExecuteRequest executeRequest = new ExecutorModels.ExecuteRequest(
                ContractTypes.EXECUTOR_CONTRACT_V1, request.requestId(), request.generationJob(),
                request.specification(), request.templateProfile(), request.approvedAssetManifest(), plan,
                new ExecutorModels.TemplateSourceBinding("template/source.pptx", sha256(source), Files.size(source),
                        FIXTURE_TIME.toString()),
                List.of(new ExecutorModels.ApprovedAssetFile("asset-001", "approved-asset-001",
                        "asset/approved.png", assetHash, Files.size(asset), FIXTURE_TIME.toString())));
        JsonNode rawExecute = objectMapper.valueToTree(executeRequest);
        int requestViolations = schemaCatalog.violationCount("engine-execute-request.schema.json", rawExecute);
        int specificationViolations = schemaCatalog.violationCount("locked-ppt-specification.schema.json", rawExecute.get("specification"));
        // Execute is a production execution-ready boundary; the legacy
        // confirmed-profile schema intentionally does not carry the explicit
        // service-owned eligibility/page-role provenance fields.
        int profileViolations = schemaCatalog.violationCount("execution-ready-template-profile.schema.json", rawExecute.get("templateProfile"));
        int manifestViolations = schemaCatalog.violationCount("approved-asset-manifest.schema.json", rawExecute.get("approvedAssetManifest"));
        assertThat(requestViolations + specificationViolations + profileViolations + manifestViolations)
                .withFailMessage(
                "execute contract counts request=%s specification=%s profile=%s manifest=%s",
                requestViolations, specificationViolations, profileViolations, manifestViolations).isZero();
        try {
        assertThat(contractGate.parseExecute(rawExecute)).isNotNull();
        ExecutorModels.ExecuteRequest roundTripped = objectMapper.treeToValue(rawExecute, ExecutorModels.ExecuteRequest.class);
        CompositionModels.ApprovedAssetManifestEntry roundEntry = roundTripped.approvedAssetManifest().entries().get(0);
        ExecutorModels.ApprovedAssetFile roundFile = roundTripped.approvedAssetFiles().get(0);
        assertThat(roundFile.assetRequirementId()).isEqualTo(roundEntry.assetRequirementId());
        assertThat(roundFile.approvedAssetId()).isEqualTo(roundEntry.approvedAssetId());
        assertThat(roundFile.absolutePath()).isEqualTo(roundEntry.storageKey());
        assertThat(roundFile.sha256()).isEqualTo(roundEntry.contentSha256());
        assertThat(roundFile.size()).isEqualTo(roundEntry.fileSize());
        assertThat(Instant.parse(roundFile.lastModifiedUtc())).isEqualTo(roundEntry.lastModifiedUtc().toInstant());
        } catch (com.auvdidao.a12.pptengine.contract.ContractRejectedException exception) {
            System.out.println("EXECUTE_DIAGNOSTIC=" + exception.diagnostic());
            throw exception;
        }
        Files.writeString(EXPORT_ROOT.resolve("execute-request.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(executeRequest));
        Files.writeString(EXPORT_ROOT.resolve("fixture-metadata.txt"),
                "synthetic=true\nsource=template/source.pptx\nasset=asset/approved.png\n" +
                        "sourceSha256=" + sha256(source) + "\nassetSha256=" + assetHash + "\n" +
                        "fixtureTime=" + FIXTURE_TIME + "\n");
    }

    private void writeFixture(Path path) throws Exception {
        try (OutputStream output = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Default Extension="png" ContentType="image/png"/>
                      <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                      <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                      <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
                      <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
                      <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
                    </Types>""");
            entry(zip, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/presentation.xml", "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><p:sldIdLst><p:sldId id=\"1\" r:id=\"rId1\"/></p:sldIdLst></p:presentation>");
            entry(zip, "ppt/_rels/presentation.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide1.xml\"/></Relationships>");
            entry(zip, "ppt/slides/slide1.xml", """
                    <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <p:cSld><p:spTree>
                        <p:nvGrpSpPr><p:cNvPr id="1" name="Group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
                        <p:sp><p:nvSpPr><p:cNvPr id="shape-body" name="shape-image"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="500000" y="500000"/><a:ext cx="5000000" cy="2500000"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr/><a:t>原文</a:t></a:r></a:p></p:txBody></p:sp>
                        <p:pic><p:nvPicPr><p:cNvPr id="shape-image" name="shape-body"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="6000000" y="500000"/><a:ext cx="4000000" cy="4000000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>
                      </p:spTree></p:cSld>
                    </p:sld>""");
            entry(zip, "ppt/slides/_rels/slide1.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image1.png"/>
                      <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/slideLayouts/slideLayout1.xml", "<p:sldLayout xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>");
            entry(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/slideMasters/slideMaster1.xml", "<p:sldMaster xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>");
            entry(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
                    </Relationships>""");
            entry(zip, "ppt/theme/theme1.xml", "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Default\"><a:themeElements/></a:theme>");
            entry(zip, "ppt/media/image1.png", ONE_PIXEL_PNG);
        }
    }

    private void entry(ZipOutputStream zip, String name, String value) throws Exception {
        entry(zip, name, value.getBytes(StandardCharsets.UTF_8));
    }

    private void entry(ZipOutputStream zip, String name, byte[] value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value);
        zip.closeEntry();
    }

    private String sha256(Path path) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static void deleteTree(Path root) throws Exception {
        if (Files.notExists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception exception) { throw new RuntimeException(exception); }
            });
        }
    }
}
