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
package io.agentscope.extensions.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.ChannelMediaMetadata;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 将钉钉机器人消息载荷解析为 {@link InboundMessage}；来源可以是 Stream 主题
 * {@code /v1.0/im/bot/messages/get}，或使用相同协议的 HTTP 回调体。
 *
 * <p>文本消息（{@code msgtype=text}）映射为内容。
 * 媒体消息（{@code picture}／{@code audio}／{@code video}／{@code file}）
 * 映射为中性标记文本及 {@link ChannelMediaMetadata} 契约：
 * {@code downloadCode} 和各类型元数据原样保留，将编码换取字节
 * （服务端 API 返回临时下载 URL）交给应用处理。
 * 上游协议说明中音频／视频／文件仅在单聊投递给机器人，而非群聊提及。
 * {@code richText} 及未知类型返回 {@link Optional#empty()}，调用方可确认事件而不分发给智能体。
 *
 * <p>对话类型：
 *
 * <ul>
 *   <li>{@code conversationType="1"} → 私聊（{@link PeerKind#DIRECT}），对端 ID 为发送者员工 ID。
 *   <li>{@code conversationType="2"} → 群聊（{@link PeerKind#GROUP}），对端 ID 为对话 ID。
 * </ul>
 *
 * <p>群消息通常仅在机器人被提及时到达，钉钉机器人 SDK 分发提及触发的事件；
 * 平台在 {@code text.content} 中保留开头的 {@code @bot} 文本。
 */
public final class DingTalkInboundMapper {

    private final String channelId;
    private final String accountId;

    public DingTalkInboundMapper(String channelId, String accountId) {
        this.channelId = channelId;
        this.accountId = accountId;
    }

    /**
     * 从钉钉机器人消息 JSON 构建 {@link InboundMessage}。
     * 文本消息映射为内容，媒体消息映射为中性标记及 {@link ChannelMediaMetadata} 条目。
     * 其他载荷类型或格式错误事件返回空，使调用方确认而不分发。
     */
    public Optional<InboundMessage> map(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return Optional.empty();
        }
        String msgType = textValue(payload, "msgtype");
        if ("text".equalsIgnoreCase(msgType)) {
            return mapText(payload);
        }
        return mapMedia(payload, msgType);
    }

    private Optional<InboundMessage> mapText(JsonNode payload) {
        String content = payload.path("text").path("content").asText(null);
        if (content == null) {
            return Optional.empty();
        }
        content = content.strip();
        if (content.isEmpty()) {
            return Optional.empty();
        }
        return assemble(payload, content, new LinkedHashMap<>());
    }

    /**
     * 将媒体消息映射为中性标记及 {@link ChannelMediaMetadata} 契约。
     * 先从载荷顶层读取媒体字段，再回退到嵌套 {@code content} 对象，兼容协议版本间字段位置差异。
     * {@code picture} 携带 {@code downloadCode}（及 {@code pictureDownloadCode}）；
     * {@code audio} 增加 {@code recognition}（平台语音转文字）和 {@code duration}；
     * {@code video} 增加 {@code videoType} 和 {@code duration}
     * （钉钉协议将这两种时长都定义为毫秒）；{@code file} 增加 {@code fileName}。
     */
    private Optional<InboundMessage> mapMedia(JsonNode payload, String msgType) {
        String kind = mediaKind(msgType);
        if (kind == null) {
            return Optional.empty();
        }
        String downloadCode = mediaField(payload, "downloadCode");
        if (downloadCode == null || downloadCode.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChannelMediaMetadata.KIND, kind);
        metadata.put(ChannelMediaMetadata.PROVIDER_TYPE, msgType);
        metadata.put(ChannelMediaMetadata.ID, downloadCode);
        String fileName = null;
        switch (kind) {
            case ChannelMediaMetadata.KIND_IMAGE ->
                    ChannelMediaMetadata.putIfPresent(
                            metadata,
                            ChannelMediaMetadata.SECONDARY_ID,
                            mediaField(payload, "pictureDownloadCode"));
            case ChannelMediaMetadata.KIND_AUDIO -> {
                ChannelMediaMetadata.putIfPresent(
                        metadata,
                        ChannelMediaMetadata.RECOGNITION,
                        mediaField(payload, "recognition"));
                ChannelMediaMetadata.putIfPresent(
                        metadata,
                        ChannelMediaMetadata.DURATION_MS,
                        mediaField(payload, "duration"));
            }
            case ChannelMediaMetadata.KIND_VIDEO -> {
                ChannelMediaMetadata.putIfPresent(
                        metadata, ChannelMediaMetadata.FORMAT, mediaField(payload, "videoType"));
                ChannelMediaMetadata.putIfPresent(
                        metadata,
                        ChannelMediaMetadata.DURATION_MS,
                        mediaField(payload, "duration"));
            }
            case ChannelMediaMetadata.KIND_FILE -> {
                fileName = mediaField(payload, "fileName");
                ChannelMediaMetadata.putIfPresent(
                        metadata, ChannelMediaMetadata.FILE_NAME, fileName);
            }
            default -> {
                // 不可达：类型已由 mediaKind 枚举。
            }
        }
        return assemble(payload, ChannelMediaMetadata.markerText(kind, fileName), metadata);
    }

    /**
     * 返回钉钉 {@code msgtype} 对应的规范化媒体类型；
     * 不是已映射媒体消息时返回 {@code null}。
     */
    private static String mediaKind(String msgType) {
        if (msgType == null) {
            return null;
        }
        return switch (msgType.toLowerCase(Locale.ROOT)) {
            case "picture" -> ChannelMediaMetadata.KIND_IMAGE;
            case "audio" -> ChannelMediaMetadata.KIND_AUDIO;
            case "video" -> ChannelMediaMetadata.KIND_VIDEO;
            case "file" -> ChannelMediaMetadata.KIND_FILE;
            default -> null;
        };
    }

    /**
     * 从载荷顶层读取媒体字段，找不到时回退到嵌套 {@code content} 对象。
     */
    private static String mediaField(JsonNode payload, String field) {
        String value = textValue(payload, field);
        if (value != null) {
            return value;
        }
        JsonNode content = payload.get("content");
        return content != null ? textValue(content, field) : null;
    }

    private Optional<InboundMessage> assemble(
            JsonNode payload, String textContent, Map<String, Object> metadata) {
        metadata.put("channelMessageId", extractMsgId(payload).orElse(""));
        String conversationType = textValue(payload, "conversationType");
        String senderStaffId = textValue(payload, "senderStaffId");
        String conversationId = textValue(payload, "conversationId");

        Peer peer;
        String senderId;
        if ("2".equals(conversationType) && conversationId != null) {
            peer = new Peer(PeerKind.GROUP, conversationId);
            senderId = senderStaffId != null ? senderStaffId : conversationId;
        } else {
            String peerId = senderStaffId != null ? senderStaffId : conversationId;
            if (peerId == null || peerId.isBlank()) {
                return Optional.empty();
            }
            peer = new Peer(PeerKind.DIRECT, peerId);
            senderId = peerId;
        }

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(senderId)
                        .textContent(textContent)
                        .metadata(metadata)
                        .build();
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(accountId)
                        .senderId(senderId)
                        .build());
    }

    /**
     * 返回存在的 {@code msgId} 字段，供幂等存储使用。
     */
    public static Optional<String> extractMsgId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        String id = textValue(payload, "msgId");
        return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(id);
    }

    private static String textValue(JsonNode payload, String field) {
        JsonNode v = payload.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }
}
