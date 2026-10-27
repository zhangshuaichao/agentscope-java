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
package io.agentscope.core.util;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 消息处理与提取辅助方法。
 *
 * <p>提供处理消息列表及从会话历史提取信息的常用操作。
 * @hidden
 */
public final class MessageUtils {

    private MessageUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * 从消息列表最近一条助手消息提取工具调用。
     *
     * <p>从列表末尾扫描，找到名称匹配指定智能体的最近助手消息，再从中提取工具调用块。
     *
     * @param messages 要搜索的消息列表
     * @param agentName 要匹配的智能体名称（必须与消息发送者名称一致）
     * @return 最后匹配助手消息的工具调用块列表；未找到时为空列表
     */
    public static List<ToolUseBlock> extractRecentToolCalls(List<Msg> messages, String agentName) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT && Objects.equals(msg.getName(), agentName)) {
                List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
                if (!toolCalls.isEmpty()) {
                    return toolCalls;
                }
                break;
            }
        }

        return List.of();
    }

    /**
     * 仅从最近一条助手消息提取待处理工具调用。
     *
     * @param messages 会话消息
     * @param agentName 要匹配的智能体名称
     * @return 尚无对应结果的工具调用
     */
    public static List<ToolUseBlock> extractPendingToolCalls(List<Msg> messages, String agentName) {
        List<ToolUseBlock> allToolCalls = extractRecentToolCalls(messages, agentName);
        if (allToolCalls.isEmpty()) {
            return List.of();
        }

        // 【学习提示】“最近发起”与“仍待执行”是两个集合。先定位最近批次，再按会话中
        // 已存在的结果 ID 过滤，可让恢复调用只处理未完成项，而非重复执行整个批次。
        Set<String> pendingIds = pendingToolUseIds(messages);
        return allToolCalls.stream()
                .filter(toolUse -> pendingIds.contains(toolUse.getId()))
                .toList();
    }

    /**
     * 查找具有指定角色的最近消息。
     *
     * @param messages 要搜索的消息列表
     * @param role 要匹配的角色
     * @return 最后一条匹配消息；不存在时为 {@code null}
     */
    public static Msg lastMessageByRole(List<Msg> messages, MsgRole role) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg != null && msg.getRole() == role) {
                return msg;
            }
        }
        return null;
    }

    /**
     * 查找最近一条助手消息。
     *
     * @param messages 要搜索的消息列表
     * @return 最后一条助手消息；不存在时为 {@code null}
     */
    public static Msg lastAssistantMessage(List<Msg> messages) {
        return lastMessageByRole(messages, MsgRole.ASSISTANT);
    }

    /**
     * 判断指定名称智能体的最近助手消息是否因达到输出 token 上限而停止。
     *
     * @param messages 会话消息
     * @param agentName 要匹配的智能体名称
     * @return 该助手消息携带输出上限标记时为 {@code true}
     */
    public static boolean lastAssistantReachedOutputLimit(List<Msg> messages, String agentName) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT && Objects.equals(msg.getName(), agentName)) {
                return Boolean.TRUE.equals(
                        msg.getMetadata().get(MessageMetadataKeys.OUTPUT_LIMIT_REACHED));
            }
        }
        return false;
    }

    /**
     * 返回指定消息所包含的服务端工具结果 ID。
     *
     * <p>只包含提供方生成的终态结果。本地工具结果通常位于 TOOL 角色消息中，
     * 意外出现在助手消息中的本地结果不得使匹配的服务端工具调用被视为完成。
     * 运行中的提供方结果也排除，因为它只是中间部分结果，而非完成的工具调用。
     *
     * @param message 要检查的消息
     * @return 内嵌服务端工具结果 ID
     */
    public static Set<String> inlineServerToolResultIds(Msg message) {
        if (message == null) {
            return Set.of();
        }

        return message.getContentBlocks(ToolResultBlock.class).stream()
                .filter(ToolResultBlock::isServerTool)
                .filter(result -> result.getState() != ToolResultState.RUNNING)
                .map(ToolResultBlock::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 替换具有指定角色的最近消息。
     *
     * @param messages 要更新的可变消息列表
     * @param role 要匹配的角色
     * @param replacement 替换消息
     * @return 替换了消息时为 {@code true}
     */
    public static boolean replaceLastMessageByRole(
            List<Msg> messages, MsgRole role, Msg replacement) {
        if (messages == null || messages.isEmpty() || replacement == null) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg != null && msg.getRole() == role) {
                messages.set(i, replacement);
                return true;
            }
        }
        return false;
    }

    /**
     * 返回最后一条助手消息中、在整个会话中尚无匹配工具结果的本地工具调用 ID。
     * 服务端工具由提供方执行，不得进入本地执行或待处理工具恢复流程。
     *
     * @param messages 会话消息
     * @return 待处理工具调用 ID
     */
    public static Set<String> pendingToolUseIds(List<Msg> messages) {
        Msg lastAssistant = lastAssistantMessage(messages);
        if (lastAssistant == null || !lastAssistant.hasContentBlocks(ToolUseBlock.class)) {
            return Set.of();
        }

        Set<String> existingResultIds =
                messages.stream()
                        .filter(Objects::nonNull)
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .map(ToolResultBlock::getId)
                        .collect(Collectors.toSet());

        return lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                .filter(toolUse -> !toolUse.isServerTool())
                .map(ToolUseBlock::getId)
                .filter(id -> !existingResultIds.contains(id))
                .collect(Collectors.toSet());
    }

    /**
     * 判断消息是否包含任何工具调用块。
     *
     * @param message 要检查的消息
     * @return 至少存在一个工具调用时为 {@code true}
     */
    public static boolean hasToolCalls(Msg message) {
        return message != null && !message.getContentBlocks(ToolUseBlock.class).isEmpty();
    }

    /**
     * 在指定消息列表前添加系统消息，不修改原列表。
     *
     * @param messages 原消息列表
     * @param systemMessage 要前置的系统消息
     * @return 合并后的消息列表
     */
    public static List<Msg> prependSystemMessage(List<Msg> messages, Msg systemMessage) {
        if (systemMessage == null) {
            return messages != null ? messages : List.of();
        }
        List<Msg> result = new ArrayList<>();
        result.add(systemMessage);
        if (messages != null) {
            result.addAll(messages);
        }
        return result;
    }

    /**
     * 判断给定工具调用是否全部在会话中具有 DENIED 结果。
     *
     * @param messages 会话消息
     * @param toolCalls 要检查的工具调用
     * @return 全部工具调用均被拒绝时为 {@code true}
     */
    public static boolean allToolCallsDenied(List<Msg> messages, List<ToolUseBlock> toolCalls) {
        if (toolCalls == null) {
            return false;
        }
        Set<String> toolIds =
                toolCalls.stream().map(ToolUseBlock::getId).collect(Collectors.toSet());
        Map<String, ToolResultState> resultStates = new HashMap<>();
        for (Msg message : messages) {
            if (message == null) {
                continue;
            }
            for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                if (toolIds.contains(result.getId())) {
                    resultStates.put(result.getId(), result.getState());
                }
            }
        }
        return toolIds.size() == resultStates.size()
                && resultStates.values().stream()
                        .allMatch(state -> state == ToolResultState.DENIED);
    }

    /**
     * 替换最后一条助手消息中 ID 匹配所提供替换项的工具调用块。
     *
     * @param messages 可变会话消息
     * @param replacements 从工具调用 ID 到替换块的映射
     * @return 替换了任意块时为 {@code true}
     */
    public static boolean replaceToolUseBlocks(
            List<Msg> messages, Map<String, ToolUseBlock> replacements) {
        if (messages == null
                || messages.isEmpty()
                || replacements == null
                || replacements.isEmpty()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg == null || msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            boolean hasMatch =
                    msg.getContent().stream()
                            .anyMatch(
                                    block ->
                                            block instanceof ToolUseBlock toolUse
                                                    && replacements.containsKey(toolUse.getId()));
            if (!hasMatch) {
                continue;
            }
            List<ContentBlock> rebuilt = new ArrayList<>(msg.getContent().size());
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolUseBlock toolUse
                        && replacements.containsKey(toolUse.getId())) {
                    rebuilt.add(replacements.get(toolUse.getId()));
                } else {
                    rebuilt.add(block);
                }
            }
            messages.set(i, msg.withContent(rebuilt));
            return true;
        }
        return false;
    }
}
