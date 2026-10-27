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
package io.agentscope.extensions.channel.wecom;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.ChannelMediaMetadata;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 将解密后的企业微信回调 XML 正文解析为 {@link InboundMessage}。
 *
 * <p>文本消息（{@code MsgType=text}，单用户应用消息）映射为其内容。媒体消息（{@code MsgType} 为 {@code image} / {@code voice} / {@code video}）映射为中性标记文本加 {@link ChannelMediaMetadata} 契约：原样保留服务方素材标识，由应用负责后续获取（需令牌认证的 {@code media/get} 交换；上游协议说明中的素材有效期为三天）。其他入站类型（事件、位置、链接等）返回 {@link Optional#empty()}，让调用方确认 webhook 而不分发到智能体。
 *
 * <p>【学习提示】这里仅映射媒体标识，不下载媒体、更不把它直接解码成模型可见内容。应用仍须处理访问令牌、素材过期与下载边界。
 */
public final class WeComInboundMapper {

    private static final DocumentBuilderFactory DBF = newSafeDocumentBuilderFactory();

    private final String channelId;
    private final String accountId;

    public WeComInboundMapper(String channelId, String accountId) {
        this.channelId = channelId;
        this.accountId = accountId;
    }

    /**
     * 从已解密的企业微信消息 XML 构建 {@link InboundMessage}。文本消息映射为内容；媒体消息映射为中性标记和 {@link ChannelMediaMetadata} 条目。其他载荷类型或格式错误事件返回空，让调用方确认而不分发。
     */
    public Optional<InboundMessage> map(String xml) {
        try {
            DocumentBuilder builder = DBF.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            String msgType = textValue(root, "MsgType");
            if ("text".equalsIgnoreCase(msgType)) {
                return mapText(root);
            }
            return mapMedia(root, msgType);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse WeCom callback XML: " + e.getMessage(), e);
        }
    }

    private Optional<InboundMessage> mapText(Element root) {
        String fromUser = textValue(root, "FromUserName");
        String content = textValue(root, "Content");
        if (fromUser == null || fromUser.isBlank() || content == null) {
            return Optional.empty();
        }
        return assemble(
                fromUser,
                content,
                Map.of("channelMessageId", Objects.toString(textValue(root, "MsgId"), "")));
    }

    /**
     * 将媒体消息映射为中性标记及 {@link ChannelMediaMetadata} 契约。{@code image} 携带 {@code PicUrl} 和 {@code MediaId}；{@code voice} 携带 {@code MediaId} 和 {@code Format}；{@code video} 携带 {@code MediaId} 和 {@code ThumbMediaId}。
     */
    private Optional<InboundMessage> mapMedia(Element root, String msgType) {
        String kind = mediaKind(msgType);
        if (kind == null) {
            return Optional.empty();
        }
        String fromUser = textValue(root, "FromUserName");
        String mediaId = textValue(root, "MediaId");
        if (fromUser == null || fromUser.isBlank() || mediaId == null || mediaId.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelMessageId", Objects.toString(textValue(root, "MsgId"), ""));
        metadata.put(ChannelMediaMetadata.KIND, kind);
        metadata.put(ChannelMediaMetadata.PROVIDER_TYPE, msgType);
        metadata.put(ChannelMediaMetadata.ID, mediaId);
        switch (kind) {
            case ChannelMediaMetadata.KIND_IMAGE ->
                    ChannelMediaMetadata.putIfPresent(
                            metadata, ChannelMediaMetadata.URL, textValue(root, "PicUrl"));
            case ChannelMediaMetadata.KIND_AUDIO ->
                    ChannelMediaMetadata.putIfPresent(
                            metadata, ChannelMediaMetadata.FORMAT, textValue(root, "Format"));
            case ChannelMediaMetadata.KIND_VIDEO ->
                    ChannelMediaMetadata.putIfPresent(
                            metadata,
                            ChannelMediaMetadata.SECONDARY_ID,
                            textValue(root, "ThumbMediaId"));
            default -> {
                // 不可到达：类型由 mediaKind 枚举。
            }
        }
        return assemble(fromUser, ChannelMediaMetadata.markerText(kind, null), metadata);
    }

    /**
 * 根据映射后的文本内容和元数据构建私聊 {@link InboundMessage}。
 */
    private Optional<InboundMessage> assemble(
            String fromUser, String textContent, Map<String, Object> metadata) {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(fromUser)
                        .textContent(textContent)
                        .metadata(metadata)
                        .build();
        Peer peer = new Peer(PeerKind.DIRECT, fromUser);
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(accountId)
                        .senderId(fromUser)
                        .build());
    }

    /**
     * 返回企业微信 {@code MsgType} 对应的规范化媒体类型；不是已映射媒体消息时返回 {@code null}。
     */
    private static String mediaKind(String msgType) {
        if (msgType == null) {
            return null;
        }
        return switch (msgType.toLowerCase(Locale.ROOT)) {
            case "image" -> ChannelMediaMetadata.KIND_IMAGE;
            case "voice" -> ChannelMediaMetadata.KIND_AUDIO;
            case "video" -> ChannelMediaMetadata.KIND_VIDEO;
            default -> null;
        };
    }

    /**
 * 存在时返回 {@code MsgId} 字段，供幂等存储使用。
 */
    public static Optional<String> extractMsgId(String xml) {
        try {
            DocumentBuilder builder = DBF.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            String id = textValue(doc.getDocumentElement(), "MsgId");
            return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String textValue(Element root, String tagName) {
        NodeList list = root.getElementsByTagName(tagName);
        if (list == null || list.getLength() == 0) {
            return null;
        }
        Node node = list.item(0);
        return node != null ? node.getTextContent() : null;
    }

    private static DocumentBuilderFactory newSafeDocumentBuilderFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
            // 尽力加强解析安全性；解析器不支持某项特性时跳过。
        }
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }
}
