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
package io.agentscope.core.rag.integration.bailian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BailianKnowledge 端到端测试。
 *
 * <p>测试需要设置以下环境变量：
 * <ul>
 *   <li>ALIBABA_CLOUD_ACCESS_KEY_ID - 阿里云访问密钥 ID
 *   <li>ALIBABA_CLOUD_ACCESS_KEY_SECRET - 阿里云访问密钥 secret
 *   <li>BAILIAN_WORKSPACE_ID - 百炼工作区 ID
 *   <li>BAILIAN_INDEX_ID - 测试使用的知识库索引 ID
 * </ul>
 *
 * <p>未设置这些环境变量时跳过测试。
 */
class BailianKnowledgeE2ETest {

    private static final Logger log = LoggerFactory.getLogger(BailianKnowledgeE2ETest.class);

    private static String accessKeyId;
    private static String accessKeySecret;
    private static String workspaceId;
    private static String indexId;

    @BeforeAll
    static void setUp() {
        accessKeyId = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID");
        accessKeySecret = System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET");
        workspaceId = System.getenv("BAILIAN_WORKSPACE_ID");
        indexId = System.getenv("BAILIAN_INDEX_ID");

        if (accessKeyId == null
                || accessKeySecret == null
                || workspaceId == null
                || indexId == null) {
            log.info("Skipping BailianKnowledgeE2ETest: Required environment variables not set");
            log.info(
                    "To run this test, please set: ALIBABA_CLOUD_ACCESS_KEY_ID,"
                            + " ALIBABA_CLOUD_ACCESS_KEY_SECRET, BAILIAN_WORKSPACE_ID,"
                            + " BAILIAN_INDEX_ID");
        }
    }

    @Test
    void testRetrieveFromBailianKnowledgeBase() {
        assumeTrue(
                accessKeyId != null
                        && accessKeySecret != null
                        && workspaceId != null
                        && indexId != null,
                "Environment variables for Bailian not set");

        // 创建配置
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .workspaceId(workspaceId)
                        .indexId(indexId)
                        .build();

        // 创建知识库
        BailianKnowledge knowledge = BailianKnowledge.builder().config(config).build();

        // 检索文档
        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.3).build();

        List<Document> results = knowledge.retrieve("测试查询", retrieveConfig).block();

        // 验证结果
        assertNotNull(results);
        log.info("Retrieved {} documents", results.size());

        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            log.info(
                    "Document {}: score={}, content={}",
                    i + 1,
                    doc.getScore(),
                    doc.getMetadata()
                            .getContentText()
                            .substring(
                                    0, Math.min(100, doc.getMetadata().getContentText().length())));

            assertNotNull(doc.getScore());
            assertTrue(doc.getScore() >= 0.3);
            assertNotNull(doc.getMetadata());
            assertNotNull(doc.getMetadata().getContentText());
            assertFalse(doc.getMetadata().getContentText().isEmpty());
        }
    }

    @Test
    void testBailianKnowledgeWithKnowledgeRetrievalTools() {
        assumeTrue(
                accessKeyId != null
                        && accessKeySecret != null
                        && workspaceId != null
                        && indexId != null,
                "Environment variables for Bailian not set");

        // 创建知识库
        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .workspaceId(workspaceId)
                        .indexId(indexId)
                        .build();

        BailianKnowledge knowledge = BailianKnowledge.builder().config(config).build();

        // 创建知识检索工具
        KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);

        // 注册到工具集
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);

        // 验证工具已注册
        assertNotNull(toolkit);
        assertTrue(toolkit.getToolNames().size() > 0);

        // 直接调用工具，因此 agent/ctx 传 null
        ToolResultBlock result = tools.retrieveKnowledge("测试查询", 3, null, null).block();
        String resultText = text(result);

        assertNotNull(result);
        assertEquals(ToolResultState.SUCCESS, result.getState());
        log.info("Tool result: {}", resultText);

        // 结果应包含检索到的文档信息
        assertTrue(
                resultText.contains("document")
                        || resultText.contains("Document")
                        || resultText.contains("文档"));
    }

    private static String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
    }

    @Test
    void testEmptyQueryReturnsEmptyResults() {
        assumeTrue(
                accessKeyId != null
                        && accessKeySecret != null
                        && workspaceId != null
                        && indexId != null,
                "Environment variables for Bailian not set");

        BailianConfig config =
                BailianConfig.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .workspaceId(workspaceId)
                        .indexId(indexId)
                        .build();

        BailianKnowledge knowledge = BailianKnowledge.builder().config(config).build();

        RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        List<Document> results = knowledge.retrieve("", retrieveConfig).block();

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }
}
