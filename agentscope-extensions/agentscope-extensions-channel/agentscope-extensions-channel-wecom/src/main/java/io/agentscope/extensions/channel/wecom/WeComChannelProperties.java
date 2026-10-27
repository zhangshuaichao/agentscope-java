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

import java.util.Map;
import java.util.Objects;

/**
 * 单个企业微信渠道实例的服务方专用配置，来自 {@code agentscope.json} 中 {@code channels.<id>} 的 {@code properties} 块。
 *
 * <p>环境变量解析值（例如 {@code ${WECOM_CORP_ID}}）应由调用方在构造前替换。
 *
 * @param corpId 企业微信企业 ID
 * @param agentId 自建应用 ID（数字，发送 API 中使用 int）
 * @param secret 应用秘密
 * @param token 回调令牌（在企业微信管理控制台配置）
 * @param encodingAesKey 回调加密使用的 43 字符无填充 base64 AES 密钥
 * @param callbackPath Spring 暴露回调的 HTTP 路径，默认 {@code /api/channels/wecom/{channelId}/callback}
 * @param apiBase 企业微信 API 基础 URL 覆盖值，默认 {@code https://qyapi.weixin.qq.com}
 */
public record WeComChannelProperties(
        String corpId,
        int agentId,
        String secret,
        String token,
        String encodingAesKey,
        String callbackPath,
        String apiBase) {

    public WeComChannelProperties {
        if (corpId == null || corpId.isBlank()) {
            throw new IllegalArgumentException("wecom.corpId is required");
        }
        if (agentId <= 0) {
            throw new IllegalArgumentException("wecom.agentId must be a positive integer");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("wecom.secret is required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("wecom.token is required");
        }
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("wecom.encodingAesKey must be 43 characters");
        }
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://qyapi.weixin.qq.com";
        }
    }

    /**
 * 从任意属性映射读取 {@link WeComChannelProperties}。
 */
    public static WeComChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        String defaultCallback = "/api/channels/wecom/" + channelId + "/callback";
        return new WeComChannelProperties(
                asString(p, "corpId"),
                asInt(p, "agentId"),
                asString(p, "secret"),
                asString(p, "token"),
                asString(p, "encodingAesKey"),
                asStringOr(p, "callbackPath", defaultCallback),
                asStringOr(p, "apiBase", null));
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static String asStringOr(Map<String, Object> p, String key, String fallback) {
        Object v = p.get(key);
        return v == null ? fallback : v.toString();
    }

    private static int asInt(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "wecom." + key + " must be an integer, got: " + v, e);
        }
    }
}
