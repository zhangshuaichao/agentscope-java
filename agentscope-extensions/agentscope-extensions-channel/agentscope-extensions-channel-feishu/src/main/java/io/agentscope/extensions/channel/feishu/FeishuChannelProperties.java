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

import java.util.Map;
import java.util.Objects;

/**
 * 单个飞书／Lark 渠道实例的专用配置，来自 {@code agentscope.json}
 * 中 {@code channels.<id>} 的 {@code properties} 块。
 *
 * @param appId 飞书自建应用 ID（cli_xxx）
 * @param appSecret 飞书自建应用密钥
 * @param encryptKey 开发者控制台配置的可选 Encrypt Key；非空白时回调体采用 AES-256-CBC 加密，解析前须解密
 * @param verificationToken 开发者控制台配置的可选 Verification Token；非空白时与 URL 验证挑战的 {@code token} 字段匹配
 * @param callbackPath Spring 暴露回调的 HTTP 路径，默认 {@code /api/channels/feishu/{channelId}/callback}
 * @param apiBase 飞书 Open API 基础 URL 覆盖，默认 {@code https://open.feishu.cn}
 */
public record FeishuChannelProperties(
        String appId,
        String appSecret,
        String encryptKey,
        String verificationToken,
        String callbackPath,
        String apiBase) {

    public FeishuChannelProperties {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("feishu.appId is required");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("feishu.appSecret is required");
        }
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://open.feishu.cn";
        }
    }

    /**
     * 从任意属性映射读取 {@link FeishuChannelProperties}。
     */
    public static FeishuChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        String defaultCallback = "/api/channels/feishu/" + channelId + "/callback";
        return new FeishuChannelProperties(
                asString(p, "appId"),
                asString(p, "appSecret"),
                asStringOr(p, "encryptKey", null),
                asStringOr(p, "verificationToken", null),
                asStringOr(p, "callbackPath", defaultCallback),
                asStringOr(p, "apiBase", null));
    }

    /**
     * 此渠道是否启用了 AES-256-CBC 载荷解密。
     */
    public boolean isEncrypted() {
        return encryptKey != null && !encryptKey.isBlank();
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static String asStringOr(Map<String, Object> p, String key, String fallback) {
        Object v = p.get(key);
        return v == null ? fallback : v.toString();
    }
}
