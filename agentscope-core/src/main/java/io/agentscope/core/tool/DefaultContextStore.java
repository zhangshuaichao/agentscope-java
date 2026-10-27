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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 使用两级映射 Class → (Key → Object) 的内存 {@link ContextStore}。
 *
 * <p>支持单例（每类型一个）和多实例（按键区分）模式，不可变且线程安全。
 *
 * @deprecated 属于 {@link ToolExecutionContext} 层，已由 {@link io.agentscope.core.agent.RuntimeContext} 取代。
 */
@Deprecated
class DefaultContextStore implements ContextStore {

    private static final String DEFAULT_KEY = "";

    // 两级映射：Class -> (Key -> Object)
    private final Map<Class<?>, Map<String, Object>> objectMap;

    private DefaultContextStore(Builder builder) {
        if (builder.objectMap != null) {
            Map<Class<?>, Map<String, Object>> copy = new HashMap<>();
            for (Map.Entry<Class<?>, Map<String, Object>> entry : builder.objectMap.entrySet()) {
                copy.put(
                        entry.getKey(),
                        Collections.unmodifiableMap(new HashMap<>(entry.getValue())));
            }
            this.objectMap = Collections.unmodifiableMap(copy);
        } else {
            this.objectMap = Collections.emptyMap();
        }
    }

    /**
     * 按键和类型检索对象。
     *
     * @param key 标识实例的键
     * @param type 要检索的类类型
     * @param <T> 对象类型
     * @return 对象实例；未找到时为 null
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Map<String, Object> keyMap = objectMap.get(type);
        if (keyMap == null) {
            return null;
        }

        Object obj = keyMap.get(key);
        if (obj != null && type.isInstance(obj)) {
            return (T) obj;
        }
        return null;
    }

    /**
     * 仅按类型检索对象（单例场景）。
     *
     * <p>返回以默认键（空字符串）保存的对象。默认键下没有对象时返回 null。
     *
     * @param type 要检索的类类型
     * @param <T> 对象类型
     * @return 对象实例；未找到时为 null
     */
    @Override
    public <T> T get(Class<T> type) {
        return get(DEFAULT_KEY, type);
    }

    /**
     * 检查指定键和类型的对象是否存在。
     *
     * @param key 标识实例的键
     * @param type 要检查的类类型
     * @return 存在时为 true，否则为 false
     */
    @Override
    public boolean contains(String key, Class<?> type) {
        Map<String, Object> keyMap = objectMap.get(type);
        return keyMap != null && keyMap.containsKey(key);
    }

    /**
     * 检查是否存在指定类型的对象。
     *
     * @param type 要检查的类类型
     * @return 至少存在一个该类型对象时为 true，否则为 false
     */
    @Override
    public boolean contains(Class<?> type) {
        Map<String, Object> keyMap = objectMap.get(type);
        return keyMap != null && !keyMap.isEmpty();
    }

    /**
     * 返回内部对象映射的不可修改视图。
     *
     * <p>结构为 Class → (Key → Object)：外层以类型为键，内层以实例标识符为键。同时支持单例模式（默认键 ""）和多实例模式（自定义键）。
     *
     * @return 包含全部已存对象的不可修改映射，永不为 null（可以为空）
     */
    public Map<Class<?>, Map<String, Object>> getObjectMap() {
        return objectMap;
    }

    /**
     * 创建 DefaultContextStore 的构建器。
     *
     * @return 新的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建空的 DefaultContextStore。
     *
     * @return 空存储
     */
    public static DefaultContextStore empty() {
        return new Builder().build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DefaultContextStore that = (DefaultContextStore) o;
        return Objects.equals(objectMap, that.objectMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(objectMap);
    }

    @Override
    public String toString() {
        return "DefaultContextStore{" + "objectMap=" + objectMap + '}';
    }

    /**
     * DefaultContextStore 的构建器。
     */
    public static class Builder {
        private Map<Class<?>, Map<String, Object>> objectMap;

        private Builder() {}

        /**
         * 以对象的精确类型为键注册对象（单例场景）。
         *
         * <p>使用默认键（空字符串）注册，表示此类型的单例。
         *
         * <p>示例：
         * <pre>{@code
         * DatabaseConfig config = new DatabaseConfig();
         * builder.register(config);  // Singleton - no key needed
         * }</pre>
         *
         * @param object 要注册的对象（不能为 null）
         * @return 当前构建器
         * @throws NullPointerException 当 object 为 null 时
         */
        @SuppressWarnings("unchecked")
        public Builder register(Object object) {
            Objects.requireNonNull(object, "Object must not be null");
            Class<Object> type = (Class<Object>) object.getClass();
            return register(DEFAULT_KEY, type, object);
        }

        /**
         * 以显式类型键注册对象（单例场景）。
         *
         * <p>可按接口或父类类型注册对象。
         *
         * <p>示例：
         * <pre>{@code
         * UserContextImpl impl = new UserContextImpl();
         * builder.register(UserContext.class, impl);  // Register by interface
         * }</pre>
         *
         * @param type 类型键（不能为 null）
         * @param object 要注册的对象（不能为 null）
         * @param <T> 对象类型
         * @return 当前构建器
         * @throws NullPointerException 当 type 或 object 为 null 时
         * @throws IllegalArgumentException 当 object 不是 type 的实例时
         */
        public <T> Builder register(Class<T> type, T object) {
            return register(DEFAULT_KEY, type, object);
        }

        /**
         * 使用自定义键和显式类型注册对象。
         *
         * <p>可用键区分并存储同类型的多个实例。
         *
         * <p>示例：
         * <pre>{@code
         * UserContext admin = new UserContext("admin");
         * UserContext guest = new UserContext("guest");
         * builder.register("admin", UserContext.class, admin)
         *        .register("guest", UserContext.class, guest);
         * }</pre>
         *
         * @param key 标识实例的键（不能为 null）
         * @param type 类型键（不能为 null）
         * @param object 要注册的对象（不能为 null）
         * @param <T> 对象类型
         * @return 当前构建器
         * @throws NullPointerException 当 key、type 或 object 为 null 时
         * @throws IllegalArgumentException 当 object 不是 type 的实例时
         */
        public <T> Builder register(String key, Class<T> type, T object) {
            Objects.requireNonNull(key, "Key must not be null");
            Objects.requireNonNull(type, "Type must not be null");
            Objects.requireNonNull(object, "Object must not be null");
            if (!type.isInstance(object)) {
                throw new IllegalArgumentException(
                        "Object must be an instance of type " + type.getName());
            }

            if (this.objectMap == null) {
                this.objectMap = new HashMap<>();
            }

            Map<String, Object> keyMap = this.objectMap.computeIfAbsent(type, k -> new HashMap<>());
            keyMap.put(key, object);
            return this;
        }

        /**
         * 使用自定义键和对象运行时类型注册对象。
         *
         * <p>这是无需显式指定类型即可注册带键对象的便捷方法。
         *
         * <p>示例：
         * <pre>{@code
         * UserContext admin = new UserContext("admin");
         * builder.register("admin", admin);  // Type inferred from object
         * }</pre>
         *
         * @param key 标识实例的键（不能为 null）
         * @param object 要注册的对象（不能为 null）
         * @return 当前构建器
         * @throws NullPointerException 当 key 或 object 为 null 时
         */
        @SuppressWarnings("unchecked")
        public Builder register(String key, Object object) {
            Objects.requireNonNull(key, "Key must not be null");
            Objects.requireNonNull(object, "Object must not be null");
            Class<Object> type = (Class<Object>) object.getClass();
            return register(key, type, object);
        }

        /**
         * 构建 DefaultContextStore。
         *
         * @return 新的 DefaultContextStore
         */
        public DefaultContextStore build() {
            return new DefaultContextStore(this);
        }
    }
}
