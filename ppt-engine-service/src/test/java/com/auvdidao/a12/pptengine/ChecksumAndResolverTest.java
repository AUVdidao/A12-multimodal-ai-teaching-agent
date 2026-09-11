package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ChecksumService;
import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.resolver.ComponentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksumAndResolverTest {

    private static final String CHECKSUM_VECTOR = "01923be87d5d379d363d922a61b1eaa8ed2b014882a7d01e76572fff470c6d25";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ChecksumService checksumService = new ChecksumService(objectMapper);
    private final ComponentResolver resolver = new ComponentResolver();

    @Test
    void checksumMatchesIndependentVectorAndIsStableAcrossJvms() throws Exception {
        JsonNodeHolder holder = JsonNodeHolder.load(objectMapper);
        ContractModels.LockedPptSpecification specification = objectMapper.treeToValue(
                holder.root().get("specification"), ContractModels.LockedPptSpecification.class);
        assertThat(checksumService.compute(specification)).isEqualTo(CHECKSUM_VECTOR);

        for (int index = 0; index < 20; index++) {
            assertThat(checksumService.compute(specification)).isEqualTo(CHECKSUM_VECTOR);
        }

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        for (int index = 0; index < 2; index++) {
            Process process = new ProcessBuilder(java, "-cp", classpath, ChecksumVectorProcess.class.getName())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertThat(process.waitFor()).isZero();
            assertThat(output).isEqualTo(CHECKSUM_VECTOR);
        }
    }

    @Test
    void checksumChangesWhenLockedContentChangesAndIgnoresOnlyChecksumField() {
        ContractModels.LockedPptSpecification original = ContractFixtures.specification(objectMapper);
        ContractModels.LockedPptSpecification equivalent = ContractFixtures.withChecksum(objectMapper,
                new ContractModels.LockedPptSpecification(
                        original.contractVersion(), original.specificationId(), original.projectId(),
                        original.version(), original.status(), original.templateProfileId(),
                        original.templateProfileVersion(), original.targetSlideCount(),
                        original.slideCountTolerance(), original.locale(), original.provider(), original.model(),
                        original.aiSupplementPolicy(), original.lockedBy(), original.lockedAt(),
                        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", original.slides()));
        assertThat(equivalent.checksum()).isEqualTo(original.checksum());

        ContractModels.LockedPptSlide changedSlide = new ContractModels.LockedPptSlide(
                "slide-001", 1, "改变后的标题", "说明光合作用的基本过程", original.slides().get(0).contentBlocks(),
                original.slides().get(0).semanticLayout(), original.slides().get(0).assetRequirements(),
                original.slides().get(0).provenance(), original.slides().get(0).notes());
        ContractModels.LockedPptSpecification changed = ContractFixtures.withChecksum(objectMapper,
                new ContractModels.LockedPptSpecification(
                        original.contractVersion(), original.specificationId(), original.projectId(),
                        original.version(), original.status(), original.templateProfileId(),
                        original.templateProfileVersion(), original.targetSlideCount(),
                        original.slideCountTolerance(), original.locale(), original.provider(), original.model(),
                        original.aiSupplementPolicy(), original.lockedBy(), original.lockedAt(),
                        null, List.of(changedSlide)));
        assertThat(changed.checksum()).isNotEqualTo(original.checksum());
    }

    @Test
    void resolverIsDeterministicAndDoesNotMutateInput() throws Exception {
        ContractModels.EnginePreflightRequest request = ContractFixtures.request(objectMapper);
        String before = objectMapper.writeValueAsString(request);
        List<String> plans = new ArrayList<>();

        for (int index = 0; index < 20; index++) {
            ComponentResolver.ResolverResult result = resolver.resolve(
                    request.specification().slides().get(0), request.templateProfile());
            plans.add(objectMapper.writeValueAsString(result.plan()));
            assertThat(result.diagnostics()).isEmpty();
            assertThat(result.plan().blockToSlotBindings()).containsEntry("block-001", "slot-body");
            assertThat(result.plan().assetToSlotBindings()).containsEntry("asset-001", "slot-image");
            assertThat(result.plan().selectedComponentIds())
                    .containsExactly("component-body", "component-image");
        }

        assertThat(plans).allMatch(plans.get(0)::equals);
        assertThat(objectMapper.writeValueAsString(request)).isEqualTo(before);
    }

    @Test
    void unconfirmedComponentIsNeverSelectedAndTransformMismatchIsDiagnosed() {
        ContractModels.EnginePreflightRequest request = ContractFixtures.request(objectMapper);
        ContractModels.SemanticLayout strictLayout = new ContractModels.SemanticLayout(
                "BODY", List.of(), ContractTypes.TransformConstraint.UNIFORM_SCALE);
        ContractModels.LockedPptSlide strictSlide = new ContractModels.LockedPptSlide(
                "slide-001", 1, "光合作用", "说明光合作用的基本过程", request.specification().slides().get(0).contentBlocks(),
                strictLayout, List.of(), request.specification().slides().get(0).provenance(), "");

        ComponentResolver.ResolverResult result = resolver.resolve(strictSlide, request.templateProfile());
        assertThat(result.plan().selectedComponentIds()).doesNotContain("component-unconfirmed");
        assertThat(result.diagnostics()).extracting(ContractModels.Diagnostic::code)
                .contains("TRANSFORM_NOT_ALLOWED");
    }

    private record JsonNodeHolder(com.fasterxml.jackson.databind.JsonNode root) {
        private static JsonNodeHolder load(ObjectMapper objectMapper) throws Exception {
            return new JsonNodeHolder(objectMapper.readTree(
                    ChecksumAndResolverTest.class.getResourceAsStream(
                            "/contracts/v1/examples/valid/preflight-request.json")));
        }
    }
}
