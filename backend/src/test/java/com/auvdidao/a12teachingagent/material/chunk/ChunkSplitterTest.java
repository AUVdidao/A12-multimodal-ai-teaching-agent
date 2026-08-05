package com.auvdidao.a12teachingagent.material.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkSplitterTest {

    @Test
    void splitsCleanedParagraphsWithinConfiguredLimit() {
        ChunkSplitter splitter = new ChunkSplitter(new TextCleaner(), 40, 8, 5);

        var chunks = splitter.split("第一段内容，包含需要保留的教学信息。\n\n第二段内容，用于验证分段边界。\n\n第三段内容。");

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(40));
        assertThat(String.join("\n", chunks)).contains("第一段内容", "第二段内容", "第三段内容");
    }

    @Test
    void returnsEmptyForBlankInput() {
        assertThat(new ChunkSplitter(new TextCleaner()).split(" \n\n ")).isEmpty();
    }
}
