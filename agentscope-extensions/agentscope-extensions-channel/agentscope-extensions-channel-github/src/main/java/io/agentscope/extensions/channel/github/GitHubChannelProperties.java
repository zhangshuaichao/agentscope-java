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
package io.agentscope.extensions.channel.github;

import java.util.Map;
import java.util.Objects;

/**
 * 单个 GitHub 渠道实例的平台配置，来自 {@code agentscope.json} 中 {@code channels.<id>} 的 {@code properties}。
 * @param token 必填个人访问令牌 PAT，可为细粒度或经典形式，用于 REST 出站与机器人身份查询
 * @param webhookSecret 必填 webhook 共享秘密，用于验证 {@code X-Hub-Signature-256}
 * @param apiBase REST API 基址，默认 {@code https://api.github.com}；企业服务器可设为 {@code https://github.your-corp.com/api/v3}
 * @param webhookPath Spring 暴露 webhook 的 HTTP 路径，默认 {@code /api/channels/github/{channelId}/webhook}
 * @param botUserLogin 可选机器人登录名覆盖值，例如 {@code my-bot}；未配置时启动阶段通过 {@code GET /user} 解析
 */
public record GitHubChannelProperties(
        String token,
        String webhookSecret,
        String apiBase,
        String webhookPath,
        String botUserLogin) {

    public GitHubChannelProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("github.token is required");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("github.webhookSecret is required");
        }
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.github.com";
        }
    }

    /**
     * 从任意属性映射读取 {@link GitHubChannelProperties}。
     */
    public static GitHubChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        String defaultPath = "/api/channels/github/" + channelId + "/webhook";
        return new GitHubChannelProperties(
                asString(p, "token"),
                asString(p, "webhookSecret"),
                asStringOr(p, "apiBase", null),
                asStringOr(p, "webhookPath", defaultPath),
                asStringOr(p, "botUserLogin", null));
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
