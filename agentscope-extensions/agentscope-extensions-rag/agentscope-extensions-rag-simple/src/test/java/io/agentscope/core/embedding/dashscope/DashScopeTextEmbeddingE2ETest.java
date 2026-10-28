/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.embedding.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.test.StepVerifier;

/**
 * DashScopeTextEmbedding 端到端测试。
 *
 * <p>需要有效 DASHSCOPE_API_KEY 环境变量，并实际调用 DashScope 嵌入服务 API。
 *
 * <p>标记为 "e2e"，需要访问外部 API。
 */
@Tag("e2e")
@DisplayName("DashScopeTextEmbedding E2E Tests")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class DashScopeTextEmbeddingE2ETest {

    private static final String MODEL_NAME = "text-embedding-v3";
    private static final int EXPECTED_DIMENSIONS = 1024;

    private EmbeddingModel createModel() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        return DashScopeTextEmbedding.builder()
                .apiKey(apiKey)
                .modelName(MODEL_NAME)
                .dimensions(EXPECTED_DIMENSIONS)
                .build();
    }

    @Test
    @DisplayName("Should generate embedding for single text")
    void testSingleTextEmbedding() {
        EmbeddingModel model = createModel();

        TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();
        StepVerifier.create(model.embed(textBlock))
                .assertNext(
                        response -> {
                            double[] embedding = response.getEmbedding();
                            assertNotNull(embedding, "Embedding should not be null");
                            assertEquals(
                                    EXPECTED_DIMENSIONS,
                                    embedding.length,
                                    "Embedding dimension should match");
                            // 验证嵌入值合理，不全为零
                            boolean hasNonZero = false;
                            for (double value : embedding) {
                                if (Math.abs(value) > 0.0001) {
                                    hasNonZero = true;
                                    break;
                                }
                            }
                            assertTrue(hasNonZero, "Embedding should have non-zero values");
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should generate consistent embeddings for same text")
    void testEmbeddingConsistency() {
        EmbeddingModel model = createModel();
        TextBlock textBlock = TextBlock.builder().text("Test text for consistency").build();

        double[] embedding1 = model.embed(textBlock).block().getEmbedding();
        double[] embedding2 = model.embed(textBlock).block().getEmbedding();

        assertNotNull(embedding1);
        assertNotNull(embedding2);
        assertEquals(embedding1.length, embedding2.length);

        // 嵌入应相近，余弦相似度应较高
        // 注意：部分嵌入模型可能略有变化，因此检查近似相等
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < embedding1.length; i++) {
            dotProduct += embedding1[i] * embedding2[i];
            norm1 += embedding1[i] * embedding1[i];
            norm2 += embedding2[i] * embedding2[i];
        }
        double cosineSimilarity = dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
        assertTrue(
                cosineSimilarity > 0.9,
                "Embeddings for same text should be very similar, cosine similarity: "
                        + cosineSimilarity);
    }

    @Test
    @DisplayName("Should return correct model name and dimensions")
    void testModelProperties() {
        EmbeddingModel model = createModel();

        assertEquals(MODEL_NAME, model.getModelName());
        assertEquals(EXPECTED_DIMENSIONS, model.getDimensions());
    }
}
