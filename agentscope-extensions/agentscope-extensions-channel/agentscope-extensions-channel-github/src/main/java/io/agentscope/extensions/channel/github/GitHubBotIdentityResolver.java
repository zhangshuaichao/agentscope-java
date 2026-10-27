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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 通过 {@code GET /user} 解析配置令牌对应的 GitHub 用户 ID 和登录名，
 * 用于识别机器人自己发布的评论，避免 webhook 回流造成无限循环。
 * <p>解析失败仅记录日志，不阻止启动；结果缓存在渠道生命周期内。可通过 {@link #botUserId()} 访问。
 */
public final class GitHubBotIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(GitHubBotIdentityResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String token;
    private final AtomicReference<Identity> identity = new AtomicReference<>(Identity.UNKNOWN);

    public GitHubBotIdentityResolver(String apiBase, String token, String botLoginOverride) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.token = token;
        if (botLoginOverride != null && !botLoginOverride.isBlank()) {
            this.identity.set(new Identity(-1L, botLoginOverride));
        }
    }

    /**
     * 同步请求 {@code GET /user} 并缓存响应，失败记录 WARN 后继续启动。
     */
    public void refresh() {
        try {
            String body =
                    client.get()
                            .uri("/user")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .header(HttpHeaders.USER_AGENT, "agentscope-builder")
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(10))
                            .block();
            if (body == null) {
                return;
            }
            JsonNode node = MAPPER.readTree(body);
            long id = node.path("id").asLong(-1);
            String login = node.path("login").asText(null);
            if (id > 0 && login != null) {
                identity.set(new Identity(id, login));
                log.info("GitHub bot identity resolved: id={}, login={}", id, login);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve GitHub bot identity: {}", e.getMessage());
        }
    }

    /**
     * 返回机器人的数字用户 ID，未解析时为 {@code -1}。
     */
    public long botUserId() {
        return identity.get().id;
    }

    /**
     * 返回机器人登录名，未解析时为 null。
     */
    public String botLogin() {
        return identity.get().login;
    }

    private record Identity(long id, String login) {
        static final Identity UNKNOWN = new Identity(-1L, null);
    }
}
