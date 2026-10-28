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
package io.agentscope.dataagent.runtime.channel.webhook;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单个通用 Webhook 通道配置：轻量 HTTP 入站/出站旁路，供 IM / 工单 / CI 系统
 * 无需打开主聊天界面即可调用 DataAgent。
 * @param sharedSecret HMAC-SHA256 共享密钥；非空白时，每个入站请求必须携带
 *     {@code X-DataAgent-Sig} 头，值为 {@code hex(hmacSha256(secret, rawBody))}。
 *     留空会禁用签名校验（仅在可信反向代理后才可接受）。
 * @param allowedIps 可选源 IP 允许列表；非空时，即使签名有效，其他对端请求仍以 403 拒绝。
 *     有 {@code X-Forwarded-For} 时从中读取远端地址，否则使用直接连接地址。
 * @param outboundParkCapacity 通道内 {@code replyMode=poll} 请求等待通过
 *     {@code GET /api/webhook/{channelId}/outbound/{inboundId}} 投递时的环形缓冲容量。
 *     缓冲区满时丢弃最旧的暂存回复。
 * @param longPollTimeoutMillis 轮询端点等待时长，超时返回 {@code 204 No
 *     Content}，供客户端继续轮询。
 */
public record WebhookChannelProperties(
        String sharedSecret,
        List<String> allowedIps,
        int outboundParkCapacity,
        long longPollTimeoutMillis) {

    public static final int DEFAULT_PARK_CAPACITY = 256;
    public static final long DEFAULT_LONG_POLL_MILLIS = 5_000L;

    public WebhookChannelProperties {
        allowedIps = allowedIps != null ? List.copyOf(allowedIps) : List.of();
        if (outboundParkCapacity <= 0) {
            outboundParkCapacity = DEFAULT_PARK_CAPACITY;
        }
        if (longPollTimeoutMillis <= 0) {
            longPollTimeoutMillis = DEFAULT_LONG_POLL_MILLIS;
        }
    }

    /** 从任意属性映射读取 {@link WebhookChannelProperties}。 */
    public static WebhookChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        return new WebhookChannelProperties(
                asString(p, "sharedSecret"),
                asStringList(p, "allowedIps"),
                asInt(p, "outboundParkCapacity", DEFAULT_PARK_CAPACITY),
                asLong(p, "longPollTimeoutMillis", DEFAULT_LONG_POLL_MILLIS));
    }

    /** 是否要求 HMAC 签名验证。 */
    public boolean requiresSignature() {
        return sharedSecret != null && !sharedSecret.isBlank();
    }

    /** IP 允许列表是否启用。 */
    public boolean hasIpAllowList() {
        return !allowedIps.isEmpty();
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of(v.toString());
    }

    private static int asInt(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long asLong(Map<String, Object> p, String key, long fallback) {
        Object v = p.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
