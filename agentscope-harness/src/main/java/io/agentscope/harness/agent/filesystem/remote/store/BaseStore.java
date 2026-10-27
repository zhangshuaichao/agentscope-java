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

import java.util.List;
import java.util.Map;

/**
 * 基于命名空间的键值存储抽象接口。
 *
 * <p>条目按命名空间（类似层级路径的元组）组织，并由命名空间内的键标识。
 */
public interface BaseStore {
    /**
     * 显式声明支持：带版本读取以及跨写入者的持久比较并设置。
     */
    default boolean supportsAtomicSessionStorage() {
        return false;
    }

    /**
     * 按命名空间和键获取单个条目。
     *
     * @param namespace 层级命名空间路径
     * @param key 命名空间内的条目键
     * @return 存储条目；未找到时为 {@code null}
     */
    StoreItem get(List<String> namespace, String key);

    /**
     * 存储或更新条目。
     *
     * @param namespace 层级命名空间路径
     * @param key 命名空间内的条目键
     * @param value 要存储的数据
     */
    void put(List<String> namespace, String key, Map<String, Object> value);

    /**
     * 条件更新：仅当当前存储版本与 {@code expectedVersion} 一致时保存条目。
     *
     * <p>这是比较并交换（CAS）操作，用于防止多个写入者并发修改同一键时丢失更新。调用方应：
     * <ol>
     *   <li>通过 {@link #get} 读取条目，并记住 {@link StoreItem#version()}。
     *   <li>在本地计算新值。
     *   <li>调用 {@code putIfVersion}，将记住的版本作为 {@code expectedVersion}。
     *   <li>若返回 {@code false}，说明条目已被其他写入者修改；从第 1 步重试。
     * </ol>
     *
     * <p>{@code expectedVersion} 的特殊值 {@code 0} 表示“仅在键尚不存在时写入”（即不存在时创建）。
     *
     * <p>默认实现始终返回 {@code false}，使现有存储实现无需修改即可编译。
     * 支持分布式部署的后端应覆盖此方法，实现真正的服务端原子检查。
     *
     * @param namespace 层级命名空间路径
     * @param key 命名空间内的条目键
     * @param value 要存储的新数据
     * @param expectedVersion 调用方观察到的版本，必须与当前存储版本匹配
     * @return 写入成功时为 {@code true}；版本不匹配时为 {@code false}
     */
    default boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        return false;
    }

    /**
     * 分页搜索命名空间内的条目。
     *
     * @param namespace 层级命名空间路径
     * @param limit 最多返回的条目数量
     * @param offset 跳过的条目数量
     * @return 匹配的存储条目列表
     */
    List<StoreItem> search(List<String> namespace, int limit, int offset);

    /**
     * 按命名空间和键删除条目。
     *
     * @param namespace 层级命名空间路径
     * @param key 要删除的条目键
     */
    void delete(List<String> namespace, String key);
}
