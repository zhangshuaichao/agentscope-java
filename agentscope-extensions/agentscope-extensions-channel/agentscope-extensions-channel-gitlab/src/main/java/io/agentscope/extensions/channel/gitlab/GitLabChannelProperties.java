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
package io.agentscope.extensions.channel.gitlab;

import java.util.Map;
import java.util.Objects;

/**
 * 单个 GitLab 渠道实例的平台配置，来自 {@code agentscope.json} 中 {@code channels.<id>} 的 {@code properties}。
 * @param token 必填项目／群组／个人访问令牌，带 {@code api} 范围，用于 REST 出站和机器人身份查询
 * @param webhookToken 必填项目 webhook 共享令牌，与 {@code X-Gitlab-Token} 常量时间比较
 * @param apiBase REST API 基址，默认 {@code https://gitlab.com/api/v4}
 * @param webhookPath Spring 暴露 webhook 的 HTTP 路径，默认 {@code /api/channels/gitlab/{channelId}/webhook}
 */
public record GitLabChannelProperties(
        String token, String webhookToken, String apiBase, String webhookPath) {

    public GitLabChannelProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("gitlab.token is required");
        }
        if (webhookToken == null || webhookToken.isBlank()) {
            throw new IllegalArgumentException("gitlab.webhookToken is required");
        }
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://gitlab.com/api/v4";
        }
        // 若调用方仅提供主机且不含 /api/v 路径，则去掉尾部斜杠并补上 /api/v4；
        // 并不是删除已有 /api/v4，已有版本路径会保留。
        if (!apiBase.endsWith("/api/v4") && !apiBase.contains("/api/v")) {
            apiBase = apiBase.replaceAll("/+$", "") + "/api/v4";
        }
    }

    /**
     * 从任意属性映射读取 {@link GitLabChannelProperties}。
     */
    public static GitLabChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        String defaultPath = "/api/channels/gitlab/" + channelId + "/webhook";
        return new GitLabChannelProperties(
                asString(p, "token"),
                asString(p, "webhookToken"),
                asStringOr(p, "apiBase", null),
                asStringOr(p, "webhookPath", defaultPath));
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
