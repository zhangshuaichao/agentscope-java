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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 为一组 {@code appKey + appSecret} 获取并缓存钉钉 OpenAPI {@code accessToken}。
 * 上游接口说明中的有效期约为 7200 秒；此提供者在 TTL 约 80% 处主动刷新。
 *
 * <p>使用新 OpenAPI 端点 {@code POST /v1.0/oauth2/accessToken}，
 * 不使用 {@code oapi.dingtalk.com} 上的旧 {@code /gettoken} 端点。
 *
 * <p>默认缓存为进程内 {@link InMemoryAccessTokenStore}；
 * 传入共享存储 {@link AccessTokenStore} 可跨实例共享令牌。
 */
public final class DingTalkAccessTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String appKey;
    private final String appSecret;
    private final AccessTokenStore store;

    public DingTalkAccessTokenProvider(String apiBase, String appKey, String appSecret) {
        this(apiBase, appKey, appSecret, new InMemoryAccessTokenStore());
    }

    /**
     * 允许应用选择令牌缓存位置的构造变体，例如共享存储 {@link AccessTokenStore}，
     * 使一次刷新或失效供全部实例使用。
     *
     * @param store 已获取令牌的缓存；必须线程安全
     */
    public DingTalkAccessTokenProvider(
            String apiBase, String appKey, String appSecret, AccessTokenStore store) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 返回有效访问令牌；缓存缺失或临近过期时，在调用路径内刷新。
     */
    public Mono<String> token() {
        CachedAccessToken cached = store.get();
        if (cached != null && cached.refreshAtMs() > System.currentTimeMillis()) {
            return Mono.just(cached.value());
        }
        return refresh();
    }

    private Mono<String> refresh() {
        Map<String, Object> body = Map.of("appKey", appKey, "appSecret", appSecret);
        return client.post()
                .uri("/v1.0/oauth2/accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .map(this::parseAndStore);
    }

    private String parseAndStore(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            String token = node.path("accessToken").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException(
                        "DingTalk accessToken response missing accessToken: " + body);
            }
            int expiresIn = node.path("expireIn").asInt(7200);
            long refreshAt = System.currentTimeMillis() + (long) (expiresIn * 800L);
            store.putIfNewer(new CachedAccessToken(token, refreshAt));
            return token;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse DingTalk accessToken response: " + e.getMessage(), e);
        }
    }

    /**
     * 强制下一次 {@link #token()} 调用刷新。
     */
    public void invalidate() {
        store.clear();
    }
}
