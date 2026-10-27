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

import java.util.Map;
import java.util.Objects;

/**
 * 单个钉钉渠道实例的配置。
 *
 * <p>接收模式：{@link #MODE_STREAM}（默认）通过持久 WebSocket 接收机器人消息；
 * {@link #MODE_HTTP} 通过发送到 {@code /api/channels/dingtalk/{channelId}/callback}
 * 的签名 HTTP 回调接收，需要 Spring Web 应用。
 *
 * @param appKey 企业内部应用键
 * @param appSecret 企业内部应用密钥
 * @param robotCode 用作出站发送者 ID 的机器人编码，回复必需
 * @param mode 接收模式：{@code stream}（默认）或 {@code http}
 * @param aesKey 回调体解密的 43 字符 Base64 AES 密钥（无填充）；仅 {@code http} 模式使用，且仅启用加密时必需
 * @param apiBase 钉钉 OpenAPI 基础地址覆盖，默认 {@code https://api.dingtalk.com}
 * @param oapiBase 旧 {@code gettoken} OAPI 基础地址覆盖，默认 {@code https://oapi.dingtalk.com}
 * @param streamRegisterUrl Stream 网关注册端点覆盖，默认 {@code https://api.dingtalk.com/v1.0/gateway/connections/open}
 */
public record DingTalkChannelProperties(
        String appKey,
        String appSecret,
        String robotCode,
        String mode,
        String aesKey,
        String apiBase,
        String oapiBase,
        String streamRegisterUrl) {

    /**
     * 接收模式值：通过持久 Stream WebSocket 接收机器人消息。
     */
    public static final String MODE_STREAM = "stream";

    /**
     * 接收模式值：以签名 HTTP 回调接收机器人消息。
     */
    public static final String MODE_HTTP = "http";

    public static final String DEFAULT_API_BASE = "https://api.dingtalk.com";
    public static final String DEFAULT_OAPI_BASE = "https://oapi.dingtalk.com";
    public static final String DEFAULT_STREAM_REGISTER_URL =
            DEFAULT_API_BASE + "/v1.0/gateway/connections/open";

    public DingTalkChannelProperties {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("dingtalk.appKey is required");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("dingtalk.appSecret is required");
        }
        if (robotCode == null || robotCode.isBlank()) {
            throw new IllegalArgumentException("dingtalk.robotCode is required");
        }
        if (mode == null || mode.isBlank()) {
            mode = MODE_STREAM;
        }
        if (!MODE_STREAM.equals(mode) && !MODE_HTTP.equals(mode)) {
            throw new IllegalArgumentException(
                    "dingtalk.mode must be '"
                            + MODE_STREAM
                            + "' or '"
                            + MODE_HTTP
                            + "', got: "
                            + mode);
        }
        if (aesKey != null && aesKey.length() != 43) {
            throw new IllegalArgumentException(
                    "dingtalk.aesKey must be 43 characters when set (got " + aesKey.length() + ")");
        }
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = DEFAULT_API_BASE;
        }
        if (oapiBase == null || oapiBase.isBlank()) {
            oapiBase = DEFAULT_OAPI_BASE;
        }
        if (streamRegisterUrl == null || streamRegisterUrl.isBlank()) {
            streamRegisterUrl = DEFAULT_STREAM_REGISTER_URL;
        }
    }

    /**
     * 匹配引入回调模式前签名的便捷构造方法，使用 {@code mode=stream} 且无 {@code aesKey} 委托。
     *
     * @deprecated 请使用规范构造方法或 {@link #from}，明确接收模式
     */
    @Deprecated
    public DingTalkChannelProperties(
            String appKey,
            String appSecret,
            String robotCode,
            String apiBase,
            String oapiBase,
            String streamRegisterUrl) {
        this(appKey, appSecret, robotCode, null, null, apiBase, oapiBase, streamRegisterUrl);
    }

    /**
     * 从任意属性映射读取 {@link DingTalkChannelProperties}。
     */
    public static DingTalkChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        return new DingTalkChannelProperties(
                asString(p, "appKey"),
                asString(p, "appSecret"),
                asString(p, "robotCode"),
                asString(p, "mode"),
                asString(p, "aesKey"),
                asString(p, "apiBase"),
                asString(p, "oapiBase"),
                asString(p, "streamRegisterUrl"));
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }
}
