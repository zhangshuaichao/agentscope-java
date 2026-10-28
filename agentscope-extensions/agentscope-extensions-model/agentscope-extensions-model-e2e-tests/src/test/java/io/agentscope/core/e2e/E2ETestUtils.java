/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.e2e;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import java.time.Duration;
import java.util.List;

/** ReAct 流程及智能体交互 E2E 测试工具类，提供创建智能体、模型、工具和常见场景的辅助方法。 */
public class E2ETestUtils {

    private E2ETestUtils() {
        // 工具类
    }

    /**
     * 使用真实 DashScope 模型创建 E2E 测试 ReActAgent。
     * @param agentName 智能体名称
     * @param toolkit 使用的工具包
     * @return 已配置的 ReActAgent
     */
    public static ReActAgent createReActAgentWithRealModel(String agentName, Toolkit toolkit) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "DASHSCOPE_API_KEY environment variable not set for E2E tests");
        }

        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(true)
                        .build();

        Memory memory = new InMemoryMemory();

        return ReActAgent.builder()
                .name(agentName)
                .sysPrompt("A helpful AI assistant that can use tools to solve problems")
                .model(model)
                .toolkit(toolkit)
                .build();
    }

    /**
     * 创建包含数学和字符串工具的简单测试工具包。
     * @return 已配置工具包
     */
    public static Toolkit createTestToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MathTools());
        toolkit.registerTool(new StringTools());
        return toolkit;
    }

    /**
     * 带超时等待智能体响应。
     * @param agent 智能体
     * @param input 输入消息
     * @param timeout 超时时长
     * @return 响应消息列表
     */
    public static List<Msg> waitForResponse(ReActAgent agent, Msg input, Duration timeout) {
        return List.of(agent.call(input).block(timeout));
    }

    /**
     * 检查响应列表是否包含预期文本，忽略大小写。
     * @param responses 响应消息列表
     * @param expectedText 预期文本子串
     * @return 任一响应包含该文本时为 true
     */
    public static boolean responsesContain(List<Msg> responses, String expectedText) {
        if (responses == null || responses.isEmpty()) {
            return false;
        }

        return responses.stream()
                .anyMatch(
                        msg -> {
                            String text = TestUtils.extractTextContent(msg);
                            return text != null
                                    && text.toLowerCase().contains(expectedText.toLowerCase());
                        });
    }

    /**
     * 检查任一响应是否包含有效内容（大于 5 个字符）。
     * @param responses 响应消息列表
     * @return 至少一个响应包含有效内容时为 true
     */
    public static boolean hasMeaningfulContent(List<Msg> responses) {
        if (responses == null || responses.isEmpty()) {
            return false;
        }

        return responses.stream()
                .anyMatch(
                        msg -> {
                            String text = TestUtils.extractTextContent(msg);
                            return text != null && text.trim().length() > 5;
                        });
    }

    // 测试工具实现

    /** 测试用数学工具。 */
    public static class MathTools {
        @Tool(description = "Add two numbers together")
        public int add(
                @ToolParam(name = "a", description = "First number", required = true) int a,
                @ToolParam(name = "b", description = "Second number", required = true) int b) {
            return a + b;
        }

        @Tool(description = "Multiply two numbers")
        public int multiply(
                @ToolParam(name = "a", description = "First number", required = true) int a,
                @ToolParam(name = "b", description = "Second number", required = true) int b) {
            return a * b;
        }

        @Tool(description = "Calculate factorial of a number")
        public long factorial(
                @ToolParam(name = "n", description = "Number", required = true) int n) {
            if (n < 0) {
                throw new IllegalArgumentException("Factorial of negative number is undefined");
            }
            long result = 1;
            for (int i = 2; i <= n; i++) {
                result *= i;
            }
            return result;
        }
    }

    /** 测试用字符串工具。 */
    public static class StringTools {
        @Tool(description = "Concatenate two strings")
        public String concat(
                @ToolParam(name = "s1", description = "First string", required = true) String s1,
                @ToolParam(name = "s2", description = "Second string", required = true) String s2) {
            return s1 + s2;
        }

        @Tool(description = "Convert string to uppercase")
        public String toUpperCase(
                @ToolParam(name = "s", description = "Input string", required = true) String s) {
            return s.toUpperCase();
        }

        @Tool(description = "Get length of string")
        public int length(
                @ToolParam(name = "s", description = "Input string", required = true) String s) {
            return s.length();
        }
    }
}
