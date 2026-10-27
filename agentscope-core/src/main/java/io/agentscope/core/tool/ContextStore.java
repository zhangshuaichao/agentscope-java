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
package io.agentscope.core.tool;

/**
 * 工具执行上下文的存储层抽象。
 *
 * <p>定义上下文对象的存储契约，支持两种检索方式：
 * <ol>
 * <li><b>仅按类型</b>：{@code get(Class<T>)}，适用于单例场景</li>
 * <li><b>按键和类型</b>：{@code get(String, Class<T>)}，适用于多实例场景</li>
 * </ol>
 *
 * <p>既能处理单个 UserContext 的简单情况，也能处理不同用户拥有多个 UserContext 的复杂情况。
 *
 * <p>实现方式包括：
 * <ul>
 * <li>简单内存 Map 存储（{@link DefaultContextStore}）</li>
 * <li>自定义存储（Redis、数据库等）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // Single instance per type
 * DatabaseConfig config = store.get(DatabaseConfig.class);
 *
 * // Multiple instances of same type
 * UserContext admin = store.get("admin", UserContext.class);
 * UserContext guest = store.get("guest", UserContext.class);
 * }</pre>
 *
 * @see ToolExecutionContext
 * @see DefaultContextStore
 * @deprecated 属于 {@link ToolExecutionContext} 层，已由 {@link io.agentscope.core.agent.RuntimeContext} 取代。
 */
@Deprecated
public interface ContextStore {

    /**
     * 按键和类型检索对象。
     *
     * <p>可用不同键存储相同类型的多个实例。键可以是用户 ID、会话 ID 或其他区分实例的标识符。
     *
     * <p>示例：
     * <pre>{@code
     * // Store multiple UserContext instances
     * store.register("user123", new UserContext("user123"));
     * store.register("user456", new UserContext("user456"));
     *
     * // Retrieve specific instance
     * UserContext user123 = store.get("user123", UserContext.class);
     * }</pre>
     *
     * @param key 标识具体实例的键
     * @param type 要检索的类类型
     * @param <T> 对象类型
     * @return 对象实例；未找到时为 null
     */
    <T> T get(String key, Class<T> type);

    /**
     * 仅按类型检索对象（无键）。
     *
     * <p>适用于某类型只有一个实例的单例场景。存在多个实例时，实现可：
     * <ul>
     * <li>返回默认实例（由实现定义）</li>
     * <li>返回第一个注册的实例</li>
     * <li>返回 null，并要求显式指定键</li>
     * </ul>
     *
     * <p>示例：
     * <pre>{@code
     * // Single DatabaseConfig instance
     * DatabaseConfig config = store.get(DatabaseConfig.class);
     * }</pre>
     *
     * @param type 要检索的类类型
     * @param <T> 对象类型
     * @return 对象实例；未找到时为 null
     */
    <T> T get(Class<T> type);

    /**
     * 检查指定键和类型的对象是否存在。
     *
     * @param key 标识实例的键
     * @param type 要检查的类类型
     * @return 存在时为 true，否则为 false
     */
    boolean contains(String key, Class<?> type);

    /**
     * 检查是否存在指定类型的对象（不论键为何）。
     *
     * @param type 要检查的类类型
     * @return 至少存在一个该类型对象时为 true，否则为 false
     */
    boolean contains(Class<?> type);
}
