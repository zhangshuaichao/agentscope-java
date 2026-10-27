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

import java.util.Map;

/**
 * 从 {@link BaseStore} 获取的单个条目。
 *
 * @param key 条目在其命名空间内的键
 * @param value 以字符串为键的映射形式表示的条目数据
 * @param version 单调递增的版本计数器，从 1 开始，每次成功执行 {@link BaseStore#put} 或 {@link BaseStore#putIfVersion} 都递增。
 *     {@code 0} 表示版本未知（例如由引入版本机制之前的旧实现返回的条目）。
 */
public record StoreItem(String key, Map<String, Object> value, long version) {

    /**
     * 为尚未提供版本的代码保留的兼容构造方法。
     */
    public StoreItem(String key, Map<String, Object> value) {
        this(key, value, 0L);
    }
}
