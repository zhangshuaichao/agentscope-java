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
package io.agentscope.core.agent;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ContextStore;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolRequestConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 智能体运行的单次调用元数据：会话作用域字段、线程安全的属性容器，
 * 以及可选的 {@link ToolExecutionContext}（工具 POJO／依赖注入层）。
 *
 * <p>属性不会持久化。在单次 {@code call} 期间，钩子和工具可以读取、更新同一个实例。
 */
public class RuntimeContext {

    private static final String TYPED_DEFAULT_KEY = "";

    private final String sessionId;
    private final String userId;

    /**
     * 单次调用关联 ID：可通过 {@link Builder#runId(String)} 显式设置（唯一性由调用方负责），
     * 也可自动生成；{@link Builder#from} 会复制它，使派生上下文（子智能体链）保持关联。绝不为 {@code null}。
     */
    private final String runId;

    /**
     * 活动 {@code (userId, sessionId)} 槽位对应的调用作用域 {@link AgentState}。
     * 由智能体在调用入口设置一次，供调用期间需要实时会话状态的中间件／工具读取，
     * 而非使用并发场景下不具备调用作用域的 {@code agent.getAgentState()}。
     * 调用之外为 {@code null}。
     */
    private volatile AgentState agentState;

    /**
     * 以字符串为键的额外属性（兼容旧接口及通用扩展）。
     */
    private final ConcurrentMap<String, Object> stringAttributes;

    /**
     * 类型化层：class -&gt; (key -&gt; value)。单例类型访问请使用 {@link #TYPED_DEFAULT_KEY}。
     */
    private final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> typedAttributes;

    private final ToolExecutionContext toolExecutionContext;

    /**
     * 单次调用的工具请求配置（不可变）。携带本次调用的工具差异：外部注入的工具（仅 schema）及合并模式。
     * 与智能体共享工具集组合，形成一次调用可见的工具集合。{@code null} 表示原样使用共享工具集。
     */
    private ToolRequestConfig toolRequestConfig;

    private RuntimeContext(Builder builder) {
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        // 在此生成（不缓存在构建器中），以便复用同一 Builder 时得到不同 ID。
        this.runId =
                builder.runId == null || builder.runId.isBlank() ? generateRunId() : builder.runId;
        this.stringAttributes = new ConcurrentHashMap<>();
        this.typedAttributes = new ConcurrentHashMap<>();
        this.toolExecutionContext = builder.toolExecutionContext;
        this.agentState = builder.agentState;
        this.toolRequestConfig = builder.toolRequestConfig;
        if (builder.stringExtras != null) {
            this.stringAttributes.putAll(builder.stringExtras);
        }
        for (Map.Entry<Class<?>, Map<String, Object>> e : builder.typedValues.entrySet()) {
            Class<?> type = e.getKey();
            Map<String, Object> values = e.getValue();
            if (type == null || values == null || values.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Object> typedEntry : values.entrySet()) {
                if (typedEntry.getKey() == null || typedEntry.getValue() == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Class<Object> typedClass = (Class<Object>) type;
                putValue(typedEntry.getKey(), typedClass, typedEntry.getValue());
            }
        }
    }

    /**
     * 浅层、可变的空上下文（会话字段为 null、属性映射为空、不含工具上下文）。
     */
    public static RuntimeContext empty() {
        return new Builder().build();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    /**
     * 返回日志、事件和追踪使用的稳定单次调用关联 ID，也覆盖由此上下文派生的子智能体链。
     * 通过 {@code prepareRun}／{@code prepareCall} 创建句柄时，与 {@link AgentRun#runId()} 对齐。
     * 绝不为 {@code null}。
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 生成新的不透明 runId（32 位十六进制字符串）；默认 ID 的唯一生成入口。
     */
    public static String generateRunId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 返回本次运行调用作用域的 {@link AgentState}；不在活动 {@code call()} 内时为 {@code null}。
     * 中间件和工具应优先使用此方法而非 {@code agent.getAgentState()}，确保并发时使用正确会话的状态。
     */
    public AgentState getAgentState() {
        return agentState;
    }

    /**
     * 安装调用作用域的 {@link AgentState}。由智能体在调用入口调用，不属于公共工具／中间件契约。
     */
    public void setAgentState(AgentState agentState) {
        this.agentState = agentState;
    }

    /**
     * 解析当前调用的实时 {@link AgentState}，优先使用 {@code ctx} 携带的调用作用域状态（并发安全），
     * 只有上下文未携带状态时才回退到 {@code fallbackAgent} 的状态。
     * 中间件和工具应使用此方法，而非直接调用并发下不具备调用作用域的 {@code agent.getAgentState()}。
     *
     * @param ctx 单次调用的运行时上下文（可以为 {@code null}）
     * @param fallbackAgent 用于回退的智能体（可以为 {@code null}）
     * @return 解析出的 {@link AgentState}；两个来源均未提供时为 {@code null}
     */
    public static AgentState resolveAgentState(RuntimeContext ctx, Agent fallbackAgent) {
        AgentState s = ctx != null ? ctx.getAgentState() : null;
        if (s != null) {
            return s;
        }
        return fallbackAgent != null ? fallbackAgent.getAgentState() : null;
    }

    /**
     * 返回构建时提供的工具执行上下文（如果存在）。
     *
     * <p>不包含运行时属性投影；请使用 {@link #asToolExecutionContext()}。
     */
    public ToolExecutionContext getToolExecutionContext() {
        return toolExecutionContext;
    }

    /**
     * 返回单次调用的工具请求配置；未提供时为 {@code null}（执行引擎随后原样使用智能体共享工具集）。
     */
    public ToolRequestConfig getToolRequestConfig() {
        return toolRequestConfig;
    }

    /**
     * 安装单次调用的工具请求配置。适用于需要按调用改变工具集合、又不修改智能体共享工具集的调用方（并发安全）。
     * 设为 {@code null} 则原样使用共享工具集。
     */
    public void setToolRequestConfig(ToolRequestConfig toolRequestConfig) {
        this.toolRequestConfig = toolRequestConfig;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        if (key == null) {
            return null;
        }
        return (T) stringAttributes.get(key);
    }

    public void put(String key, Object value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            stringAttributes.remove(key);
        } else {
            stringAttributes.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        if (type == null) {
            return null;
        }
        T v = getValue(TYPED_DEFAULT_KEY, type);
        if (v != null) {
            return v;
        }
        // 允许访问当前 RuntimeContext 自身
        if (type == RuntimeContext.class) {
            return (T) this;
        }
        return getAssignableValue(TYPED_DEFAULT_KEY, type);
    }

    public <T> void put(Class<T> type, T value) {
        if (type == null) {
            return;
        }
        if (value == null) {
            removeTyped(type, TYPED_DEFAULT_KEY);
        } else {
            putValue(TYPED_DEFAULT_KEY, type, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        if (key == null || type == null) {
            return null;
        }
        T v = getValue(key, type);
        if (v != null) {
            return v;
        }
        if (TYPED_DEFAULT_KEY.equals(key) && type == RuntimeContext.class) {
            return (T) this;
        }
        T assignable = getAssignableValue(key, type);
        if (assignable != null) {
            return assignable;
        }
        Object fromString = stringAttributes.get(key);
        if (type.isInstance(fromString)) {
            return (T) fromString;
        }
        return null;
    }

    public <T> void put(String key, Class<T> type, T value) {
        if (key == null || type == null) {
            return;
        }
        if (value == null) {
            removeTyped(type, key);
        } else {
            putValue(key, type, value);
        }
    }

    public static Builder builder(RuntimeContext source) {
        return new Builder().from(source);
    }

    /**
     * 以字符串为键的属性视图；修改返回的映射会影响此上下文。
     *
     * <p>不包含类型化的 {@link #get(Class)} 值；请使用基于类型的访问方法。
     */
    public Map<String, Object> getExtra() {
        return stringAttributes;
    }

    private <T> void putValue(String key, Class<T> type, Object value) {
        typedAttributes.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    private <T> T getValue(String key, Class<T> type) {
        Map<String, Object> m = typedAttributes.get(type);
        if (m == null) {
            return null;
        }
        Object o = m.get(key);
        if (o == null) {
            return null;
        }
        return type.isInstance(o) ? type.cast(o) : null;
    }

    private <T> T getAssignableValue(String key, Class<T> type) {
        if (type == null) {
            return null;
        }
        T candidate = null;
        Class<?> candidateType = null;
        for (Map.Entry<Class<?>, ConcurrentMap<String, Object>> entry :
                typedAttributes.entrySet()) {
            Class<?> storedType = entry.getKey();
            if (storedType == null || !type.isAssignableFrom(storedType)) {
                continue;
            }
            Map<String, Object> values = entry.getValue();
            if (values == null) {
                continue;
            }
            Object o = values.get(key);
            if (o == null || !type.isInstance(o)) {
                continue;
            }
            if (candidate == null) {
                candidate = type.cast(o);
                candidateType = storedType;
                continue;
            }
            if (candidateType.equals(storedType)) {
                continue;
            }
            if (candidateType.isAssignableFrom(storedType)) {
                candidate = type.cast(o);
                candidateType = storedType;
            }
        }
        return candidate;
    }

    private <T> void removeTyped(Class<T> type, String key) {
        Map<String, Object> m = typedAttributes.get(type);
        if (m == null) {
            return;
        }
        m.remove(key);
        if (m.isEmpty()) {
            typedAttributes.remove(type);
        }
    }

    /**
     * 将此上下文的数据合并为供工具调用使用的 {@link ToolExecutionContext}。
     *
     * <p>顺序：首先注册并暴露当前实例（在 {@link ToolExecutionContext#merge} 中优先级最高），
     * 然后是用于类型化和字符串属性的 {@link ContextStore}，最后是嵌套
     * {@link #getToolExecutionContext()} 中的存储（如果存在）。
     */
    public ToolExecutionContext asToolExecutionContext() {
        ToolExecutionContext.Builder b = ToolExecutionContext.builder();
        b.addStore(new DefaultMutableContextStore(this));
        if (toolExecutionContext != null) {
            for (ContextStore s : toolExecutionContext.getStores()) {
                if (s != null) {
                    b.addStore(s);
                }
            }
        }
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private String userId;
        private String runId;
        private Map<String, Object> stringExtras;
        private final Map<Class<?>, Map<String, Object>> typedValues = new HashMap<>();
        private ToolExecutionContext toolExecutionContext;
        private AgentState agentState;
        private ToolRequestConfig toolRequestConfig;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * 显式设置单次调用关联 ID（例如编排追踪 ID）；唯一性由调用方负责。
         * 覆盖 {@link #from(RuntimeContext)} 复制的值。未设置或为空白时在 {@code build()} 时生成。
         */
        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder agentState(AgentState agentState) {
            this.agentState = agentState;
            return this;
        }

        public Builder put(String key, Object value) {
            if (this.stringExtras == null) {
                this.stringExtras = new ConcurrentHashMap<>();
            }
            this.stringExtras.put(key, value);
            return this;
        }

        public Builder putAll(Map<String, Object> extras) {
            if (extras == null || extras.isEmpty()) {
                return this;
            }
            if (this.stringExtras == null) {
                this.stringExtras = new ConcurrentHashMap<>();
            }
            this.stringExtras.putAll(extras);
            return this;
        }

        public <T> Builder put(Class<T> type, T value) {
            return put(TYPED_DEFAULT_KEY, type, value);
        }

        public <T> Builder put(String key, Class<T> type, T value) {
            if (key == null || type == null || value == null) {
                return this;
            }
            this.typedValues.computeIfAbsent(type, k -> new HashMap<>()).put(key, value);
            return this;
        }

        public Builder from(RuntimeContext source) {
            if (source == null) {
                return this;
            }
            this.sessionId = source.sessionId;
            this.userId = source.userId;
            // 派生上下文有意共享来源的 runId（agent_spawn 调用链）；
            // 后续 runId(x) 调用可以覆盖它。
            this.runId = source.runId;
            this.agentState = source.agentState;
            this.toolExecutionContext = source.toolExecutionContext;
            this.toolRequestConfig = source.toolRequestConfig;
            if (!source.stringAttributes.isEmpty()) {
                this.stringExtras = new ConcurrentHashMap<>(source.stringAttributes);
            }
            for (Map.Entry<Class<?>, ConcurrentMap<String, Object>> entry :
                    source.typedAttributes.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                this.typedValues.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
            return this;
        }

        /**
         * 嵌套 {@link ToolExecutionContext}（例如智能体构建器级工具依赖注入），
         * 它在 {@link #asToolExecutionContext()} 中的可见优先级低于运行时属性。
         */
        public Builder toolExecutionContext(ToolExecutionContext toolExecutionContext) {
            this.toolExecutionContext = toolExecutionContext;
            return this;
        }

        /**
         * 安装单次调用的工具请求配置。设置后，执行引擎在本次调用期间将该不可变工具差异与共享工具集组合（并发安全）。
         * {@code null}（默认值）表示原样使用共享工具集。
         */
        public Builder toolRequestConfig(ToolRequestConfig toolRequestConfig) {
            this.toolRequestConfig = toolRequestConfig;
            return this;
        }

        public RuntimeContext build() {
            return new RuntimeContext(this);
        }
    }

    /**
     * 供工具调用链使用的 {@link RuntimeContext} 合并视图：先检查类型化属性，
     * 再为旧式 {@link #get(String)} 键检查字符串映射，最后委托给底层存储。
     */
    private static final class DefaultMutableContextStore implements ContextStore {

        private final RuntimeContext runtimeContext;

        private DefaultMutableContextStore(RuntimeContext runtimeContext) {
            this.runtimeContext = runtimeContext;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String key, Class<T> type) {
            return runtimeContext.get(key, type);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Class<T> type) {
            return runtimeContext.get(type);
        }

        @Override
        public boolean contains(String key, Class<?> type) {
            return get(key, type) != null;
        }

        @Override
        public boolean contains(Class<?> type) {
            return get(type) != null;
        }
    }
}
