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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link BaseStore} 语义契约测试。
 *
 * <p>针对 {@link InMemoryStore}（规范参考实现）运行。Harness 不依赖 jedis/testcontainers，因此此处不测 RedisStore；其他后端应匹配这些语义。
 *
 * <p><b>递归搜索规范：</b>{@link InMemoryStore#search} 按命名空间前缀匹配，所以 {@code search(["a"])} <em>会</em>返回 {@code ["a","b"]} 等子命名空间条目。控制平面托管 KV 必须保留此行为。
 */
class BaseStoreContractTest {

    @Test
    void putGetRoundTrip_versionStartsAtOne() {
        BaseStore store = newStore();
        List<String> ns = List.of("ws");

        store.put(ns, "MEMORY.md", Map.of("content", "hello"));
        StoreItem item = store.get(ns, "MEMORY.md");

        assertNotNull(item);
        assertEquals("MEMORY.md", item.key());
        assertEquals("hello", item.value().get("content"));
        assertEquals(1L, item.version());
    }

    @Test
    void put_incrementsVersion() {
        BaseStore store = newStore();
        List<String> ns = List.of("ws");

        store.put(ns, "k", Map.of("v", 1));
        assertEquals(1L, store.get(ns, "k").version());

        store.put(ns, "k", Map.of("v", 2));
        assertEquals(2L, store.get(ns, "k").version());
        assertEquals(2, store.get(ns, "k").value().get("v"));
    }

    @Test
    void putIfVersion_successAndConflict() {
        BaseStore store = newStore();
        List<String> ns = List.of("cas");

        store.put(ns, "k", Map.of("v", 1));
        long v1 = store.get(ns, "k").version();

        assertTrue(store.putIfVersion(ns, "k", Map.of("v", 2), v1));
        assertEquals(2, store.get(ns, "k").value().get("v"));
        assertEquals(v1 + 1, store.get(ns, "k").version());

        assertFalse(store.putIfVersion(ns, "k", Map.of("v", 3), v1));
        assertEquals(2, store.get(ns, "k").value().get("v"));
    }

    @Test
    void putIfVersionZero_createIfAbsent() {
        BaseStore store = newStore();
        List<String> ns = List.of("create");

        assertTrue(store.putIfVersion(ns, "k", Map.of("v", 1), 0L));
        assertEquals(1L, store.get(ns, "k").version());

        assertFalse(store.putIfVersion(ns, "k", Map.of("v", 2), 0L));
        assertEquals(1, store.get(ns, "k").value().get("v"));
    }

    @Test
    void delete_isIdempotent() {
        BaseStore store = newStore();
        List<String> ns = List.of("del");

        store.put(ns, "k", Map.of("v", 1));
        store.delete(ns, "k");
        assertNull(store.get(ns, "k"));

        store.delete(ns, "k"); // 第二次删除不得抛异常。
        assertNull(store.get(ns, "k"));
    }

    @Test
    void search_includesChildNamespaceItems() {
        BaseStore store = newStore();

        store.put(List.of("a"), "parent", Map.of("where", "a"));
        store.put(List.of("a", "b"), "child", Map.of("where", "a/b"));

        List<StoreItem> found = store.search(List.of("a"), 100, 0);
        Set<String> keys = found.stream().map(StoreItem::key).collect(Collectors.toSet());

        // 规范语义（InMemoryStore 前缀匹配）：search(["a"]) 同时返回
        // ["a"] 及 ["a","b"] 等子命名空间中的条目。
        assertEquals(Set.of("parent", "child"), keys);
        assertEquals(2, found.size());
    }

    private static BaseStore newStore() {
        return new InMemoryStore();
    }
}
