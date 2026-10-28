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
package io.agentscope.core.rag.e2e;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 智能体模块测试辅助方法。
 * 提供创建测试数据及公共操作的辅助方法。
 */
public class TestUtils {

    /**
     * 创建含文本的简单用户消息。
     */
    public static Msg createUserMessage(String name, String text) {
        return Msg.builder()
                .name(name)
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /**
     * 创建含文本的简单助手消息。
     */
    public static Msg createAssistantMessage(String name, String text) {
        return Msg.builder()
                .name(name)
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /**
     * 创建思考消息。
     */
    public static Msg createThinkingMessage(String name, String thinking) {
        return Msg.builder()
                .name(name)
                .role(MsgRole.ASSISTANT)
                .content(ThinkingBlock.builder().thinking(thinking).build())
                .build();
    }

    /**
     * 创建工具使用消息。
     */
    public static Msg createToolUseMessage(
            String name, String toolName, String toolCallId, Map<String, Object> arguments) {
        return Msg.builder()
                .name(name)
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .name(toolName)
                                .id(toolCallId)
                                .input(arguments != null ? arguments : new HashMap<>())
                                .build())
                .build();
    }

    /**
     * 创建工具结果消息。
     */
    public static Msg createToolResultMessage(String toolCallId, String result) {
        return Msg.builder()
                .name("tool")
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id(toolCallId)
                                .output(TextBlock.builder().text(result).build())
                                .build())
                .build();
    }

    /**
     * 从消息中提取文本内容。
     */
    public static String extractTextContent(Msg msg) {
        if (msg == null) {
            return null;
        }

        return msg.getContent().stream()
                .map(
                        block -> {
                            if (block instanceof TextBlock) {
                                return ((TextBlock) block).getText();
                            } else if (block instanceof ThinkingBlock) {
                                return ((ThinkingBlock) block).getThinking();
                            }
                            return "";
                        })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 检查消息是否为工具使用消息。
     */
    public static boolean isToolUseMessage(Msg msg) {
        return msg != null && msg.hasContentBlocks(ToolUseBlock.class);
    }

    /**
     * 检查消息是否为文本消息。
     */
    public static boolean isTextMessage(Msg msg) {
        return msg != null && msg.hasContentBlocks(TextBlock.class);
    }

    /**
     * 检查消息是否为思考消息。
     */
    public static boolean isThinkingMessage(Msg msg) {
        return msg != null && msg.hasContentBlocks(ThinkingBlock.class);
    }

    /**
     * 从工具使用消息提取工具名称。
     */
    public static String extractToolName(Msg msg) {
        if (!isToolUseMessage(msg)) {
            return null;
        }

        ToolUseBlock toolUse = msg.getFirstContentBlock(ToolUseBlock.class);
        return toolUse.getName();
    }

    /**
     * 从工具使用消息提取工具调用 ID。
     */
    public static String extractToolCallId(Msg msg) {
        if (!isToolUseMessage(msg)) {
            return null;
        }

        ToolUseBlock toolUse = msg.getFirstContentBlock(ToolUseBlock.class);
        return toolUse.getId();
    }

    /**
     * 创建工具调用的简单参数映射。
     */
    public static Map<String, Object> createToolArguments(String... keyValuePairs) {
        Map<String, Object> arguments = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (i + 1 < keyValuePairs.length) {
                arguments.put(keyValuePairs[i], keyValuePairs[i + 1]);
            }
        }
        return arguments;
    }

    /**
     * 休眠指定时长，供异步操作测试使用。
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private TestUtils() {
        // 工具类，禁止实例化
    }
}
