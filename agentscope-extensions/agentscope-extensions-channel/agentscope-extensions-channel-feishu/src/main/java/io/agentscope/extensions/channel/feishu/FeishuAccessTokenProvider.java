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
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 为一组 {@code app_id + app_secret} 获取并缓存飞书 {@code tenant_access_token}。
 * 上游接口说明中的有效期约为 7200 秒；此提供者在 TTL 约 80% 处主动刷新。
 *
 * <p>默认缓存为进程内 {@link InMemoryAccessTokenStore}；
 * 传入共享存储 {@link AccessTokenStore} 可跨实例共享令牌。
 *
 * @see <a href="https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal">tenant_access_token（内部应用）</a>
 */
public final class FeishuAccessTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient client;
    private final String appId;
    private final String appSecret;
    private final AccessTokenStore store;
    private final Duration responseTimeout;

    public FeishuAccessTokenProvider(String apiBase, String appId, String appSecret) {
        this(apiBase, appId, appSecret, new InMemoryAccessTokenStore(), DEFAULT_RESPONSE_TIMEOUT);
    }

    /**
     * 允许应用选择令牌缓存位置的构造变体，例如共享存储 {@link AccessTokenStore}，
     * 使一次刷新或失效可供全部实例使用。
     *
     * @param store 已获取令牌的缓存；必须线程安全
     */
    public FeishuAccessTokenProvider(
            String apiBase, String appId, String appSecret, AccessTokenStore store) {
        this(apiBase, appId, appSecret, store, DEFAULT_RESPONSE_TIMEOUT);
    }

    /**
     * 明确指定单次令牌请求时限的构造变体。生产环境保留默认 10 秒；
     * 资源紧张的 CI 上针对回环服务器的测试可传入更长时限。
     *
     * @param responseTimeout 单次令牌请求的正值时限
     */
    public FeishuAccessTokenProvider(
            String apiBase, String appId, String appSecret, Duration responseTimeout) {
        this(apiBase, appId, appSecret, new InMemoryAccessTokenStore(), responseTimeout);
    }

    /**
     * @param responseTimeout 单次令牌请求的正值时限
     */
    public FeishuAccessTokenProvider(
            String apiBase,
            String appId,
            String appSecret,
            AccessTokenStore store,
            Duration responseTimeout) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.appId = appId;
        this.appSecret = appSecret;
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(responseTimeout, "responseTimeout");
        if (responseTimeout.isZero() || responseTimeout.isNegative())
            throw new IllegalArgumentException("responseTimeout must be positive");
        this.responseTimeout = responseTimeout;
    }

    /**
     * 返回有效 tenant_access_token，缺失或临近过期时在调用路径内刷新。
     */
    // 【学习提示】80% TTL 是下次 token() 调用时检查的刷新阈值，不是后台定时器。
    // 共享存储共享的是令牌值；这里没有把并发 refresh 合并为一次请求的锁或单飞机制。
    public Mono<String> token() {
        CachedAccessToken cached = store.get();
        if (cached != null && cached.refreshAtMs() > System.currentTimeMillis()) {
            return Mono.just(cached.value());
        }
        return refresh();
    }

    private Mono<String> refresh() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);
        return client.post()
                .uri("/open-apis/auth/v3/tenant_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(responseTimeout)
                .map(this::parseAndStore);
    }

    private String parseAndStore(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            int code = node.path("code").asInt(0);
            if (code != 0) {
                throw new IllegalStateException(
                        "Feishu tenant_access_token failed: code="
                                + code
                                + ", msg="
                                + node.path("msg").asText());
            }
            String token = node.path("tenant_access_token").asText();
            int expiresIn = node.path("expire").asInt(7200);
            long refreshAt = System.currentTimeMillis() + (long) (expiresIn * 800L);
            store.putIfNewer(new CachedAccessToken(token, refreshAt));
            return token;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse Feishu tenant_access_token response: " + e.getMessage(), e);
        }
    }

    /**
     * 强制下一次 {@link #token()} 调用刷新，适用于测试或错误恢复。
     */
    public void invalidate() {
        store.clear();
    }
}
