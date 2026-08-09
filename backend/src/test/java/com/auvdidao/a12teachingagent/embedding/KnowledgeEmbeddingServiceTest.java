package com.auvdidao.a12teachingagent.embedding;

import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeEmbeddingServiceTest {

    @Test
    void embedsOneChunkWithIdentityAndProviderMetadata() {
        FakeProvider provider = providerFor(List.of(vector(0, 1.0, 0.0)));
        KnowledgeChunk chunk = chunk(11L, 7L, 41L, 3, "  alpha  ", "lesson.md");

        EmbeddedKnowledgeChunk embedded = new KnowledgeEmbeddingService(provider)
                .embedChunks(List.of(chunk))
                .get(0);

        assertThat(provider.calls).containsExactly(List.of("alpha"));
        assertThat(embedded.chunkId()).isEqualTo(11L);
        assertThat(embedded.projectId()).isEqualTo(7L);
        assertThat(embedded.materialId()).isEqualTo(41L);
        assertThat(embedded.chunkNo()).isEqualTo(3);
        assertThat(embedded.sourceFilename()).isEqualTo("lesson.md");
        assertThat(embedded.provider()).isEqualTo("fake");
        assertThat(embedded.model()).isEqualTo("fake-model");
        assertThat(embedded.dimensions()).isEqualTo(2);
        assertThat(embedded.vector()).containsExactly(1.0, 0.0);
    }

    @Test
    void preservesChunkOrderAndIdentityWhenProviderVectorsAreReordered() {
        FakeProvider provider = providerFor(List.of(
                vector(2, 0.0, 0.3),
                vector(0, 0.1, 0.0),
                vector(1, 0.0, 0.2)
        ));
        List<KnowledgeChunk> chunks = List.of(
                chunk(101L, 7L, 41L, 1, "alpha", "a.md"),
                chunk(102L, 7L, 41L, 2, "beta", "a.md"),
                chunk(103L, 7L, 41L, 3, "gamma", "a.md")
        );

        List<EmbeddedKnowledgeChunk> embedded = new KnowledgeEmbeddingService(provider).embedChunks(chunks);

        assertThat(provider.calls).containsExactly(List.of("alpha", "beta", "gamma"));
        assertThat(embedded).extracting(EmbeddedKnowledgeChunk::chunkId).containsExactly(101L, 102L, 103L);
        assertThat(embedded).extracting(EmbeddedKnowledgeChunk::projectId).containsOnly(7L);
        assertThat(embedded).extracting(EmbeddedKnowledgeChunk::materialId).containsOnly(41L);
        assertThat(embedded).extracting(EmbeddedKnowledgeChunk::chunkNo).containsExactly(1, 2, 3);
        assertThat(embedded).extracting(EmbeddedKnowledgeChunk::vector).containsExactly(
                List.of(0.1, 0.0), List.of(0.0, 0.2), List.of(0.0, 0.3)
        );
    }

    @Test
    void normalizesChunkContentWithoutChangingSemanticText() {
        FakeProvider provider = providerFor(List.of(vector(0, 1.0, 0.0)));
        KnowledgeChunk chunk = chunk(11L, 7L, 41L, 3, "  alpha\r\nbeta\rgamma  ", "lesson.md");

        new KnowledgeEmbeddingService(provider).embedChunks(List.of(chunk));

        assertThat(provider.calls).containsExactly(List.of("alpha\nbeta\ngamma"));
    }

    @Test
    void rejectsNullAndEmptyChunkLists() {
        KnowledgeEmbeddingService service = new KnowledgeEmbeddingService(providerFor(List.of()));

        assertInvalidInput(() -> service.embedChunks(null));
        assertInvalidInput(() -> service.embedChunks(List.of()));
    }

    @Test
    void rejectsNullChunk() {
        assertInvalidInput(() -> new KnowledgeEmbeddingService(providerFor(List.of()))
                .embedChunks(java.util.Collections.singletonList(null)));
    }

    @Test
    void rejectsMissingChunkId() {
        KnowledgeChunk chunk = chunk(null, 7L, 41L, 3, "alpha", "lesson.md");
        assertInvalidInput(() -> new KnowledgeEmbeddingService(providerFor(List.of())).embedChunks(List.of(chunk)));
    }

    @Test
    void rejectsMissingProjectId() {
        KnowledgeChunk chunk = chunk(11L, null, 41L, 3, "alpha", "lesson.md");
        assertInvalidInput(() -> new KnowledgeEmbeddingService(providerFor(List.of())).embedChunks(List.of(chunk)));
    }

    @Test
    void rejectsMissingMaterialId() {
        KnowledgeChunk chunk = chunk(11L, 7L, null, 3, "alpha", "lesson.md");
        assertInvalidInput(() -> new KnowledgeEmbeddingService(providerFor(List.of())).embedChunks(List.of(chunk)));
    }

    @Test
    void rejectsMissingChunkNo() {
        KnowledgeChunk chunk = chunk(11L, 7L, 41L, null, "alpha", "lesson.md");
        assertInvalidInput(() -> new KnowledgeEmbeddingService(providerFor(List.of())).embedChunks(List.of(chunk)));
    }

    @Test
    void rejectsBlankContentWithoutLeakingContent() {
        String invalidContent = "private full source content";
        KnowledgeChunk chunk = chunk(11L, 7L, 41L, 3, " \t", "lesson.md");

        assertThatThrownBy(() -> new KnowledgeEmbeddingService(providerFor(List.of())).embedChunks(List.of(chunk)))
                .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                    assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_INPUT);
                    assertThat(exception).hasMessageNotContaining(invalidContent);
                });
    }

    @Test
    void rejectsNullProviderResult() {
        FakeProvider provider = new FakeProvider(inputs -> null);

        assertInvalidResponse(() -> new KnowledgeEmbeddingService(provider).embedChunks(
                List.of(chunk(11L, 7L, 41L, 3, "alpha", "lesson.md"))
        ));
    }

    @Test
    void rejectsWrongProviderResultCount() {
        FakeProvider provider = providerFor(List.of(vector(0, 1.0, 0.0)));
        List<KnowledgeChunk> chunks = List.of(
                chunk(11L, 7L, 41L, 1, "alpha", "lesson.md"),
                chunk(12L, 7L, 41L, 2, "beta", "lesson.md")
        );

        assertInvalidResponse(() -> new KnowledgeEmbeddingService(provider).embedChunks(chunks));
    }

    @Test
    void rejectsMissingProviderIndex() {
        FakeProvider provider = providerFor(List.of(vector(0, 1.0, 0.0), vector(2, 0.0, 1.0)));
        List<KnowledgeChunk> chunks = List.of(
                chunk(11L, 7L, 41L, 1, "alpha", "lesson.md"),
                chunk(12L, 7L, 41L, 2, "beta", "lesson.md")
        );

        assertInvalidResponse(() -> new KnowledgeEmbeddingService(provider).embedChunks(chunks));
    }

    @Test
    void rejectsInvalidProviderDimensions() {
        FakeProvider provider = new FakeProvider(inputs -> new EmbeddingBatchResult(
                "fake", "fake-model", 0, List.of(vector(0, 1.0, 0.0))
        ));

        assertInvalidResponse(() -> new KnowledgeEmbeddingService(provider).embedChunks(
                List.of(chunk(11L, 7L, 41L, 3, "alpha", "lesson.md"))
        ));
    }

    @Test
    void rejectsInconsistentProviderVectorDimensions() {
        FakeProvider provider = new FakeProvider(inputs -> new EmbeddingBatchResult(
                "fake", "fake-model", 2, List.of(vector(0, 1.0, 0.0), vector(1, 0.0))
        ));

        assertInvalidResponse(() -> new KnowledgeEmbeddingService(provider).embedChunks(List.of(
                chunk(11L, 7L, 41L, 1, "alpha", "lesson.md"),
                chunk(12L, 7L, 41L, 2, "beta", "lesson.md")
        )));
    }

    @Test
    void embedsQueryWithExactlyOneProviderInputAndInheritedMetadata() {
        FakeProvider provider = providerFor(List.of(vector(0, 0.4, 0.6)));

        EmbeddedQuery query = new KnowledgeEmbeddingService(provider).embedQuery("  alpha beta  ");

        assertThat(provider.calls).containsExactly(List.of("alpha beta"));
        assertThat(query.provider()).isEqualTo("fake");
        assertThat(query.model()).isEqualTo("fake-model");
        assertThat(query.dimensions()).isEqualTo(2);
        assertThat(query.vector()).containsExactly(0.4, 0.6);
    }

    @Test
    void queryOnlyStripsOuterWhitespace() {
        FakeProvider provider = providerFor(List.of(vector(0, 0.4, 0.6)));

        new KnowledgeEmbeddingService(provider).embedQuery("  alpha\r\nbeta  ");

        assertThat(provider.calls).containsExactly(List.of("alpha\r\nbeta"));
    }

    @Test
    void rejectsBlankQuery() {
        FakeProvider provider = providerFor(List.of(vector(0, 0.4, 0.6)));

        assertInvalidInput(() -> new KnowledgeEmbeddingService(provider).embedQuery(" \t\r\n"));
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void rejectsQueryProviderResultWithWrongCount() {
        FakeProvider provider = new FakeProvider(inputs -> new EmbeddingBatchResult(
                "fake", "fake-model", 2, List.of()
        ));

        assertInvalidResponse(() -> new KnowledgeEmbeddingService(provider).embedQuery("alpha"));
    }

    @Test
    void rejectsQueryProviderResultWithMissingIndex() {
        FakeProvider provider = providerFor(List.of(vector(1, 0.4, 0.6)));

        assertInvalidResponse(() -> new KnowledgeEmbeddingService(provider).embedQuery("alpha"));
    }

    @Test
    void embeds601ChunksThroughProviderOnceAndPreservesAllIdentities() {
        List<EmbeddingVector> vectors = new ArrayList<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int index = 0; index < 601; index++) {
            vectors.add(vector(index, 1.0, 0.0));
            chunks.add(chunk(10_000L + index, 7L, 41L, index + 1, "text-" + index, "lesson.md"));
        }
        FakeProvider provider = providerFor(vectors);

        List<EmbeddedKnowledgeChunk> embedded = new KnowledgeEmbeddingService(provider).embedChunks(chunks);

        assertThat(provider.calls).hasSize(1);
        assertThat(provider.calls.get(0)).hasSize(601);
        assertThat(embedded).hasSize(601);
        for (int index = 0; index < 601; index++) {
            assertThat(embedded.get(index).chunkId()).isEqualTo(10_000L + index);
            assertThat(embedded.get(index).chunkNo()).isEqualTo(index + 1);
        }
    }

    private static FakeProvider providerFor(List<EmbeddingVector> vectors) {
        return new FakeProvider(inputs -> new EmbeddingBatchResult(
                "fake", "fake-model", 2, vectors
        ));
    }

    private static EmbeddingVector vector(int index, double... values) {
        return new EmbeddingVector(index, java.util.Arrays.stream(values).boxed().toList());
    }

    private static KnowledgeChunk chunk(
            Long id,
            Long projectId,
            Long materialId,
            Integer chunkNo,
            String content,
            String sourceFilename
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setProjectId(projectId);
        chunk.setMaterialId(materialId);
        chunk.setChunkNo(chunkNo);
        chunk.setContent(content);
        chunk.setSourceFilename(sourceFilename);
        return chunk;
    }

    private static void assertInvalidInput(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_INPUT));
    }

    private static void assertInvalidResponse(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(EmbeddingException.class, exception ->
                        assertThat(exception.getKind()).isEqualTo(EmbeddingFailureKind.INVALID_RESPONSE));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private static final class FakeProvider implements EmbeddingProvider {

        private final Function<List<String>, EmbeddingBatchResult> responseFactory;
        private final List<List<String>> calls = new ArrayList<>();

        private FakeProvider(Function<List<String>, EmbeddingBatchResult> responseFactory) {
            this.responseFactory = responseFactory;
        }

        @Override
        public EmbeddingBatchResult embed(List<String> inputs) {
            calls.add(List.copyOf(inputs));
            return responseFactory.apply(inputs);
        }
    }
}
