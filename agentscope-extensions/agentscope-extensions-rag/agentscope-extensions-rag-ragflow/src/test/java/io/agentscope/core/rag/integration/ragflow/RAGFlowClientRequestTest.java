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
package io.agentscope.core.rag.integration.ragflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RAGFlowClient 请求生成单元测试。
 *
 * <p>验证生成的请求体符合 RAGFlow API 规范：POST /api/v1/retrieval。
 *
 * <p>API 参考：https://ragflow.io/docs/dev/http_api_reference
 */
class RAGFlowClientRequestTest {

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private MockResponse createSuccessResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\": 0, \"data\": {\"chunks\": [], \"total\": 0}}");
    }

    private Map<String, Object> parseAndPrintRequest(RecordedRequest request) throws Exception {
        String body = request.getBody().readUtf8();

        System.out.println("\n" + "=".repeat(70));
        System.out.println("RAGFlow API Request Validation");
        System.out.println("=".repeat(70));
        System.out.println("Method: " + request.getMethod());
        System.out.println("Path: " + request.getPath());
        System.out.println("-".repeat(70));
        System.out.println("Headers:");
        request.getHeaders()
                .names()
                .forEach(name -> System.out.println("  " + name + ": " + request.getHeader(name)));
        System.out.println("-".repeat(70));

        Map<String, Object> parsed =
                JsonUtils.getJsonCodec()
                        .fromJson(body, new TypeReference<Map<String, Object>>() {});
        String prettyJson = JsonUtils.getJsonCodec().toPrettyJson(parsed);
        System.out.println("Request Body:");
        System.out.println(prettyJson);
        System.out.println("=".repeat(70) + "\n");

        return parsed;
    }

    @Test
    void shouldGenerateCompleteRequest() throws Exception {
        mockWebServer.enqueue(createSuccessResponse());

        // 构建完整元数据条件
        Map<String, Object> metadataCondition =
                Map.of(
                        "logic",
                        "and",
                        "conditions",
                        List.of(
                                Map.of(
                                        "name", "author",
                                        "comparison_operator", "=",
                                        "value", "Toby"),
                                Map.of(
                                        "name", "date",
                                        "comparison_operator", ">=",
                                        "value", "2024-01-01"),
                                Map.of(
                                        "name", "category",
                                        "comparison_operator", "contains",
                                        "value", "AI")));

        // 构建包含全部参数的完整 RAGFlowConfig
        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        // === 必填参数 ===
                        .apiKey("ragflow-test-api-key-12345")
                        .baseUrl(mockWebServer.url("/").toString())

                        // === 数据集/文档 ID ===
                        .addDatasetId("b2a62730759d11ef987d0242ac120004")
                        .addDatasetId("c3b73841870e22fg0ae3c242bd240115")
                        .addDocumentId("77df9ef4759a11ef8bdd0242ac120004")
                        .addDocumentId("88ef0ab5869b22fg9cee1242ac130005")

                        // === 检索参数 ===
                        .topK(1024) // 默认值：1024
                        .similarityThreshold(0.3) // 默认值：0.2
                        .vectorSimilarityWeight(0.5) // 默认值：0.3

                        // === 分页 ===
                        .page(1) // 默认值：1
                        .pageSize(30) // 默认值：30

                        // === 高级功能 ===
                        .useKg(true) // 知识图谱，默认 false
                        .tocEnhance(true) // 目录增强，默认 false
                        .rerankId("BAAI/bge-reranker-v2-m3@BAAI") // 重排模型 ID
                        .keyword(true) // 关键词匹配，默认 false
                        .highlight(true) // 结果高亮，默认 false

                        // === 跨语言检索 ===
                        .addCrossLanguage("en")
                        .addCrossLanguage("zh")
                        .addCrossLanguage("ja")

                        // === 元数据过滤 ===
                        .metadataCondition(metadataCondition)

                        // === HTTP 设置 ===
                        .timeout(Duration.ofSeconds(60))
                        .maxRetries(5)
                        .addCustomHeader("X-Request-ID", "test-request-123")
                        .addCustomHeader("X-Trace-ID", "trace-456")
                        .build();

        // 执行请求
        RAGFlowClient client = new RAGFlowClient(config);
        client.retrieve("What is the advantage of RAGFlow for enterprise RAG?", null, null, null)
                .block();

        // 捕获并打印请求
        RecordedRequest request = mockWebServer.takeRequest();
        Map<String, Object> body = parseAndPrintRequest(request);

        // === 验证 HTTP 请求格式 ===
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().contains("/api/v1/retrieval"));
        assertEquals("Bearer ragflow-test-api-key-12345", request.getHeader("Authorization"));
        assertTrue(request.getHeader("Content-Type").contains("application/json"));
        assertEquals("test-request-123", request.getHeader("X-Request-ID"));
        assertEquals("trace-456", request.getHeader("X-Trace-ID"));

        // === 验证必填参数 ===
        assertEquals("What is the advantage of RAGFlow for enterprise RAG?", body.get("question"));

        @SuppressWarnings("unchecked")
        List<String> datasetIds = (List<String>) body.get("dataset_ids");
        assertNotNull(datasetIds);
        assertEquals(2, datasetIds.size());
        assertTrue(datasetIds.contains("b2a62730759d11ef987d0242ac120004"));
        assertTrue(datasetIds.contains("c3b73841870e22fg0ae3c242bd240115"));

        @SuppressWarnings("unchecked")
        List<String> documentIds = (List<String>) body.get("document_ids");
        assertNotNull(documentIds);
        assertEquals(2, documentIds.size());
        assertTrue(documentIds.contains("77df9ef4759a11ef8bdd0242ac120004"));

        // === 验证检索参数 ===
        assertEquals(1024, body.get("top_k"));
        assertEquals(0.3, body.get("similarity_threshold"));
        assertEquals(0.5, body.get("vector_similarity_weight"));

        // === 验证分页 ===
        assertEquals(1, body.get("page"));
        assertEquals(30, body.get("page_size"));

        // === 验证高级功能 ===
        assertEquals(true, body.get("use_kg"));
        assertEquals(true, body.get("toc_enhance"));
        assertEquals("BAAI/bge-reranker-v2-m3@BAAI", body.get("rerank_id"));
        assertEquals(true, body.get("keyword"));
        assertEquals(true, body.get("highlight"));

        // === 验证跨语言配置 ===
        @SuppressWarnings("unchecked")
        List<String> languages = (List<String>) body.get("cross_languages");
        assertNotNull(languages);
        assertEquals(3, languages.size());
        assertTrue(languages.contains("en"));
        assertTrue(languages.contains("zh"));
        assertTrue(languages.contains("ja"));

        // === 验证元数据条件 ===
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) body.get("metadata_condition");
        assertNotNull(metadata);
        assertEquals("and", metadata.get("logic"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conditions =
                (List<Map<String, Object>>) metadata.get("conditions");
        assertNotNull(conditions);
        assertEquals(3, conditions.size());

        // 验证首个条件结构
        Map<String, Object> firstCondition = conditions.get(0);
        assertEquals("author", firstCondition.get("name"));
        assertEquals("=", firstCondition.get("comparison_operator"));
        assertEquals("Toby", firstCondition.get("value"));

        System.out.println("✅ All request parameters validated successfully!");
    }
}
