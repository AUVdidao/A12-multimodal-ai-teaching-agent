package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.material.parse.MaterialPrototypeParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaterialParseIdentityTest {

    @Test
    void parserSnapshotChecksumIsDeterministicAndChangesWithParserOutput() {
        MaterialPrototypeParser.ParsedContent first = parsed("summary", "text");
        MaterialPrototypeParser.ParsedContent same = parsed("summary", "text");
        MaterialPrototypeParser.ParsedContent changed = parsed("summary changed", "text");

        assertThat(MaterialParseIdentity.snapshotChecksum(first))
                .isEqualTo(MaterialParseIdentity.snapshotChecksum(same))
                .hasSize(64)
                .isNotEqualTo(MaterialParseIdentity.snapshotChecksum(changed));
    }

    @Test
    void completeIdentityRequiresAllServerOwnedFields() {
        ParseResult result = new ParseResult();
        result.setMaterialId(7L);
        result.setAnalysisRunId("material-analysis-run");
        result.setSourceVersionId(7L);
        result.setParserSnapshotChecksum("a".repeat(64));
        result.setParseStatus(MaterialParseStatus.SUCCEEDED);

        MaterialParseIdentity.requireComplete(result);

        result.setParserSnapshotChecksum(null);
        assertThatThrownBy(() -> MaterialParseIdentity.requireComplete(result))
                .hasMessage("PARSE_RESULT_IDENTITY_UNAVAILABLE");
    }

    private static MaterialPrototypeParser.ParsedContent parsed(String summary, String text) {
        return new MaterialPrototypeParser.ParsedContent(summary, List.of("keyword"),
                List.of("stage"), text, text, 2, List.of("section"));
    }
}
