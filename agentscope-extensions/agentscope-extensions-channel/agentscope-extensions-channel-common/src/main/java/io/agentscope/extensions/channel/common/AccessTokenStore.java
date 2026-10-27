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
package io.agentscope.extensions.channel.common;

/**
 * 缓存一份渠道凭据对应的平台访问令牌。
 *
 * <p>提供器先查缓存，再向平台取令牌并回写。刷新时间、响应解析和错误码失效策略由提供器负责，
 * 存储仅提供不带键的缓存槽；一个实例服务一份凭据，不同凭据的提供器共享实例会混淆令牌。
 * <p>默认 {@link InMemoryAccessTokenStore} 位于 JVM 堆，不跨进程共享。多实例部署可使用共享后端，
 * 例如按渠道 ID 区分的 Redis，并经渠道工厂注入，使刷新和失效影响整个部署。
 * <p>实现必须线程安全，因为提供器可能从并发响应式流水线调用。系统没有跨实例刷新锁，允许并发刷新；
 * 提供器用 {@link #putIfNewer} 防止刷新截止时间倒退，{@link #put} 则无条件替换。
 * <p>【学习提示】共享缓存和单凭据隔离是不同维度。原子选新只比较 refreshAtMs，
 * 不能把一次缓存写入看成远端令牌仍有效的证明；平台拒绝时仍需提供器的失效逻辑。
 */
public interface AccessTokenStore {

    /**
     * 返回缓存令牌。
     * <p>一个实例只保存一份凭据的令牌，不要让不同凭据的提供器共享它。
     * @return 上次保存的令牌，无缓存时为 {@code null}；不按年龄过滤，提供器根据 {@link CachedAccessToken#refreshAtMs()} 判断刷新
     */
    CachedAccessToken get();

    /**
     * 缓存 {@code token}，无条件覆盖旧条目。
     * <p>一个实例只服务一份凭据，不应在不同凭据的提供器之间共享。
     * @param token 待缓存令牌
     */
    void put(CachedAccessToken token);

    /**
     * 仅当 {@code token} 的 {@link CachedAccessToken#refreshAtMs()} 严格晚于当前条目时保存，
     * 防止无跨实例锁的并发刷新把缓存回退到更早截止时间。
     * <p>默认实现为非原子的读取—比较—写入；共享存储实现应覆盖为原子条件写，例如 CAS 脚本，
     * 才能在并发下维持该保证。
     * @param token 待缓存令牌，适用 {@link #put} 的单凭据约束
     * @return {@code token} 被保存时为 {@code true}
     */
    default boolean putIfNewer(CachedAccessToken token) {
        CachedAccessToken current = get();
        if (current == null || token.refreshAtMs() > current.refreshAtMs()) {
            put(token);
            return true;
        }
        return false;
    }

    /**
     * 移除缓存令牌，使提供器下次调用重新获取。
     */
    void clear();
}
