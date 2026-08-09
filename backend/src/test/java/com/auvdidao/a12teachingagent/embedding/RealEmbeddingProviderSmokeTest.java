package com.auvdidao.a12teachingagent.embedding;

import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RealEmbeddingProviderSmokeTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealEmbeddingProviderSmokeTest.class);
    private static final String SMOKE_FLAG = "A12_RUN_EMBEDDING_SMOKE";

    @Test
    void smokeGateRequiresExplicitTrue() {
        assertThat(isSmokeEnabled(null)).isFalse();
        assertThat(isSmokeEnabled("false")).isFalse();
        assertThat(isSmokeEnabled(" true ")).isTrue();
    }

    @Test
    void realProviderSmokeRunsOnlyWhenExplicitlyEnabled() {
        Assumptions.assumeTrue(
                isSmokeEnabled(System.getenv(SMOKE_FLAG)),
                "Set A12_RUN_EMBEDDING_SMOKE=true to run the real provider smoke test"
        );

        OpenAiCompatibleEmbeddingProvider provider = providerFromEnvironment();
        EmbeddingProviderDescriptor descriptor = provider.describe();
        assertThat(descriptor.enabled()).as("embedding provider must be enabled").isTrue();
        assertThat(descriptor.configured()).as("embedding provider must be configured").isTrue();

        String queryText = "什么是检索增强生成？";
        List<String> inputs = List.of(
                queryText,
                "RAG combines retrieval with generation.",
                "向量检索通过语义相似度寻找相关文本。",
                "Java Spring Boot application."
        );

        long started = System.nanoTime();
        EmbeddingBatchResult result = provider.embed(inputs);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        assertValidResult(result, descriptor, inputs.size());

        // Reuse the one real response to exercise the service mappings without extra network calls.
        ReplayEmbeddingProvider replayProvider = new ReplayEmbeddingProvider(descriptor, result, inputs);
        KnowledgeEmbeddingService embeddingService = new KnowledgeEmbeddingService(replayProvider);

        EmbeddedQuery embeddedQuery = embeddingService.embedQuery(queryText);
        assertThat(embeddedQuery.provider()).isEqualTo(descriptor.provider());
        assertThat(embeddedQuery.model()).isEqualTo(descriptor.model());
        assertThat(embeddedQuery.dimensions()).isEqualTo(result.dimensions());
        assertThat(embeddedQuery.vector()).hasSize(result.dimensions());
        assertThat(embeddedQuery.vector()).allSatisfy(value -> assertThat(value).isFinite());

        List<KnowledgeChunk> chunks = List.of(
                chunk(101L, 1, inputs.get(1)),
                chunk(102L, 2, inputs.get(2)),
                chunk(103L, 3, inputs.get(3))
        );
        List<EmbeddedKnowledgeChunk> embeddedChunks = embeddingService.embedChunks(chunks);
        assertThat(embeddedChunks).hasSize(3);
        assertThat(embeddedChunks).extracting(EmbeddedKnowledgeChunk::chunkId)
                .containsExactly(101L, 102L, 103L);
        assertThat(embeddedChunks).allSatisfy(chunk -> {
            assertThat(chunk.projectId()).isEqualTo(7L);
            assertThat(chunk.materialId()).isEqualTo(41L);
            assertThat(chunk.provider()).isEqualTo(descriptor.provider());
            assertThat(chunk.model()).isEqualTo(descriptor.model());
            assertThat(chunk.dimensions()).isEqualTo(result.dimensions());
            assertThat(chunk.vector()).hasSize(result.dimensions());
            assertThat(chunk.vector()).allSatisfy(value -> assertThat(value).isFinite());
        });

        LOGGER.info(
                "Real embedding provider smoke passed: provider={}, model={}, dimensions={}, inputCount={}, elapsedMs={}",
                descriptor.provider(),
                descriptor.model(),
                result.dimensions(),
                inputs.size(),
                elapsedMillis
        );
    }

    static boolean isSmokeEnabled(String value) {
        return value != null && "true".equalsIgnoreCase(value.strip());
    }

    private static OpenAiCompatibleEmbeddingProvider providerFromEnvironment() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setProvider(environment("A12_EMBEDDING_PROVIDER", properties.getProvider()));
        properties.setBaseUrl(environment("A12_EMBEDDING_BASE_URL", properties.getBaseUrl()));
        properties.setApiKey(environment("A12_EMBEDDING_API_KEY", properties.getApiKey()));
        properties.setModel(environment("A12_EMBEDDING_MODEL", properties.getModel()));
        properties.setTimeoutSeconds(environmentLong("A12_EMBEDDING_TIMEOUT_SECONDS", properties.getTimeoutSeconds()));
        properties.setBatchSize(environmentInt("A12_EMBEDDING_BATCH_SIZE", properties.getBatchSize()));
        properties.setRequestAttempts(environmentInt("A12_EMBEDDING_REQUEST_ATTEMPTS", properties.getRequestAttempts()));
        properties.setRetryDelayMillis(environmentLong(
                "A12_EMBEDDING_RETRY_DELAY_MILLIS",
                properties.getRetryDelayMillis()
        ));
        return new OpenAiCompatibleEmbeddingProvider(new com.fasterxml.jackson.databind.ObjectMapper(), properties);
    }

    private static void assertValidResult(
            EmbeddingBatchResult result,
            EmbeddingProviderDescriptor descriptor,
            int inputCount
    ) {
        assertThat(result).isNotNull();
        assertThat(result.provider()).isEqualTo(descriptor.provider());
        assertThat(result.model()).isEqualTo(descriptor.model());
        assertThat(result.dimensions()).isPositive();
        assertThat(result.vectors()).hasSize(inputCount);
        assertThat(result.vectors()).extracting(EmbeddingVector::index)
                .containsExactly(0, 1, 2, 3);
        assertThat(result.vectors()).allSatisfy(vector -> {
            assertThat(vector.values()).isNotEmpty().hasSize(result.dimensions());
            assertThat(vector.values()).allSatisfy(value -> assertThat(value).isFinite());
            assertThat(vector.index()).isBetween(0, inputCount - 1);
        });
    }

    private static KnowledgeChunk chunk(Long id, int chunkNo, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setProjectId(7L);
        chunk.setMaterialId(41L);
        chunk.setChunkNo(chunkNo);
        chunk.setSourceFilename("embedding-smoke.txt");
        chunk.setContent(content);
        return chunk;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static int environmentInt(String name, int fallback) {
        return Integer.parseInt(environment(name, Integer.toString(fallback)).strip());
    }

    private static long environmentLong(String name, long fallback) {
        return Long.parseLong(environment(name, Long.toString(fallback)).strip());
    }

    private static final class ReplayEmbeddingProvider implements EmbeddingProvider {

        private final EmbeddingProviderDescriptor descriptor;
        private final Map<String, EmbeddingVector> vectorsByInput;
        private final int dimensions;

        private ReplayEmbeddingProvider(
                EmbeddingProviderDescriptor descriptor,
                EmbeddingBatchResult result,
                List<String> inputs
        ) {
            this.descriptor = descriptor;
            this.dimensions = result.dimensions();
            Map<String, EmbeddingVector> vectors = new HashMap<>();
            for (int index = 0; index < inputs.size(); index++) {
                vectors.put(inputs.get(index), result.vectors().get(index));
            }
            this.vectorsByInput = Map.copyOf(vectors);
        }

        @Override
        public EmbeddingProviderDescriptor describe() {
            return descriptor;
        }

        @Override
        public EmbeddingBatchResult embed(List<String> inputs) {
            List<EmbeddingVector> vectors = new ArrayList<>(inputs.size());
            for (int index = 0; index < inputs.size(); index++) {
                EmbeddingVector source = vectorsByInput.get(inputs.get(index));
                if (source == null) {
                    throw new IllegalArgumentException("Smoke replay input was not captured");
                }
                vectors.add(new EmbeddingVector(index, source.values()));
            }
            return new EmbeddingBatchResult(descriptor.provider(), descriptor.model(), dimensions, vectors);
        }
    }
}
