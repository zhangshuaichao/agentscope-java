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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 保存单条令牌的进程内 {@link AccessTokenStore}。
 * <p>状态位于 JVM 堆，其他实例不可见；多实例部署参见 {@link AccessTokenStore}。
 * {@link #putIfNewer} 原子合并竞争写入，较新的刷新截止时间优先；此类线程安全。
 */
public final class InMemoryAccessTokenStore implements AccessTokenStore {

    private final AtomicReference<CachedAccessToken> slot = new AtomicReference<>();

    @Override
    public CachedAccessToken get() {
        return slot.get();
    }

    @Override
    public void put(CachedAccessToken token) {
        slot.set(Objects.requireNonNull(token, "token"));
    }

    @Override
    public boolean putIfNewer(CachedAccessToken token) {
        Objects.requireNonNull(token, "token");
        while (true) {
            CachedAccessToken current = slot.get();
            if (current != null && token.refreshAtMs() <= current.refreshAtMs()) {
                return false;
            }
            if (slot.compareAndSet(current, token)) {
                return true;
            }
        }
    }

    @Override
    public void clear() {
        slot.set(null);
    }
}
