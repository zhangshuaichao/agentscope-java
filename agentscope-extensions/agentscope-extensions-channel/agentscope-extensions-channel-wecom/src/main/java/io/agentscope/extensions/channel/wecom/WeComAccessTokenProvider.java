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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import java.time.Duration;
import java.util.Objects;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 为一组 {@code corpid + corpsecret} 获取并缓存企业微信 {@code access_token}。实现按服务方返回 TTL 的约 80% 设置刷新阈值，expires_in 缺失时采用 7200 秒默认值。
 *
 * <p>默认缓存为进程本地 {@link InMemoryAccessTokenStore}；传入共享存储的 {@link AccessTokenStore} 可跨实例共享令牌。
 *
 * <p>【学习提示】这里没有后台定时刷新：token() 检查缓存，达到阈值后在当前调用路径请求新令牌。共享缓存也不等于所有实例的并发刷新自动合并。
 */
public final class WeComAccessTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String corpId;
    private final String secret;
    private final AccessTokenStore store;

    public WeComAccessTokenProvider(String apiBase, String corpId, String secret) {
        this(apiBase, corpId, secret, new InMemoryAccessTokenStore());
    }

    /**
     * 允许应用选择令牌缓存位置的构造方法，例如使用共享存储的 {@link AccessTokenStore}，让一次刷新或失效处理作用于所有实例。
     *
     * @param store 已获取令牌的缓存，必须线程安全
     */
    public WeComAccessTokenProvider(
            String apiBase, String corpId, String secret, AccessTokenStore store) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.corpId = corpId;
        this.secret = secret;
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 返回有效访问令牌；缓存缺失或接近过期时，在当前调用路径刷新。
     */
    public Mono<String> token() {
        CachedAccessToken cached = store.get();
        if (cached != null && cached.refreshAtMs() > System.currentTimeMillis()) {
            return Mono.just(cached.value());
        }
        return refresh();
    }

    private Mono<String> refresh() {
        return client.get()
                .uri(
                        uri ->
                                uri.path("/cgi-bin/gettoken")
                                        .queryParam("corpid", corpId)
                                        .queryParam("corpsecret", secret)
                                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .map(this::parseAndStore);
    }

    private String parseAndStore(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            int errcode = node.path("errcode").asInt(0);
            if (errcode != 0) {
                throw new IllegalStateException(
                        "WeCom gettoken failed: errcode="
                                + errcode
                                + ", errmsg="
                                + node.path("errmsg").asText());
            }
            String token = node.path("access_token").asText();
            int expiresIn = node.path("expires_in").asInt(7200);
            long refreshAt = System.currentTimeMillis() + (long) (expiresIn * 800L);
            store.putIfNewer(new CachedAccessToken(token, refreshAt));
            return token;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse WeCom gettoken response: " + e.getMessage(), e);
        }
    }

    /**
 * 强制下一次 {@link #token()} 调用刷新，适用于测试和错误恢复。
 */
    public void invalidate() {
        store.clear();
    }
}
