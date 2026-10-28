/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.chat.completions.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.chat.completions.model.ToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 ChatMessage DTO 转换为框架内部 Msg 对象的服务。
 *
 * <p>将 HTTP 请求 DTO（角色为 String）转换为框架内部消息对象（角色为 MsgRole 枚举）。支持 user、assistant、system、tool 角色。
 *
 * <p>还支持：
 *
 * <ul>
 *   <li>assistant 消息中的工具调用（tool_calls 字段）
 *   <li>tool 消息中的工具结果（tool_call_id 字段）
 * </ul>
 */
public class ChatMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 将 {@link ChatMessage} DTO 列表转换为框架内部 {@link Msg} 对象，支持包含工具调用的完整对话历史。
     *
     * <p>将 HTTP 请求 DTO（角色为 String 的 ChatMessage）转换为框架内部对象（角色为 MsgRole 枚举的 Msg）。支持 user、assistant、system、tool 角色。
     *
     * @param chatMessages HTTP 请求中待转换的聊天消息
     * @return 转换后的 {@link Msg} 对象列表；输入为 null 或空时返回空列表
     * @throws IllegalArgumentException 任一消息包含不支持的角色时抛出
     */
    public List<Msg> convertMessages(List<ChatMessage> chatMessages) {
        if (chatMessages == null || chatMessages.isEmpty()) {
            return List.of();
        }

        return chatMessages.stream()
                .filter(Objects::nonNull)
                .map(this::convertMessage)
                .collect(Collectors.toList());
    }

    /**
     * 将单条 ChatMessage 转换为 Msg。
     *
     * @param chatMsg 待转换的聊天消息
     * @return 转换后的 Msg 对象
     * @throws IllegalArgumentException 遇到不支持的角色时抛出
     */
    private Msg convertMessage(ChatMessage chatMsg) {
        String roleStr = chatMsg.getRole();
        if (roleStr == null || roleStr.isBlank()) {
            log.warn("Message with null/empty role, defaulting to USER");
            roleStr = "user";
        }

        // 将字符串角色转换为枚举
        MsgRole role = convertRole(roleStr);

        // 对工具结果消息进行专门处理
        if (role == MsgRole.TOOL) {
            return convertToolResultMessage(chatMsg);
        }

        // 处理含有工具调用的 assistant 消息
        if (role == MsgRole.ASSISTANT
                && chatMsg.getToolCalls() != null
                && !chatMsg.getToolCalls().isEmpty()) {
            return convertAssistantWithToolCalls(chatMsg);
        }

        // 常规消息转换
        String content = chatMsg.getContent();
        if (content == null) {
            log.warn("Message with null content, using empty string");
            content = "";
        }

        return Msg.builder().role(role).content(TextBlock.builder().text(content).build()).build();
    }

    /**
     * 将含工具调用的 assistant 消息转换为 Msg。
     *
     * @param chatMsg 包含工具调用的 assistant 消息
     * @return 包含 ToolUseBlock 的 Msg 对象
     */
    private Msg convertAssistantWithToolCalls(ChatMessage chatMsg) {
        List<ContentBlock> contentBlocks = new ArrayList<>();

        // 存在文本内容时将其加入
        if (chatMsg.getContent() != null && !chatMsg.getContent().isEmpty()) {
            contentBlocks.add(TextBlock.builder().text(chatMsg.getContent()).build());
        }

        // 将工具调用转换为 ToolUseBlock
        for (ToolCall toolCall : chatMsg.getToolCalls()) {
            Map<String, Object> input = parseArguments(toolCall.getFunction().getArguments());
            contentBlocks.add(
                    ToolUseBlock.builder()
                            .id(toolCall.getId())
                            .name(toolCall.getFunction().getName())
                            .input(input)
                            .build());
        }

        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(contentBlocks.toArray(new ContentBlock[0]))
                .build();
    }

    /**
     * 将工具结果消息转换为 Msg。
     *
     * @param chatMsg 工具结果消息
     * @return 包含 ToolResultBlock 的 Msg 对象
     */
    private Msg convertToolResultMessage(ChatMessage chatMsg) {
        String toolCallId = chatMsg.getToolCallId();
        String name = chatMsg.getName();
        String content = chatMsg.getContent() != null ? chatMsg.getContent() : "";

        // ToolResultBlock.output 的类型为 List<ContentBlock>，因此用 TextBlock 包装文本
        ToolResultBlock resultBlock =
                new ToolResultBlock(toolCallId, name, TextBlock.builder().text(content).build());

        return Msg.builder().role(MsgRole.TOOL).content(resultBlock).build();
    }

    /**
     * 将 JSON 参数字符串解析为 Map。
     *
     * @param arguments 参数的 JSON 字符串
     * @return 解析得到的 Map；解析失败时返回空映射
     */
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool arguments: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 将字符串角色转换为 MsgRole 枚举。
     *
     * @param roleStr 角色字符串（不区分大小写）
     * @return 对应的 MsgRole 枚举值
     * @throws IllegalArgumentException 角色不受支持时抛出
     */
    private MsgRole convertRole(String roleStr) {
        return switch (roleStr.toLowerCase()) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system" -> MsgRole.SYSTEM;
            case "tool" -> MsgRole.TOOL;
            default -> {
                log.error(
                        "Unknown message role: '{}'. Supported roles: user, assistant, system,"
                                + " tool",
                        roleStr);
                throw new IllegalArgumentException(
                        String.format(
                                "Unknown message role: '%s'. Supported roles: user, assistant,"
                                        + " system, tool",
                                roleStr));
            }
        };
    }
}
