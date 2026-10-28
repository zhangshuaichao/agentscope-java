/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * StructuredOutputCapableAgent 动态 JSON Schema 测试，使用延迟强制模式。
 */
public class StructuredOutputDynamicDefineTest {

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
    }

    @Test
    void testDynamicComplexNestedStructure() {
        Memory memory = new InMemoryMemory();
        MockModel mockModel = getMockModel();

        // 创建 TOOL_BASED 策略智能体
        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        // 执行结构化输出调用
        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();
        String json =
                """
                {
                  "type": "object",
                  "properties": {
                    "location": {
                      "type": "string"
                    },
                    "temperature": {
                      "type": "string"
                    },
                    "condition": {
                      "type": "string"
                    }
                  },
                  "required": ["location", "temperature", "condition"],
                  "additionalProperties": false
                }
                """;
        // 调用智能体，从响应消息提取结构化数据
        Msg responseMsg =
                agent.call(inputMsg, JsonUtils.getJsonCodec().fromJson(json, JsonNode.class))
                        .block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getMetadata());

        // 从元数据提取结构化数据
        Map<String, Object> result = responseMsg.getStructuredData(false);

        // 验证
        assertNotNull(result);
        assertEquals("San Francisco", result.get("location"));
        assertEquals("72°F", result.get("temperature"));
        assertEquals("Sunny", result.get("condition"));
    }

    @Test
    @DisplayName("Stream execution events with JSON schema structured support")
    void testDynamicComplexNestedStructureStreamingMode() {
        Memory memory = new InMemoryMemory();
        MockModel mockModel = getMockModel();

        // 创建 TOOL_BASED 策略智能体
        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        // 执行结构化输出调用
        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();
        String json =
                """
                {
                  "type": "object",
                  "properties": {
                    "location": {
                      "type": "string"
                    },
                    "temperature": {
                      "type": "string"
                    },
                    "condition": {
                      "type": "string"
                    }
                  },
                  "required": ["location", "temperature", "condition"],
                  "additionalProperties": false
                }
                """;
        // 流式调用智能体并从响应提取结构化数据
        Flux<Event> eventFlux =
                agent.stream(
                        inputMsg,
                        StreamOptions.defaults(),
                        JsonUtils.getJsonCodec().fromJson(json, JsonNode.class));

        StepVerifier.create(eventFlux)
                .thenConsumeWhile(
                        event -> !(event.isLast() && event.getType() == EventType.AGENT_RESULT))
                .assertNext(
                        event -> {
                            Msg responseMsg = event.getMessage();
                            assertNotNull(responseMsg);
                            assertNotNull(responseMsg.getMetadata());

                            // 从元数据提取结构化数据
                            Map<String, Object> result = responseMsg.getStructuredData(false);

                            // 验证
                            assertNotNull(result);
                            assertEquals("San Francisco", result.get("location"));
                            assertEquals("72°F", result.get("temperature"));
                            assertEquals("Sunny", result.get("condition"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should resolve $ref when $defs is hoisted from properties.response to root")
    void testDefsHoistedToRootForRefResolution() {
        Memory memory = new InMemoryMemory();

        // 含 $defs 与 $ref 的模式嵌套到 properties.response 后，
        // $ref "#/$defs/Material" 必须从文档根解析，因此覆盖
        // StructuredOutputCapableAgent.getParameters() 的定义提升逻辑。
        String schemaJson =
                """
                {
                  "type": "object",
                  "properties": {
                    "items": {
                      "type": "array",
                      "items": { "$ref": "#/$defs/Material" }
                    }
                  },
                  "$defs": {
                    "Material": {
                      "type": "object",
                      "properties": {
                        "name": { "type": "string" },
                        "quantity": { "type": "integer" }
                      },
                      "required": ["name", "quantity"]
                    }
                  },
                  "required": ["items"]
                }
                """;

        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "items",
                                List.of(
                                        Map.of("name", "Wood", "quantity", 10),
                                        Map.of("name", "Stone", "quantity", 5))));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);
                            if (!hasToolResults) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_ref_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_ref")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_ref_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("material-agent")
                        .sysPrompt("You are a material assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("List materials").build())
                        .build();

        Msg responseMsg =
                agent.call(inputMsg, JsonUtils.getJsonCodec().fromJson(schemaJson, JsonNode.class))
                        .block();

        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getMetadata());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = responseMsg.getStructuredData(false);
        assertNotNull(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertNotNull(items);
        assertEquals(2, items.size());
        assertEquals("Wood", items.get(0).get("name"));
        assertEquals(10, items.get(0).get("quantity"));
        assertEquals("Stone", items.get(1).get("name"));
        assertEquals(5, items.get(1).get("quantity"));
    }

    private static MockModel getMockModel() {
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location",
                                "San Francisco",
                                "temperature",
                                "72°F",
                                "condition",
                                "Sunny"));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            // 检查 TOOL 角色的工具执行结果消息
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);

                            if (!hasToolResults) {
                                // 首次返回 generate_response 工具调用
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // 工具执行后第二次返回简单文本
                                // 无更多工具调用，表示结束
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Response generated")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                        });
        return mockModel;
    }
}
