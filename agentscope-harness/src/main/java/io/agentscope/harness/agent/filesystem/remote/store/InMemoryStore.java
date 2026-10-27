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
package io.agentscope.harness.agent.filesystem.remote.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 线程安全的 {@link BaseStore} 内存实现。
 *
 * <p>条目保存在 ConcurrentHashMap 中，键由命名空间各部分与条目键拼接而成，以 {@code '\0'} 分隔。
 *
 * <p>每次成功执行 {@link #put} 或 {@link #putIfVersion} 都会递增条目的 {@link StoreItem#version()} 计数器，以支持乐观并发控制。
 */
public class InMemoryStore implements BaseStore {

    private final ConcurrentMap<String, StoreItem> store = new ConcurrentHashMap<>();

    @Override
    public StoreItem get(List<String> namespace, String key) {
        return store.get(compoundKey(namespace, key));
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        String ck = compoundKey(namespace, key);
        store.compute(
                ck,
                (k, existing) -> {
                    long nextVersion = (existing != null) ? existing.version() + 1 : 1L;
                    return new StoreItem(key, value, nextVersion);
                });
    }

    /**
     * 原子比较并交换：仅当已存储版本等于 {@code expectedVersion} 时更新条目。
     * {@code expectedVersion} 为 {@code 0} 时，仅在键尚不存在的情况下成功。
     *
     * @return 写入成功时为 {@code true}；版本不匹配时为 {@code false}
     */
    @Override
    public boolean supportsAtomicSessionStorage() {
        return true;
    }

    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        String ck = compoundKey(namespace, key);
        boolean[] written = {false};
        store.compute(
                ck,
                (k, existing) -> {
                    long currentVersion = (existing != null) ? existing.version() : 0L;
                    if (currentVersion != expectedVersion) {
                        return existing; // 版本不匹配，不更新
                    }
                    written[0] = true;
                    return new StoreItem(key, value, currentVersion + 1);
                });
        return written[0];
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String prefix = namespacePrefix(namespace);
        List<StoreItem> matches = new ArrayList<>();
        for (Map.Entry<String, StoreItem> entry : store.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                matches.add(entry.getValue());
            }
        }
        Collections.sort(matches, (a, b) -> a.key().compareTo(b.key()));

        int start = Math.min(offset, matches.size());
        int end = Math.min(start + limit, matches.size());
        return matches.subList(start, end);
    }

    @Override
    public void delete(List<String> namespace, String key) {
        store.remove(compoundKey(namespace, key));
    }

    /**
     * 返回当前存储的条目数量。
     */
    public int size() {
        return store.size();
    }

    /**
     * 移除存储中的全部条目。
     */
    public void clear() {
        store.clear();
    }

    private static String compoundKey(List<String> namespace, String key) {
        return namespacePrefix(namespace) + key;
    }

    private static String namespacePrefix(List<String> namespace) {
        StringBuilder sb = new StringBuilder();
        for (String component : namespace) {
            sb.append(component).append('\0');
        }
        return sb.toString();
    }
}
