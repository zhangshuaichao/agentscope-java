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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 通过按优先级解析，将自定义 POJO 传递给工具方法的门面。
 *
 * <p>两层架构：对外接口与存储层（{@link ContextStore}）。
 *
 * <p>优先级链从高到低：Call → Agent → Toolkit → Spring。
 *
 * <p>示例：
 *
 * <pre>{@code
 * // Register custom POJO
 * ToolExecutionContext context = ToolExecutionContext.builder()
 *     .register(new UserContext("user123"))
 *     .build();
 *
 * // Auto-injected by type in tool methods
 * @Tool
 * public ToolResultBlock myTool(UserContext ctx) {
 *     return ToolResultBlock.text(ctx.getUserId());
 * }
 * }</pre>
 *
 * @see ContextStore
 * @see DefaultContextStore
 * @deprecated 请改用 {@link io.agentscope.core.agent.RuntimeContext}。通过 {@code RuntimeContext.builder().put(Type.class, value).build()} 注册 POJO，并传给 {@code agent.call(msg, runtimeContext)}。
 */
@Deprecated
public class ToolExecutionContext {

    private final List<ContextStore> stores;

    private ToolExecutionContext(Builder builder) {
        this.stores =
                builder.stores != null && !builder.stores.isEmpty()
                        ? Collections.unmodifiableList(new ArrayList<>(builder.stores))
                        : Collections.emptyList();
    }

    /**
     * 按键和类型检索对象（多实例场景）。
     *
     * @param key 标识实例的键
     * @param type 要检索的类类型
     * @param <T> 对象类型
     * @return 对象实例；未找到时为 null
     */
    public <T> T get(String key, Class<T> type) {
        // 按优先级顺序查询全部存储
        for (ContextStore store : stores) {
            T obj = store.get(key, type);
            if (obj != null) {
                return obj;
            }
        }
        return null;
    }

    /**
     * 仅按类型检索对象（单例场景）。
     *
     * @param type 要检索的类类型
     * @param <T> 对象类型
     * @return 对象实例；未找到时为 null
     */
    public <T> T get(Class<T> type) {
        // 按优先级顺序查询全部存储
        for (ContextStore store : stores) {
            T obj = store.get(type);
            if (obj != null) {
                return obj;
            }
        }
        return null;
    }

    /**
     * 检查任意存储中是否存在指定键和类型的对象。
     *
     * @param key 标识实例的键
     * @param type 要检查的类类型
     * @return 任意存储包含该对象时为 true，否则为 false
     */
    public boolean contains(String key, Class<?> type) {
        for (ContextStore store : stores) {
            if (store.contains(key, type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查任意存储中是否存在指定类型的对象（不论键为何）。
     *
     * @param type 要检查的类类型
     * @return 任意存储包含该对象时为 true，否则为 false
     */
    public boolean contains(Class<?> type) {
        for (ContextStore store : stores) {
            if (store.contains(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按优先级顺序获取全部存储。
     *
     * @return 不可修改的存储列表（永不为 null，可以为空）
     */
    public List<ContextStore> getStores() {
        return stores;
    }

    /**
     * 将多个上下文合并为按优先级解析的链。
     *
     * <p>越靠前的上下文优先级越高。
     *
     * @param contexts 按优先级排列的待合并上下文（最高优先级在前）
     * @return 合并后的新上下文
     */
    public static ToolExecutionContext merge(ToolExecutionContext... contexts) {
        if (contexts == null || contexts.length == 0) {
            return empty();
        }

        // 按优先级顺序收集所有上下文的存储
        List<ContextStore> mergedStores = new ArrayList<>();
        for (ToolExecutionContext ctx : contexts) {
            if (ctx != null && !ctx.stores.isEmpty()) {
                mergedStores.addAll(ctx.stores);
            }
        }

        if (mergedStores.isEmpty()) {
            return empty();
        }

        return new Builder().stores(mergedStores).build();
    }

    /**
     * 创建用于构造 ToolExecutionContext 实例的构建器。
     *
     * @return 新的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建空上下文。
     *
     * @return 空的 ToolExecutionContext
     */
    public static ToolExecutionContext empty() {
        return new Builder().build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolExecutionContext that = (ToolExecutionContext) o;
        return Objects.equals(stores, that.stores);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stores);
    }

    @Override
    public String toString() {
        return "ToolExecutionContext{" + "stores=" + stores.size() + " stores}";
    }

    /**
     * ToolExecutionContext 的构建器。
     */
    public static class Builder {
        private DefaultContextStore.Builder storeBuilder = DefaultContextStore.builder();
        private List<ContextStore> stores;

        private Builder() {}

        /**
         * 按运行时类型注册对象（单例模式）。
         *
         * @param object 要注册的对象
         * @return 当前构建器
         */
        public Builder register(Object object) {
            this.storeBuilder.register(object);
            return this;
        }

        /**
         * 使用显式类型注册对象（例如按接口注册）。
         *
         * @param type 类型键
         * @param object 要注册的对象
         * @param <T> 对象类型
         * @return 当前构建器
         */
        public <T> Builder register(Class<T> type, T object) {
            this.storeBuilder.register(type, object);
            return this;
        }

        /**
         * 使用键和类型注册对象（多实例模式）。
         *
         * @param key 标识此实例的键
         * @param type 类型键
         * @param object 要注册的对象
         * @param <T> 对象类型
         * @return 当前构建器
         */
        public <T> Builder register(String key, Class<T> type, T object) {
            this.storeBuilder.register(key, type, object);
            return this;
        }

        /**
         * 使用键注册对象（类型从运行时推断）。
         *
         * @param key 标识此实例的键
         * @param object 要注册的对象
         * @return 当前构建器
         */
        public Builder register(String key, Object object) {
            this.storeBuilder.register(key, object);
            return this;
        }

        /**
         * 直接设置存储列表（替换之前全部注册内容）。
         *
         * <p>主要由 {@link #merge(ToolExecutionContext...)} 在内部使用，组合多个上下文的存储。
         *
         * @param stores 按优先级排列的存储列表
         * @return 当前构建器
         */
        Builder stores(List<ContextStore> stores) {
            this.stores = stores;
            return this;
        }

        /**
         * 向列表添加单个存储。
         *
         * <p>支持添加自定义 {@link ContextStore} 实现。
         *
         * @param store 要添加的上下文存储
         * @return 当前构建器
         */
        public Builder addStore(ContextStore store) {
            if (this.stores == null) {
                this.stores = new ArrayList<>();
            }
            this.stores.add(store);
            return this;
        }

        /**
         * 构建 ToolExecutionContext。
         *
         * @return 新的 ToolExecutionContext
         */
        public ToolExecutionContext build() {
            if (stores == null) {
                // 未显式设置存储时，从已注册对象构建
                ContextStore store = storeBuilder.build();
                if (store != null
                        && !(store instanceof DefaultContextStore
                                && ((DefaultContextStore) store).getObjectMap().isEmpty())) {
                    stores = new ArrayList<>();
                    stores.add(store);
                }
            }
            return new ToolExecutionContext(this);
        }
    }
}
