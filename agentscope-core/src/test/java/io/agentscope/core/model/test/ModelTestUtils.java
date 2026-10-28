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
package io.agentscope.core.model.test;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模型测试工具方法。
 *
 * <p>提供创建模拟响应、工具 schema 和验证模型行为的辅助方法。
 */
public class ModelTestUtils {

    /**
     * 创建简单文本响应。
     */
    public static ChatResponse createTextResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 20, 30))
                .build();
    }

    /**
     * 创建工具调用响应。
     */
    public static ChatResponse createToolCallResponse(
            String toolName, String toolCallId, Map<String, Object> arguments) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(toolCallId != null ? toolCallId : generateId())
                                        .input(arguments != null ? arguments : new HashMap<>())
                                        .build()))
                .usage(new ChatUsage(15, 25, 40))
                .build();
    }

    /**
     * 创建简单工具 schema。
     */
    public static ToolSchema createSimpleToolSchema(String name, String description) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", new HashMap<String, Object>());

        return ToolSchema.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();
    }

    /**
     * 创建带参数的工具 schema。
     */
    public static ToolSchema createToolSchemaWithParams(
            String name, String description, Map<String, Object> properties) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of());

        return ToolSchema.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();
    }

    /**
     * 从内容块提取文本。
     */
    public static String extractText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                sb.append(((TextBlock) block).getText());
            }
        }

        return sb.toString();
    }

    /**
     * 检查响应是否包含文本。
     */
    public static boolean hasText(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return false;
        }

        return response.getContent().stream().anyMatch(block -> block instanceof TextBlock);
    }

    /**
     * 检查响应是否包含工具调用。
     */
    public static boolean hasToolCall(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return false;
        }

        return response.getContent().stream().anyMatch(block -> block instanceof ToolUseBlock);
    }

    /**
     * 生成唯一 ID。
     */
    public static String generateId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 创建测试用模拟 API 密钥。
     */
    public static String createMockApiKey() {
        return "test_api_key_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 验证聊天响应结构。
     */
    public static boolean isValidChatResponse(ChatResponse response) {
        return response != null
                && response.getContent() != null
                && !response.getContent().isEmpty()
                && response.getUsage() != null;
    }

    private ModelTestUtils() {
        // 工具类
    }
}
