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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AnthropicMultiAgentFormatter 基准结果测试。 */
class AnthropicMultiAgentFormatterGroundTruthTest {

    private AnthropicMultiAgentFormatter formatter;
    private JsonCodec jsonCodec;
    private String imageUrl;
    private List<Msg> msgsSystem;
    private List<Msg> msgsConversation;
    private List<Msg> msgsTools;
    private List<Msg> msgsConversation2;
    private List<Msg> msgsTools2;

    @BeforeEach
    void setUp() {
        formatter = new AnthropicMultiAgentFormatter();
        jsonCodec = JsonUtils.getJsonCodec();
        imageUrl = "https://www.example.com/image.png";

        // 系统消息
        msgsSystem =
                List.of(
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("You're a helpful assistant.")
                                                        .build()))
                                .build());

        // 对话消息
        msgsConversation =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of France?")
                                                        .build(),
                                                ImageBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(imageUrl)
                                                                        .build())
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("The capital of France is Paris.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of Japan?")
                                                        .build()))
                                .build());

        // 工具消息
        msgsTools =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(Map.of("country", "Japan"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.TOOL)
                                .content(
                                        List.of(
                                                ToolResultBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .output(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " Japan is"
                                                                                    + " Tokyo.")
                                                                        .build())
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("The capital of Japan is Tokyo.")
                                                        .build()))
                                .build());

        // 第二段对话
        msgsConversation2 =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of South Korea?")
                                                        .build()))
                                .build());

        // 第二组工具消息
        msgsTools2 =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("2")
                                                        .name("get_capital")
                                                        .input(Map.of("country", "South Korea"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.TOOL)
                                .content(
                                        List.of(
                                                ToolResultBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .output(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " South Korea"
                                                                                    + " is Seoul.")
                                                                        .build())
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "The capital of South Korea is"
                                                                        + " Seoul.")
                                                        .build()))
                                .build());
    }

    @Test
    void testMultiAgentFormatterFullHistory() throws Exception {
        // 系统、对话、工具、对话、工具
        List<Msg> allMsgs = new ArrayList<>();
        allMsgs.addAll(msgsSystem);
        allMsgs.addAll(msgsConversation);
        allMsgs.addAll(msgsTools);
        allMsgs.addAll(msgsConversation2);
        allMsgs.addAll(msgsTools2);

        List<MessageParam> result = formatter.format(allMsgs);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = jsonCodec.fromJson(resultJson, JsonNode.class);

        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "You're a helpful assistant.",
                        "type": "text"
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "# Conversation History\\nThe content between <history></history> tags contains your conversation history\\n<history>\\nuser: What is the capital of France?",
                        "type": "text"
                      },
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://www.example.com/image.png"
                        }
                      },
                      {
                        "text": "assistant: The capital of France is Paris.\\nuser: What is the capital of Japan?\\n</history>",
                        "type": "text"
                      }
                    ]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "id": "1",
                        "type": "tool_use",
                        "name": "get_capital",
                        "input": {
                          "country": "Japan"
                        }
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "1",
                        "content": [
                          {
                            "type": "text",
                            "text": "The capital of Japan is Tokyo."
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "<history>\\nassistant: The capital of Japan is Tokyo.\\nuser: What is the capital of South Korea?\\n</history>",
                        "type": "text"
                      }
                    ]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "id": "2",
                        "type": "tool_use",
                        "name": "get_capital",
                        "input": {
                          "country": "South Korea"
                        }
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "1",
                        "content": [
                          {
                            "type": "text",
                            "text": "The capital of South Korea is Seoul."
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "<history>\\nassistant: The capital of South Korea is Seoul.\\n</history>",
                        "type": "text"
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = jsonCodec.fromJson(groundTruthJson, JsonNode.class);
        assertEquals(groundTruthNode, resultNode, "Formatted output should match ground truth");
    }

    @Test
    void testMultiAgentFormatterWithoutSystemMessage() throws Exception {
        // 对话及工具，不含最后一条助手文本
        List<Msg> allMsgs = new ArrayList<>();
        allMsgs.addAll(msgsConversation);
        // 只添加 msgsTools 的前两条消息（tool_use 与 tool_result）
        // 排除最后一条助手文本消息
        allMsgs.add(msgsTools.get(0)); // 含 tool_use 的助手消息
        allMsgs.add(msgsTools.get(1)); // 含 tool_result 的系统消息

        List<MessageParam> result = formatter.format(allMsgs);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = jsonCodec.fromJson(resultJson, JsonNode.class);

        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "# Conversation History\\nThe content between <history></history> tags contains your conversation history\\n<history>\\nuser: What is the capital of France?",
                        "type": "text"
                      },
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://www.example.com/image.png"
                        }
                      },
                      {
                        "text": "assistant: The capital of France is Paris.\\nuser: What is the capital of Japan?\\n</history>",
                        "type": "text"
                      }
                    ]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "id": "1",
                        "type": "tool_use",
                        "name": "get_capital",
                        "input": {
                          "country": "Japan"
                        }
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "1",
                        "content": [
                          {
                            "type": "text",
                            "text": "The capital of Japan is Tokyo."
                          }
                        ]
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = jsonCodec.fromJson(groundTruthJson, JsonNode.class);
        assertEquals(groundTruthNode, resultNode);
    }

    @Test
    void testMultiAgentFormatterOnlySystemMessage() throws Exception {
        List<MessageParam> result = formatter.format(msgsSystem);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = jsonCodec.fromJson(resultJson, JsonNode.class);

        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "You're a helpful assistant.",
                        "type": "text"
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = jsonCodec.fromJson(groundTruthJson, JsonNode.class);
        assertEquals(groundTruthNode, resultNode);
    }

    @Test
    void testMultiAgentFormatterOnlyConversation() throws Exception {
        List<MessageParam> result = formatter.format(msgsConversation);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = jsonCodec.fromJson(resultJson, JsonNode.class);

        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "# Conversation History\\nThe content between <history></history> tags contains your conversation history\\n<history>\\nuser: What is the capital of France?",
                        "type": "text"
                      },
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://www.example.com/image.png"
                        }
                      },
                      {
                        "text": "assistant: The capital of France is Paris.\\nuser: What is the capital of Japan?\\n</history>",
                        "type": "text"
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = jsonCodec.fromJson(groundTruthJson, JsonNode.class);
        assertEquals(groundTruthNode, resultNode);
    }
}
