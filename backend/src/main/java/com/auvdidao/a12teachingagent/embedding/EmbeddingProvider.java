package com.auvdidao.a12teachingagent.embedding;

import java.util.List;

/**
 * Provider-neutral contract for obtaining embeddings.
 *
 * <p>This contract deliberately says nothing about vector persistence or
 * retrieval. Implementations only translate text inputs into vectors.</p>
 */
public interface EmbeddingProvider {

    EmbeddingBatchResult embed(List<String> inputs);
}
