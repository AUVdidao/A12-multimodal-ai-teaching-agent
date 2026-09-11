package com.auvdidao.a12.pptengine;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import com.auvdidao.a12.pptengine.contract.JsonSchemaCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextFitPolicySchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaCatalog schemas = new JsonSchemaCatalog(mapper);

    @Test
    void legacyProfileWithoutPolicyRemainsSchemaCompatible() {
        assertThat(schemas.violationCount(
                "confirmed-template-profile.schema.json",
                mapper.valueToTree(ContractFixtures.profile()))).isZero();
    }

    @Test
    void constrainedPolicyMustBindNonUnboundFontEnvironment() {
        ObjectNode raw = mapper.valueToTree(ContractFixtures.profile());
        raw.set("textFitPolicy", mapper.valueToTree(constrained("UNBOUND")));

        assertThat(schemas.violationCount(
                "confirmed-template-profile.schema.json", raw)).isGreaterThan(0);
    }

    @Test
    void constrainedPolicyWithBoundFontEnvironmentMatchesSchema() {
        ObjectNode raw = mapper.valueToTree(ContractFixtures.profile());
        raw.set("textFitPolicy", mapper.valueToTree(constrained("font-env-v1")));

        assertThat(schemas.violationCount(
                "confirmed-template-profile.schema.json", raw)).isZero();
    }

    private ContractModels.TextFitPolicy constrained(String fontEnvironmentVersion) {
        return new ContractModels.TextFitPolicy(
                ContractTypes.TEXT_FIT_BOUNDARY_V1,
                ContractTypes.TextFitMode.PROFILE_CONSTRAINED,
                fontEnvironmentVersion,
                12, 20, 32, 1,
                80, 100, 140, 5,
                1000, 1000,
                0, 6, 18, 1,
                List.of("FONT_SIZE"));
    }
}
