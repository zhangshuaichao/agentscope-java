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
package io.agentscope.core.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 供 {@link ModelRegistry} 和模型服务商 SPI 实现使用的、与服务商无关的模型创建上下文。
 *
 * <p>此类型包含两层：
 * <ul>
 *   <li>标准字段，例如 {@code apiKey}、{@code baseUrl}、{@code endpointPath}、
 *       {@code stream} 和 {@code enableThinking}。这些字段足够通用，多个服务商均可理解；
 *       选择 {@link CachePolicy#ENABLED} 时可安全参与自动缓存标识计算。
 *   <li>服务商专用的 {@linkplain #option(String) options} 以及带类型的 {@linkplain
 *       #component(Class) components}。扩展模块可借此接收格式化器、HTTP 传输、凭据、代理和默认生成选项
 *       等高级构建配置，而不必将服务商类型加入 core。
 * </ul>
 *
 * <p>选项和组件对 core 刻意保持不透明。{@link ModelRegistry} 不会对它们做深度哈希，
 * 也不会用其 {@code hashCode()} 计算缓存标识。启用缓存且这些配置存在时，
 * 调用方必须显式提供 {@link Builder#cacheId(String)}。
 *
 * <p>【学习提示】可共享的基础配置与每租户／每请求的派生配置应区分开。
 * 缓存复用是显式设计选择，不能假定任意带凭据、代理或自定义组件的配置都能自动判定为相同。
 */
public final class ModelCreationContext {

    private static final ModelCreationContext EMPTY = builder().build();

    private final String apiKey;
    private final String baseUrl;
    private final String endpointPath;
    private final Boolean stream;
    private final Boolean enableThinking;
    private final CachePolicy cachePolicy;
    private final String cacheId;
    private final Map<String, Object> options;
    private final Map<Class<?>, Object> components;

    private ModelCreationContext(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.endpointPath = builder.endpointPath;
        this.stream = builder.stream;
        this.enableThinking = builder.enableThinking;
        this.cachePolicy = builder.cachePolicy;
        this.cacheId = builder.cacheId;
        this.options = Map.copyOf(builder.options);
        this.components = Map.copyOf(builder.components);
    }

    public static ModelCreationContext empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回用当前上下文初始化的可变建造者。
     *
     * <p>调用方可据此从共享基线派生租户或请求专属上下文，而不修改原实例。
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** 传给服务商的 API 密钥或令牌。服务商可回退为读取环境变量。 */
    public String getApiKey() {
        return apiKey;
    }

    /** 覆盖服务商基础 URL，适用于自托管或兼容 API 端点。 */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** 覆盖服务商端点路径，主要用于兼容 OpenAI 的 API。 */
    public String getEndpointPath() {
        return endpointPath;
    }

    /** 创建的模型是否默认使用流式输出。{@code null} 表示采用服务商默认值。 */
    public Boolean getStream() {
        return stream;
    }

    /** 是否启用服务商的思考／推理模式。 */
    public Boolean getEnableThinking() {
        return enableThinking;
    }

    /** {@link ModelRegistry#resolve(String, ModelCreationContext)} 的缓存策略。 */
    public CachePolicy getCachePolicy() {
        return cachePolicy;
    }

    /** 当 {@link #getCachePolicy()} 为 {@link CachePolicy#ENABLED} 时使用的显式缓存标识。 */
    public String getCacheId() {
        return cacheId;
    }

    /** 以扩展定义的名称为键的服务商专用标量或值选项。 */
    public Map<String, Object> getOptions() {
        return options;
    }

    /** 返回服务商专用选项值，或 {@code null}。 */
    public Object option(String key) {
        return options.get(key);
    }

    /** 返回转换为指定类型的服务商专用选项值，或 {@code null}。 */
    public <T> T option(String key, Class<T> type) {
        Object value = options.get(key);
        return value == null ? null : type.cast(value);
    }

    /** 以类型为键的服务商专用组件对象。 */
    public Map<Class<?>, Object> getComponents() {
        return components;
    }

    /** 按类型返回服务商专用组件，或 {@code null}。 */
    public <T> T component(Class<T> type) {
        Object value = components.get(type);
        return value == null ? null : type.cast(value);
    }

    /**
     * 返回此上下文是否没有有效设置。
     *
     * <p>空上下文保留旧版 {@link ModelRegistry#resolve(String)} 的缓存行为。
     */
    public boolean isEmpty() {
        return apiKey == null
                && baseUrl == null
                && endpointPath == null
                && stream == null
                && enableThinking == null
                && cachePolicy == CachePolicy.DEFAULT
                && cacheId == null
                && options.isEmpty()
                && components.isEmpty();
    }

    /** 返回上下文是否包含 core 无法安全生成指纹的值。 */
    public boolean hasOpaqueCacheInputs() {
        return !options.isEmpty() || !components.isEmpty();
    }

    @Override
    public String toString() {
        return "ModelCreationContext{"
                + "apiKey="
                + (apiKey == null ? "null" : "[REDACTED]")
                + ", baseUrl='"
                + baseUrl
                + '\''
                + ", endpointPath='"
                + endpointPath
                + '\''
                + ", stream="
                + stream
                + ", enableThinking="
                + enableThinking
                + ", cachePolicy="
                + cachePolicy
                + ", cacheId='"
                + cacheId
                + '\''
                + ", options="
                + options.keySet()
                + ", components="
                + components.keySet()
                + '}';
    }

    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private String endpointPath;
        private Boolean stream;
        private Boolean enableThinking;
        private CachePolicy cachePolicy = CachePolicy.DEFAULT;
        private String cacheId;
        private final Map<String, Object> options = new LinkedHashMap<>();
        private final Map<Class<?>, Object> components = new LinkedHashMap<>();

        private Builder() {}

        private Builder(ModelCreationContext source) {
            this.apiKey = source.apiKey;
            this.baseUrl = source.baseUrl;
            this.endpointPath = source.endpointPath;
            this.stream = source.stream;
            this.enableThinking = source.enableThinking;
            this.cachePolicy = source.cachePolicy;
            this.cacheId = source.cacheId;
            this.options.putAll(source.options);
            this.components.putAll(source.components);
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = trimToNull(apiKey);
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = trimToNull(baseUrl);
            return this;
        }

        public Builder endpointPath(String endpointPath) {
            this.endpointPath = trimToNull(endpointPath);
            return this;
        }

        public Builder stream(Boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder enableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        public Builder cachePolicy(CachePolicy cachePolicy) {
            this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
            return this;
        }

        public Builder cacheId(String cacheId) {
            this.cacheId = trimToNull(cacheId);
            return this;
        }

        public Builder option(String key, Object value) {
            Objects.requireNonNull(key, "key");
            if (value == null) {
                options.remove(key);
            } else {
                options.put(key, value);
            }
            return this;
        }

        public <T> Builder component(Class<T> type, T value) {
            Objects.requireNonNull(type, "type");
            if (value == null) {
                components.remove(type);
            } else {
                components.put(type, value);
            }
            return this;
        }

        public ModelCreationContext build() {
            return new ModelCreationContext(this);
        }

        private static String trimToNull(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }
}
