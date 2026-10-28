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
package io.agentscope.core.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.EmbeddingResponse;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * KnowledgeRetrievalTools 单元测试。
 */
@Tag("unit")
@DisplayName("KnowledgeRetrievalTools Unit Tests")
class KnowledgeRetrievalToolsTest {

    private static final int DIMENSIONS = 3;

    private Knowledge knowledge;
    private RetrieveConfig retrieveConfig;
    private KnowledgeRetrievalTools tools;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        TestMockEmbeddingModel embeddingModel = new TestMockEmbeddingModel(DIMENSIONS);
        InMemoryStore vectorStore = InMemoryStore.builder().dimensions(DIMENSIONS).build();
        knowledge =
                SimpleKnowledge.builder()
                        .embeddingModel(embeddingModel)
                        .embeddingStore(vectorStore)
                        .build();
        retrieveConfig = RetrieveConfig.builder().build();
        tools = new KnowledgeRetrievalTools(knowledge, retrieveConfig);
        toolkit = new Toolkit();
    }

    @Test
    @DisplayName("Should create KnowledgeRetrievalTools with valid knowledge base")
    void testCreate() {
        KnowledgeRetrievalTools newTools = new KnowledgeRetrievalTools(knowledge, retrieveConfig);
        assertNotNull(newTools);
        assertEquals(knowledge, newTools.getKnowledgeBase());
        assertEquals(retrieveConfig, newTools.getDefaultConfig());
    }

    @Test
    @DisplayName("Should create KnowledgeRetrievalTools with default config")
    void testCreateWithDefaultConfig() {
        KnowledgeRetrievalTools newTools = new KnowledgeRetrievalTools(knowledge);
        assertNotNull(newTools);
        assertEquals(knowledge, newTools.getKnowledgeBase());
        assertNotNull(newTools.getDefaultConfig());
        assertEquals(5, newTools.getDefaultConfig().getLimit());
        assertEquals(0.5, newTools.getDefaultConfig().getScoreThreshold());
    }

    @Test
    @DisplayName("Should throw exception for null knowledge base")
    void testCreateNullKnowledgeBase() {
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeRetrievalTools(null));
    }

    @Test
    @DisplayName("Should throw exception for null retrieve config")
    void testCreateNullConfig() {
        assertThrows(
                IllegalArgumentException.class, () -> new KnowledgeRetrievalTools(knowledge, null));
    }

    @Test
    @DisplayName("Should create KnowledgeRetrievalTools with custom retrieve config")
    void testCreateCustomRetrieveConfig() {
        List<Msg> conversationHistory = List.of(Msg.builder().textContent("Hello").build());
        RetrieveConfig customRetrieveConfig =
                RetrieveConfig.builder()
                        .limit(3)
                        .scoreThreshold(0.9)
                        .vectorName("test_vector")
                        .conversationHistory(conversationHistory)
                        .build();
        KnowledgeRetrievalTools newTools =
                new KnowledgeRetrievalTools(knowledge, customRetrieveConfig);
        assertNotNull(newTools);
        assertEquals(knowledge, newTools.getKnowledgeBase());
        assertEquals(customRetrieveConfig, newTools.getDefaultConfig());
        assertEquals(3, newTools.getDefaultConfig().getLimit());
        assertEquals(0.9, newTools.getDefaultConfig().getScoreThreshold());
        assertEquals("test_vector", newTools.getDefaultConfig().getVectorName());
        assertEquals(conversationHistory, newTools.getDefaultConfig().getConversationHistory());
    }

    @Test
    @DisplayName("Should register tool with Toolkit")
    void testToolRegistration() {
        toolkit.registerTool(tools);

        Set<String> toolNames = toolkit.getToolNames();
        assertTrue(
                toolNames.contains("retrieve_knowledge"),
                "Should register retrieve_knowledge tool");
    }

    @Test
    @DisplayName("Should parse @Tool annotation correctly")
    void testToolAnnotation() {
        toolkit.registerTool(tools);

        AgentTool tool = toolkit.getTool("retrieve_knowledge");
        assertNotNull(tool);
        assertEquals("retrieve_knowledge", tool.getName());
        assertTrue(tool.getDescription().contains("Retrieve relevant documents"));
    }

    @Test
    @DisplayName("Should parse @ToolParam annotations correctly")
    void testToolParamAnnotations() {
        toolkit.registerTool(tools);

        AgentTool tool = toolkit.getTool("retrieve_knowledge");
        assertNotNull(tool);

        // 验证已定义参数
        Map<String, Object> parameters = tool.getParameters();
        assertNotNull(parameters);
    }

    @Test
    @DisplayName("Should execute retrieveKnowledge tool")
    void testRetrieveKnowledgeTool() {
        // 向知识库添加文档
        Document doc1 = createDocument("doc1", "Machine learning is interesting");
        Document doc2 = createDocument("doc2", "Java programming language");
        knowledge.addDocuments(List.of(doc1, doc2)).block();

        // 注册工具
        toolkit.registerTool(tools);

        // 执行工具
        AgentTool tool = toolkit.getTool("retrieve_knowledge");
        assertNotNull(tool);

        Map<String, Object> params = Map.of("query", "machine learning", "limit", 5);
        var result = tool.callAsync(ToolCallParam.builder().input(params).build()).block();

        assertNotNull(result);
        assertTrue(ToolTestUtils.isValidToolResultBlock(result));

        // 验证结果包含检索信息
        String resultText = extractTextFromResult(result);
        assertTrue(resultText.contains("Retrieved") || resultText.contains("relevant"));
    }

    @Test
    @DisplayName("Should use default limit when not provided")
    void testRetrieveKnowledgeWithDefaultLimit() {
        // 添加文档
        Document doc = createDocument("doc1", "Test content");
        knowledge.addDocuments(List.of(doc)).block();

        // 注册工具
        toolkit.registerTool(tools);

        // 执行工具，不传 limit 参数
        AgentTool tool = toolkit.getTool("retrieve_knowledge");
        Map<String, Object> params = Map.of("query", "test");
        var result = tool.callAsync(ToolCallParam.builder().input(params).build()).block();

        assertNotNull(result);
        assertTrue(ToolTestUtils.isValidToolResultBlock(result));
    }

    @Test
    @DisplayName("Should handle empty knowledge base")
    void testRetrieveKnowledgeEmptyBase() {
        // 注册工具
        toolkit.registerTool(tools);

        // 对空知识库执行工具
        AgentTool tool = toolkit.getTool("retrieve_knowledge");
        Map<String, Object> params = Map.of("query", "test query");
        var result = tool.callAsync(ToolCallParam.builder().input(params).build()).block();

        assertNotNull(result);
        String resultText = extractTextFromResult(result);
        assertTrue(
                resultText.contains("No relevant documents")
                        || resultText.contains("Failed to retrieve"));
    }

    @Test
    @DisplayName("Should format documents correctly")
    void testFormatDocuments() {
        // 添加文档
        Document doc1 = createDocument("doc1", "Content 1");
        Document doc2 = createDocument("doc2", "Content 2");
        knowledge.addDocuments(List.of(doc1, doc2)).block();

        // 调用工具
        String result =
                extractTextFromResult(tools.retrieveKnowledge("content", 5, null, null).block());

        assertNotNull(result);
        assertTrue(result.contains("Retrieved"));
        // 验证存在内容，分数可能随相似度变化
        assertTrue(result.contains("Content 1") || result.contains("Content 2"));
        // 分数格式可能变化，仅验证包含分数信息
        assertTrue(result.contains("Score:") || result.contains("relevant"));
    }

    @Test
    @DisplayName("Should handle retrieval errors gracefully")
    void testRetrieveKnowledgeError() {
        // 创建会失败的知识库
        TestMockEmbeddingModel errorModel = new TestMockEmbeddingModel(DIMENSIONS);
        errorModel.setShouldThrowError(true);
        InMemoryStore vectorStore = InMemoryStore.builder().dimensions(DIMENSIONS).build();
        Knowledge errorKB =
                SimpleKnowledge.builder()
                        .embeddingModel(errorModel)
                        .embeddingStore(vectorStore)
                        .build();
        KnowledgeRetrievalTools errorTools = new KnowledgeRetrievalTools(errorKB);

        // 应返回错误消息而非抛异常
        String result =
                extractTextFromResult(errorTools.retrieveKnowledge("query", 5, null, null).block());
        assertNotNull(result);
        assertTrue(result.contains("Failed to retrieve"));
    }

    @Test
    @DisplayName("Should return empty message for empty results")
    void testRetrieveKnowledgeEmptyResults() {
        // 知识库为空，检索应返回空消息
        String result =
                extractTextFromResult(tools.retrieveKnowledge("query", 5, null, null).block());

        assertNotNull(result);
        assertTrue(
                result.contains("No relevant documents") || result.contains("Failed to retrieve"));
    }

    /**
     * 创建测试文档。
     */
    private Document createDocument(String docId, String content) {
        TextBlock textBlock = TextBlock.builder().text(content).build();
        DocumentMetadata metadata = new DocumentMetadata(textBlock, docId, "0");
        return new Document(metadata);
    }

    /**
     * 从 ToolResultBlock 提取文本内容。
     */
    private String extractTextFromResult(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) {
            return "";
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .findFirst()
                .orElse("");
    }

    /**
     * 测试用模拟 EmbeddingModel。
     */
    private static class TestMockEmbeddingModel implements EmbeddingModel {
        private final int dimensions;
        private final Map<String, double[]> embeddings = new HashMap<>();
        private boolean shouldThrowError = false;

        TestMockEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        void setShouldThrowError(boolean shouldThrowError) {
            this.shouldThrowError = shouldThrowError;
        }

        @Override
        public Mono<EmbeddingResponse> embed(ContentBlock block) {
            if (shouldThrowError) {
                return Mono.error(new RuntimeException("Mock embedding error"));
            }
            if (block instanceof TextBlock) {
                String text = ((TextBlock) block).getText();
                return Mono.fromCallable(
                        () -> {
                            // 依据文本生成确定性嵌入
                            double[] embedding =
                                    embeddings.computeIfAbsent(text, k -> generateEmbedding(text));
                            return new EmbeddingResponse(embedding.clone(), null);
                        });
            }
            return Mono.error(new UnsupportedOperationException("Unsupported content block type"));
        }

        @Override
        public String getModelName() {
            return "mock-embedding-model";
        }

        @Override
        public int getDimensions() {
            return dimensions;
        }

        private double[] generateEmbedding(String text) {
            // 生成简单的确定性嵌入
            double[] embedding = new double[dimensions];
            int hash = text.hashCode();
            for (int i = 0; i < dimensions; i++) {
                embedding[i] = (double) ((hash + i) % 100) / 100.0;
            }
            // 归一化
            double norm = 0.0;
            for (double v : embedding) {
                norm += v * v;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < dimensions; i++) {
                    embedding[i] /= norm;
                }
            }
            return embedding;
        }
    }
}
