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

package io.agentscope.core.a2a.server.utils;

import static io.agentscope.core.a2a.agent.message.MetadataUtil.metadataValue;
import static io.agentscope.core.a2a.agent.message.MetadataUtil.toJsonValues;
import static io.agentscope.core.a2a.agent.message.MetadataUtil.withMetadata;
import static io.agentscope.core.a2a.agent.utils.MessageConvertUtil.convertFromMsg;

import io.agentscope.core.a2a.agent.message.ContentBlockParserRouter;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.message.PartParserRouter;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;

/**
 * 在 AgentScope {@link Msg} 与 A2A {@link Message} 或 {@link Artifact} 之间转换消息。
 */
public class MessageConvertUtil {

    private static final PartParserRouter PART_PARSER = new PartParserRouter();

    private static final ContentBlockParserRouter CONTENT_BLOCK_PARSER =
            new ContentBlockParserRouter();

    /**
     * 将 {@link Msg} 列表转换为 {@link Message}。
     *
     * @param msgs 待转换的 Msg 列表
     * @param taskId 任务 ID
     * @param contextId 上下文 ID
     * @return 转换后的 Message 对象
     */
    public static Message convertFromMsgToMessage(List<Msg> msgs, String taskId, String contextId) {
        Message.Builder builder = Message.builder(convertFromMsg(msgs));
        return builder.taskId(taskId).contextId(contextId).build();
    }

    /**
     * 合并共享同一消息 ID 的连续流式增量消息。
     *
     * <p>用于累积 {@code Event} 分块的执行器回退路径。
     * 常规用户消息列表转换仍必须直接使用 {@link #convertFromMsgToMessage(List, String, String)}，
     * 以保留语义消息边界与内容块边界。
     *
     * @param msgs 流式事件管线发出的消息
     * @return 按消息 ID 合并连续文本／思考增量后的消息
     */
    public static List<Msg> compactStreamingChunks(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return List.of();
        }

        List<Msg> result = new LinkedList<>();
        Msg current = null;
        for (Msg msg : msgs) {
            if (msg == null) {
                continue;
            }
            if (canMergeStreamingChunk(current, msg)) {
                current = current.withContent(mergeContent(current.getContent(), msg.getContent()));
            } else {
                if (current != null) {
                    result.add(current);
                }
                current = msg;
            }
        }
        if (current != null) {
            result.add(current);
        }
        return result;
    }

    /**
     * 将 {@link Msg} 转换为 {@link Message}。
     *
     * @param msg 待转换的 Msg
     * @param taskId 任务 ID
     * @param contextId 上下文 ID
     * @return 转换后的 Message 对象
     */
    public static Message convertFromMsgToMessage(Msg msg, String taskId, String contextId) {
        Message.Builder builder = Message.builder();
        Map<String, Object> metadata = new HashMap<>();
        if (null != msg.getMetadata() && !msg.getMetadata().isEmpty()) {
            metadata.put(msg.getId(), toJsonValues(msg.getMetadata()));
        }
        return builder.parts(convertFromContentBlocks(msg))
                .metadata(metadata)
                .role(Message.Role.ROLE_AGENT)
                .taskId(taskId)
                .contextId(contextId)
                .build();
    }

    /**
     * 将 {@link Msg} 元数据转换为 A2A 产物元数据。
     *
     * @param msg 需要转换元数据的 Msg
     * @return JSON 值形式的元数据；Msg 无元数据时为 {@code null}
     */
    public static Map<String, Object> convertMetadata(Msg msg) {
        return toJsonValues(msg.getMetadata());
    }

    /**
     * 将 {@link Msg} 中的内容块转换为 {@link Part} 列表。
     *
     * @param msg 保存待转换内容块的 Msg
     * @return Part 列表
     */
    public static List<Part<?>> convertFromContentBlocks(Msg msg) {
        return convertFromContentBlocks(msg, false);
    }

    /**
     * 将 {@link Msg} 中的内容块转换为 {@link Part} 列表。
     *
     * @param msg 保存待转换内容块的 Msg
     * @param streamingChunk 这些 Part 是否表示流式增量分块
     * @return Part 列表
     */
    public static List<Part<?>> convertFromContentBlocks(Msg msg, boolean streamingChunk) {
        return new LinkedList<>(
                msg.getContent().stream()
                        .map(CONTENT_BLOCK_PARSER::parse)
                        .filter(Objects::nonNull)
                        .map(part -> withMetadata(part, partMetadata(msg, streamingChunk)))
                        .toList());
    }

    private static Map<String, Object> partMetadata(Msg msg, boolean streamingChunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(MessageConstants.MSG_ID_METADATA_KEY, msg.getId());
        metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, msg.getName());
        if (streamingChunk) {
            metadata.put(MessageConstants.STREAM_CHUNK_METADATA_KEY, Boolean.TRUE);
        }
        return metadata;
    }

    private static boolean canMergeStreamingChunk(Msg current, Msg next) {
        return current != null
                && current.getId() != null
                && Objects.equals(current.getId(), next.getId())
                && Objects.equals(current.getName(), next.getName())
                && Objects.equals(current.getRole(), next.getRole());
    }

    private static List<ContentBlock> mergeContent(
            List<ContentBlock> first, List<ContentBlock> second) {
        List<ContentBlock> merged = new LinkedList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second == null) {
            return merged;
        }
        for (ContentBlock block : second) {
            appendMergedBlock(merged, block);
        }
        return merged;
    }

    private static void appendMergedBlock(List<ContentBlock> blocks, ContentBlock block) {
        if (block == null || blocks.isEmpty()) {
            if (block != null) {
                blocks.add(block);
            }
            return;
        }

        ContentBlock previous = blocks.get(blocks.size() - 1);
        if (previous instanceof TextBlock previousText && block instanceof TextBlock textBlock) {
            blocks.set(
                    blocks.size() - 1,
                    TextBlock.builder().text(previousText.getText() + textBlock.getText()).build());
        } else if (previous instanceof ThinkingBlock previousThinking
                && block instanceof ThinkingBlock thinkingBlock) {
            blocks.set(
                    blocks.size() - 1,
                    ThinkingBlock.builder()
                            .thinking(previousThinking.getThinking() + thinkingBlock.getThinking())
                            .build());
        } else {
            blocks.add(block);
        }
    }

    /**
     * 将 A2A {@link Message} 转换为 {@link Msg} 列表。
     *
     * <p>转换规则与 A2aAgent 中将 {@link Msg} 列表转换为 A2A {@link Message}
     * 的过程相反，步骤如下：
     * <ol>
     *   <li>遍历 A2A {@link Message} 的所有 {@link Part}，转换为目标 {@link ContentBlock}。</li>
     *   <li>尝试从每个 {@link Part} 元数据读取 msgId 和 msgName，并保持顺序。</li>
     *   <li>按 msgId 组合全部 {@link ContentBlock}；{@link Part} 没有 msgId 时为其生成独立随机 ID。</li>
     *   <li>按顺序遍历 msgId，使用 msgId、msgName、对应 {@link ContentBlock}
     *       及从 {@link Message} 元数据中找到的元数据构建 {@link Msg}。</li>
     * </ol>
     *
     * <p>A2A {@link Message} 若来自非 AgentScope 客户端，Part 可能不含 msgId 和 msgName，
     * 此时降级为每个 {@link Part} 对应一个仅含单个 {@link ContentBlock} 的 {@link Msg}。
     *
     * @param message 来自 A2A 客户端的协议消息
     * @return {@link Msg} 列表
     */
    public static List<Msg> convertFromMessageToMsgs(Message message) {
        List<Msg> result = new LinkedList<>();
        Set<String> msgIds = new LinkedHashSet<>();
        Map<String, List<ContentBlock>> partsByMsgId = new HashMap<>();
        Map<String, String> msgIdToName = new HashMap<>();
        Map<String, MsgRole> msgIdToRole = new HashMap<>();
        message.parts().stream()
                .filter(Objects::nonNull)
                .forEach(
                        part -> {
                            String msgId = getMsgId(part);
                            partsByMsgId
                                    .compute(
                                            msgId,
                                            (key, value) -> {
                                                if (null == value) {
                                                    value = new LinkedList<>();
                                                }
                                                return value;
                                            })
                                    .add(PART_PARSER.parse(part));
                            msgIds.add(msgId);
                            msgIdToName.put(msgId, getMsgName(part));
                            MsgRole role = getMsgRole(part);
                            if (role != null) {
                                msgIdToRole.putIfAbsent(msgId, role);
                            }
                        });
        msgIds.forEach(
                msgId ->
                        result.add(
                                Msg.builder()
                                        .id(msgId)
                                        .name(msgIdToName.get(msgId))
                                        .role(
                                                msgIdToRole.getOrDefault(
                                                        msgId, convertRole(message.role())))
                                        .content(partsByMsgId.get(msgId))
                                        .metadata(getMsgMetadata(message, msgId))
                                        .build()));
        return result;
    }

    private static String getMsgId(Part<?> part) {
        String msgId = metadataValue(part, MessageConstants.MSG_ID_METADATA_KEY);
        return null == msgId ? UUID.randomUUID().toString() : msgId;
    }

    private static String getMsgName(Part<?> part) {
        return metadataValue(part, MessageConstants.SOURCE_NAME_METADATA_KEY);
    }

    private static MsgRole getMsgRole(Part<?> part) {
        String role = metadataValue(part, MessageConstants.MSG_ROLE_METADATA_KEY);
        if (role == null) {
            return null;
        }
        try {
            return MsgRole.valueOf(role);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static MsgRole convertRole(Message.Role role) {
        if (role == Message.Role.ROLE_AGENT) {
            return MsgRole.ASSISTANT;
        }
        return MsgRole.USER;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMsgMetadata(Message message, String msgId) {
        if (null == message || null == message.metadata()) {
            return Map.of();
        }
        Object metadata = message.metadata().get(msgId);
        if (null == metadata) {
            return Map.of();
        }
        if (metadata instanceof Map) {
            return (Map<String, Object>) metadata;
        }
        return Map.of();
    }
}
