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
package io.agentscope.extensions.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 将已解密的飞书事件订阅 v2 信封解析为 {@link InboundMessage}。
 *
 * <p>Schema 2.0 信封结构：
 *
 * <pre>
 * {
 *   "schema": "2.0",
 *   "header": {"event_id":"...","event_type":"im.message.receive_v1","tenant_key":"...", ...},
 *   "event": {
 *     "sender": {"sender_id":{"open_id":"...","user_id":"...","union_id":"..."}, "sender_type":"user"},
 *     "message": {
 *       "message_id":"om_...",
 *       "chat_id":"oc_...",
 *       "chat_type":"p2p|group",
 *       "message_type":"text|...",
 *       "content":"{\"text\":\"@_user_1 hello\"}"
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>文本消息（{@code message_type=text}）映射为自身内容；内层 {@code content}
 * 是 JSON 编码的字符串，需再次解析以读取 {@code text} 字段。
 * 媒体消息（{@code image}／{@code file}／{@code folder}／{@code audio}／{@code media}）
 * 映射为中性标记文本及 {@link ChannelMediaMetadata} 契约：
 * {@code image_key}／{@code file_key} 素材标识和各类型元数据原样保留，
 * 获取字节（需令牌认证的消息资源下载）留给应用完成。
 *
 * <p>【学习提示】素材 ID 和标记文本不等于已下载的图片／音频内容。模型是否真正获得媒体，
 * 取决于后续应用的鉴权下载、大小检查和内容块构造，映射成功本身不能证明这些步骤已完成。
 * 其他类型（富文本、贴纸、交互卡片、分享卡片等）返回 {@link Optional#empty()}，
 * 调用方可确认事件而不分发。
 */
public final class FeishuInboundMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String channelId;

    public FeishuInboundMapper(String channelId) {
        this.channelId = channelId;
    }

    /**
     * 返回 {@code header.event_id}，供幂等存储使用。
     */
    public static Optional<String> extractEventId(JsonNode envelope) {
        if (envelope == null) {
            return Optional.empty();
        }
        String id = envelope.path("header").path("event_id").asText(null);
        return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(id);
    }

    /**
     * 信封为 URL 验证请求时返回挑战值。
     */
    public static Optional<String> extractUrlChallenge(JsonNode envelope) {
        if (envelope == null) {
            return Optional.empty();
        }
        String type = envelope.path("type").asText(null);
        if (!"url_verification".equals(type)) {
            return Optional.empty();
        }
        String challenge = envelope.path("challenge").asText(null);
        return (challenge == null || challenge.isBlank())
                ? Optional.empty()
                : Optional.of(challenge);
    }

    /**
     * 返回 {@code event.sender.sender_id.open_id}，用于机器人循环的自身检测。
     */
    public static Optional<String> extractSenderOpenId(JsonNode envelope) {
        if (envelope == null) {
            return Optional.empty();
        }
        String id =
                envelope.path("event")
                        .path("sender")
                        .path("sender_id")
                        .path("open_id")
                        .asText(null);
        return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(id);
    }

    /**
     * 返回 {@code header.tenant_key}（多租户 ID）。
     */
    public static Optional<String> extractTenantKey(JsonNode envelope) {
        if (envelope == null) {
            return Optional.empty();
        }
        String key = envelope.path("header").path("tenant_key").asText(null);
        return (key == null || key.isBlank()) ? Optional.empty() : Optional.of(key);
    }

    /**
     * 将已解密的 Schema 2.0 信封映射为 {@link InboundMessage}。
     * 文本消息映射为内容，媒体消息映射为中性标记及 {@link ChannelMediaMetadata} 条目。
     * 其他类型或格式错误事件返回空，使调用方确认而不分发。
     */
    public Optional<InboundMessage> map(JsonNode envelope) {
        if (envelope == null) {
            return Optional.empty();
        }
        JsonNode event = envelope.path("event");
        if (event.isMissingNode() || !event.isObject()) {
            return Optional.empty();
        }
        JsonNode message = event.path("message");
        String messageType = message.path("message_type").asText(null);
        String chatType = message.path("chat_type").asText(null);
        String chatId = message.path("chat_id").asText(null);
        if (chatId == null || chatId.isBlank()) {
            return Optional.empty();
        }
        String openId = event.path("sender").path("sender_id").path("open_id").asText(null);
        if (openId == null
                || openId.isBlank()
                || !"user".equals(event.path("sender").path("sender_type").asText())
                || message.path("message_id").asText("").isBlank()) return Optional.empty();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelMessageId", message.path("message_id").asText(""));
        metadata.put("channelReplyToId", message.path("parent_id").asText(""));
        metadata.put("channelThreadId", message.path("root_id").asText(""));
        String text;
        if ("text".equalsIgnoreCase(messageType)) {
            String contentJson = message.path("content").asText(null);
            if (contentJson == null || contentJson.isBlank()) {
                return Optional.empty();
            }
            JsonNode content;
            try {
                content = MAPPER.readTree(contentJson);
            } catch (Exception e) {
                return Optional.empty();
            }
            text = content.path("text").asText(null);
            if (text == null) {
                return Optional.empty();
            }
            // 平台用结构化键表示提及。去除开头的提及键，
            // 使群聊中被点名的机器人能收到明确工作命令。
            text = text.strip();
            for (JsonNode mention : message.path("mentions")) {
                String key = mention.path("key").asText("");
                if (!key.isBlank() && text.startsWith(key))
                    text = text.substring(key.length()).stripLeading();
            }
        } else {
            Optional<String> marker = mapMediaContent(message, messageType, metadata);
            if (marker.isEmpty()) {
                return Optional.empty();
            }
            text = marker.get();
        }
        // 群聊以 chat_id 寻址，不按用户分别路由。私聊也使用
        // chat_id 作为对话键，这是飞书标识
        // 一对一聊天实例的稳定 ID，机器人回复须发往 chat_id，使用
        // receive_id_type=chat_id 作为寻址类型
        // （也可用 open_id；这里统一使用 chat_id）。
        PeerKind kind = "group".equalsIgnoreCase(chatType) ? PeerKind.GROUP : PeerKind.DIRECT;
        Peer peer = new Peer(kind, chatId);
        String senderName = openId != null ? openId : chatId;
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(senderName)
                        .textContent(text)
                        .metadata(metadata)
                        .build();
        String tenant = envelope.path("header").path("tenant_key").asText(null);
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(tenant)
                        .senderId(senderName)
                        .build());
    }

    /**
     * 将媒体消息内容映射为 {@link ChannelMediaMetadata} 并返回中性标记文本；
     * 类型未映射或缺少素材标识时返回空。{@code image} 使用 {@code image_key}；
     * {@code file}、{@code folder}、{@code audio} 和 {@code media} 使用 {@code file_key}；
     * {@code media} 是带视频的组合内容，其中 {@code image_key} 是封面。
     */
    private Optional<String> mapMediaContent(
            JsonNode message, String messageType, Map<String, Object> metadata) {
        String kind = mediaKind(messageType);
        if (kind == null) {
            return Optional.empty();
        }
        JsonNode content = parseMessageContent(message);
        if (content == null) {
            return Optional.empty();
        }
        String id =
                ChannelMediaMetadata.KIND_IMAGE.equals(kind)
                        ? content.path("image_key").asText(null)
                        : content.path("file_key").asText(null);
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        metadata.put(ChannelMediaMetadata.KIND, kind);
        metadata.put(ChannelMediaMetadata.PROVIDER_TYPE, messageType);
        metadata.put(ChannelMediaMetadata.ID, id);
        if (ChannelMediaMetadata.KIND_VIDEO.equals(kind)) {
            ChannelMediaMetadata.putIfPresent(
                    metadata,
                    ChannelMediaMetadata.SECONDARY_ID,
                    content.path("image_key").asText(null));
        }
        if (ChannelMediaMetadata.KIND_AUDIO.equals(kind)
                || ChannelMediaMetadata.KIND_VIDEO.equals(kind)) {
            ChannelMediaMetadata.putIfPresent(
                    metadata,
                    ChannelMediaMetadata.DURATION_MS,
                    content.path("duration").asText(null));
        }
        String fileName = content.path("file_name").asText(null);
        ChannelMediaMetadata.putIfPresent(metadata, ChannelMediaMetadata.FILE_NAME, fileName);
        return Optional.of(ChannelMediaMetadata.markerText(kind, fileName));
    }

    /**
     * 返回飞书 {@code message_type} 对应的规范化媒体类型；
     * 不是已映射媒体消息时返回 {@code null}。
     */
    private static String mediaKind(String messageType) {
        if (messageType == null) {
            return null;
        }
        return switch (messageType.toLowerCase(Locale.ROOT)) {
            case "image" -> ChannelMediaMetadata.KIND_IMAGE;
            // 文件夹的 file_key 标识文件夹节点本身；消息资源
            // 下载 API 仅提供图片、文件、音频和视频资源，因此它是
            // 引用 ID，而不是可直接获取的字节内容。
            case "file", "folder" -> ChannelMediaMetadata.KIND_FILE;
            case "audio" -> ChannelMediaMetadata.KIND_AUDIO;
            case "media" -> ChannelMediaMetadata.KIND_VIDEO;
            default -> null;
        };
    }

    private static JsonNode parseMessageContent(JsonNode message) {
        String contentJson = message.path("content").asText(null);
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(contentJson);
        } catch (Exception e) {
            return null;
        }
    }
}
