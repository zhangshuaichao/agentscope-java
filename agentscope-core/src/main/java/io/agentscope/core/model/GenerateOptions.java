/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.model;

import io.agentscope.core.formatter.ResponseFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * LLM 模型不可变的生成选项，使用建造者模式创建实例。
 *
 * <p>同时保存请求级生成参数（temperature、maxTokens 等）和连接级配置
 * （apiKey、baseUrl、modelName、stream）。
 *
 * <p>【学习提示】null 往往表示“由下层或默认配置决定”，不等同于 false、0 或空字符串。
 * 配置合并必须逐字段理解；某个参数有通用字段，也不代表所有服务商都支持它。
 */
public class GenerateOptions {
    // 连接级配置
    private final String apiKey;
    private final String baseUrl;
    private final String endpointPath;
    private final String modelName;
    private final Boolean stream;

    // 生成参数
    private final Double temperature;
    private final Double topP;
    private final Integer maxTokens;
    private final Integer maxCompletionTokens;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final Integer thinkingBudget;
    private final String reasoningEffort;
    private final ExecutionConfig executionConfig;
    private final ToolChoice toolChoice;
    private final Integer topK;
    private final Long seed;
    private final Boolean cacheControl;
    private final Boolean parallelToolCalls;
    private final ResponseFormat responseFormat;
    private final Map<String, String> additionalHeaders;
    private final Map<String, Object> additionalBodyParams;
    private final Map<String, String> additionalQueryParams;

    /**
     * 通过建造者模式创建新的 GenerateOptions 实例。
     *
     * @param builder 包含生成选项配置的建造者
     */
    private GenerateOptions(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.endpointPath = builder.endpointPath;
        this.modelName = builder.modelName;
        this.stream = builder.stream;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.maxTokens = builder.maxTokens;
        this.maxCompletionTokens = builder.maxCompletionTokens;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.presencePenalty = builder.presencePenalty;
        this.thinkingBudget = builder.thinkingBudget;
        this.reasoningEffort = builder.reasoningEffort;
        this.executionConfig = builder.executionConfig;
        this.toolChoice = builder.toolChoice;
        this.topK = builder.topK;
        this.seed = builder.seed;
        this.cacheControl = builder.cacheControl;
        this.parallelToolCalls = builder.parallelToolCalls;
        this.responseFormat = builder.responseFormat;
        this.additionalHeaders =
                builder.additionalHeaders != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalHeaders))
                        : Collections.emptyMap();
        this.additionalBodyParams =
                builder.additionalBodyParams != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalBodyParams))
                        : Collections.emptyMap();
        this.additionalQueryParams =
                builder.additionalQueryParams != null
                        ? Collections.unmodifiableMap(new HashMap<>(builder.additionalQueryParams))
                        : Collections.emptyMap();
    }

    /**
     * 获取用于认证的 API 密钥。
     *
     * <p>此密钥用于向 LLM 服务商进行认证。为 null 时，
     * 使用模型配置的默认 API 密钥（如果已配置）。
     *
     * @return API 密钥；未设置时为 null
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 获取 API 端点的基础 URL。
     *
     * <p>这是 LLM 服务商 API 的基础 URL。为 null 时，
     * 使用模型配置的默认基础 URL（如果已配置）。
     *
     * @return 基础 URL；未设置时为 null
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 获取 API 请求的端点路径。
     *
     * <p>这是 API 端点路径（例如 "/v1/chat/completions"）。
     * 为 null 时，使用模型的默认端点路径。
     *
     * <p>允许为采用不同于标准 OpenAI 路径的兼容 API 自定义端点。
     *
     * @return 端点路径；未设置时为 null
     */
    public String getEndpointPath() {
        return endpointPath;
    }

    /**
     * 获取生成时使用的模型名称。
     *
     * <p>指定要使用的模型（例如 "gpt-4"、"gpt-3.5-turbo"）。
     * 为 null 时，使用模型配置的默认名称（如果已配置）。
     *
     * @return 模型名称；未设置时为 null
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 获取是否启用流式模式。
     *
     * <p>为 true 时，响应随生成过程流式返回；为 false 时，生成完成后返回完整响应。
     * 为 null 时，使用模型配置的默认流式模式（如果已配置）。
     *
     * @return true 表示流式，false 表示非流式，未设置时为 null
     */
    public Boolean getStream() {
        return stream;
    }

    /**
     * 获取文本生成的温度参数。
     *
     * <p>较高值（例如 0.8）使输出更随机；较低值（例如 0.2）使输出更集中、更确定。
     *
     * @return 0 到 2 之间的温度值；未设置时为 null
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * 获取 top-p（核采样）参数。
     *
     * <p>通过核采样控制多样性：考虑累计概率超过 top_p 的最小词元集合。
     *
     * @return 0 到 1 之间的 top-p 值；未设置时为 null
     */
    public Double getTopP() {
        return topP;
    }

    /**
     * 获取要生成的最大词元数量。
     *
     * @return 最大词元限制；未设置时为 null
     */
    public Integer getMaxTokens() {
        return maxTokens;
    }

    /**
     * 获取要生成的最大补全词元数量。
     *
     * <p>这是 {@link #getMaxTokens()} 的替代选项，适用于支持
     * {@code max_completion_tokens} 的兼容 OpenAI API。部分服务商／模型将 {@code max_tokens} 与
     * {@code max_completion_tokens} 视为互斥；SDK 不强制这种互斥关系，而是原样转发调用方的设置。
     *
     * @return 最大补全词元限制；未设置时为 null
     */
    public Integer getMaxCompletionTokens() {
        return maxCompletionTokens;
    }

    /**
     * 获取频率惩罚。
     *
     * <p>根据词元在已生成文本中的出现频率施加惩罚，减少重复。
     * 值越高，抑制重复的作用越强。
     *
     * @return -2 到 2 之间的频率惩罚；未设置时为 null
     */
    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    /**
     * 获取存在惩罚。
     *
     * <p>对已在文本中出现的词元施加惩罚，减少重复。
     * 值越高，抑制重复的作用越强。
     *
     * @return -2 到 2 之间的存在惩罚；未设置时为 null
     */
    public Double getPresencePenalty() {
        return presencePenalty;
    }

    /**
     * 获取推理／思考内容的最大词元数量。
     *
     * <p>此参数适用于支持思考模式的模型（例如 DashScope）。
     * 设置后允许模型在生成最终答案前展示推理过程。
     *
     * @return 以词元为单位的思考预算；未设置时为 null
     */
    public Integer getThinkingBudget() {
        return thinkingBudget;
    }

    /**
     * 获取 o1 模型的推理努力级别。
     *
     * <p>控制模型投入推理的程度。有效值为 "low"、"medium" 和 "high"。
     *
     * @return 推理努力级别；未设置时为 null
     */
    public String getReasoningEffort() {
        return reasoningEffort;
    }

    /**
     * 获取超时与重试行为的执行配置。
     *
     * <p>设置后，模型按照执行配置应用超时和重试逻辑，包括超时时长、
     * 最大尝试次数、退避和错误过滤。
     *
     * @return 执行配置；未配置时为 null
     */
    public ExecutionConfig getExecutionConfig() {
        return executionConfig;
    }

    /**
     * 获取控制模型如何使用工具的工具选择配置。
     *
     * <p>控制模型可以调用工具、必须调用工具还是必须调用指定工具。
     * 为 null 时使用默认行为（auto）。
     *
     * @return 工具选择配置；未设置时为 null（默认为 auto）
     * @see ToolChoice
     */
    public ToolChoice getToolChoice() {
        return toolChoice;
    }

    /**
     * 获取 top-k 采样参数。
     *
     * <p>限制模型每一步仅考虑概率最高的 K 个词元。
     * 较低值使输出更集中，较高值允许更丰富的变化。
     *
     * @return top-k 值；未设置时为 null
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * 获取用于确定性生成的随机种子。
     *
     * <p>设置后，模型会尝试对相同输入和种子生成相同输出，从而支持结果复现。
     *
     * @return 种子值；未设置时为 null
     */
    public Long getSeed() {
        return seed;
    }

    /**
     * 获取是否为提示缓存启用缓存控制。
     *
     * <p>为 true 时，格式化器会自动将 <code>cache_control:
     * {"type": "ephemeral"}</code> 添加到系统消息和请求中的最后一条消息。
     * 在支持的服务商（例如 Anthropic、DashScope、兼容 OpenAI 的 API）上启用提示缓存，
     * 以降低延迟和成本。
     *
     * <p>用户也可通过 {@link
     * io.agentscope.core.message.MessageMetadataKeys#CACHE_CONTROL} 元数据手动标记单条消息。
     * 手动标记优先于自动策略。
     *
     * @return 启用缓存控制时为 true；未设置时为 false 或 null
     */
    public Boolean getCacheControl() {
        return cacheControl;
    }

    /**
     * 获取是否启用并行工具调用。
     *
     * <p>为 true 时，在工具使用阶段启用并行函数调用。
     *
     * @return 启用并行工具调用时为 true；未设置时为 false 或 null
     */
    public Boolean getParallelToolCalls() {
        return parallelToolCalls;
    }

    /**
     * 获取结构化输出的响应格式。
     *
     * <p>设置后，模型返回符合指定格式（例如 JSON schema）的响应，
     * 从而支持原生结构化输出，无需 {@code generate_response} 这类合成工具。
     *
     * @return 响应格式；未设置时为 null
     */
    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    /**
     * 获取要加入 API 请求的附加 HTTP 请求头。
     *
     * <p>调用 API 时将这些请求头与默认请求头合并，
     * 可用于传递自定义认证、追踪或服务商专用请求头。
     *
     * @return 附加请求头的不可修改映射；未设置时为空
     */
    public Map<String, String> getAdditionalHeaders() {
        return additionalHeaders;
    }

    /**
     * 获取要加入请求体的附加参数。
     *
     * <p>这些参数会合并到 API 请求体，支持标准字段未覆盖的服务商专用选项。
     *
     * @return 附加请求体参数的不可修改映射；未设置时为空
     */
    public Map<String, Object> getAdditionalBodyParams() {
        return additionalBodyParams;
    }

    /**
     * 获取要加入 API 请求的附加查询参数。
     *
     * <p>这些参数以查询字符串形式追加到 API 请求 URL。
     *
     * @return 附加查询参数的不可修改映射；未设置时为空
     */
    public Map<String, String> getAdditionalQueryParams() {
        return additionalQueryParams;
    }

    /**
     * 创建新的 GenerateOptions 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 合并两个 GenerateOptions 实例，优先使用主选项。
     *
     * <p>逐参数合并：主选项中该值非 null 时使用主值，否则使用回退值。
     * 这样可以正确叠加不同来源的选项，例如让请求级选项覆盖默认选项。
     *
     * <p><b>合并行为：</b>
     * <ul>
     *   <li>普通字段（temperature、topP 等）：primary != null ? primary : fallback</li>
     *   <li>映射字段（additionalHeaders 等）：合并两个映射，主选项值覆盖回退值</li>
     *   <li>primary 为 null 时直接返回 fallback</li>
     *   <li>fallback 为 null 时直接返回 primary</li>
     * </ul>
     *
     * <p><b>示例：</b>
     * <pre>{@code
     * ExecutionConfig defaultExecConfig = ExecutionConfig.builder()
     *     .timeout(Duration.ofMinutes(5))
     *     .maxAttempts(3)
     *     .build();
     *
     * GenerateOptions defaults = GenerateOptions.builder()
     *     .temperature(0.7)
     *     .executionConfig(defaultExecConfig)
     *     .build();
     *
     * ExecutionConfig customExecConfig = ExecutionConfig.builder()
     *     .timeout(Duration.ofSeconds(30))
     *     .build();
     *
     * GenerateOptions perRequest = GenerateOptions.builder()
     *     .executionConfig(customExecConfig)
     *     .build();
     *
     * // 结果：temperature=0.7，executionConfig 中 timeout=30s、maxAttempts=3
     * GenerateOptions merged = GenerateOptions.mergeOptions(perRequest, defaults);
     * }</pre>
     *
     * @param primary 主选项（较高优先级）
     * @param fallback 回退选项（较低优先级）
     * @return 合并后的选项；两者均为 null 时返回 null
     */
    public static GenerateOptions mergeOptions(GenerateOptions primary, GenerateOptions fallback) {
        if (primary == null) {
            return fallback;
        }
        if (fallback == null) {
            return primary;
        }

        Builder builder = builder();
        builder.apiKey(primary.apiKey != null ? primary.apiKey : fallback.apiKey);
        builder.baseUrl(primary.baseUrl != null ? primary.baseUrl : fallback.baseUrl);
        builder.endpointPath(
                primary.endpointPath != null ? primary.endpointPath : fallback.endpointPath);
        builder.modelName(primary.modelName != null ? primary.modelName : fallback.modelName);
        builder.stream(primary.stream != null ? primary.stream : fallback.stream);
        builder.temperature(
                primary.temperature != null ? primary.temperature : fallback.temperature);
        builder.topP(primary.topP != null ? primary.topP : fallback.topP);
        builder.maxTokens(primary.maxTokens != null ? primary.maxTokens : fallback.maxTokens);
        builder.maxCompletionTokens(
                primary.maxCompletionTokens != null
                        ? primary.maxCompletionTokens
                        : fallback.maxCompletionTokens);
        builder.frequencyPenalty(
                primary.frequencyPenalty != null
                        ? primary.frequencyPenalty
                        : fallback.frequencyPenalty);
        builder.presencePenalty(
                primary.presencePenalty != null
                        ? primary.presencePenalty
                        : fallback.presencePenalty);
        builder.thinkingBudget(
                primary.thinkingBudget != null ? primary.thinkingBudget : fallback.thinkingBudget);
        builder.reasoningEffort(
                primary.reasoningEffort != null
                        ? primary.reasoningEffort
                        : fallback.reasoningEffort);
        builder.executionConfig(
                ExecutionConfig.mergeConfigs(primary.executionConfig, fallback.executionConfig));
        builder.toolChoice(primary.toolChoice != null ? primary.toolChoice : fallback.toolChoice);
        builder.topK(primary.topK != null ? primary.topK : fallback.topK);
        builder.seed(primary.seed != null ? primary.seed : fallback.seed);
        builder.cacheControl(
                primary.cacheControl != null ? primary.cacheControl : fallback.cacheControl);
        builder.parallelToolCalls(
                primary.parallelToolCalls != null
                        ? primary.parallelToolCalls
                        : fallback.parallelToolCalls);
        builder.responseFormat(
                primary.responseFormat != null ? primary.responseFormat : fallback.responseFormat);

        // 合并映射字段：先放入回退值，再用主选项值覆盖
        mergeMaps(fallback.additionalHeaders, primary.additionalHeaders, builder::additionalHeader);
        mergeMaps(
                fallback.additionalBodyParams,
                primary.additionalBodyParams,
                builder::additionalBodyParam);
        mergeMaps(
                fallback.additionalQueryParams,
                primary.additionalQueryParams,
                builder::additionalQueryParam);

        return builder.build();
    }

    private static <V> void mergeMaps(
            Map<String, V> fallback, Map<String, V> primary, BiConsumer<String, V> adder) {
        if (fallback != null && !fallback.isEmpty()) {
            for (Map.Entry<String, V> entry : fallback.entrySet()) {
                adder.accept(entry.getKey(), entry.getValue());
            }
        }
        if (primary != null && !primary.isEmpty()) {
            for (Map.Entry<String, V> entry : primary.entrySet()) {
                adder.accept(entry.getKey(), entry.getValue());
            }
        }
    }

    public static class Builder {
        // 连接级配置
        private String apiKey;
        private String baseUrl;
        private String endpointPath;
        private String modelName;
        private Boolean stream;

        // 生成参数
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private Integer maxCompletionTokens;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private Integer thinkingBudget;
        private String reasoningEffort;
        private ExecutionConfig executionConfig;
        private ToolChoice toolChoice;
        private Integer topK;
        private Long seed;
        private Boolean cacheControl;
        private Boolean parallelToolCalls;
        private ResponseFormat responseFormat;
        private Map<String, String> additionalHeaders;
        private Map<String, Object> additionalBodyParams;
        private Map<String, String> additionalQueryParams;

        /**
         * 设置用于认证的 API 密钥。
         *
         * @param apiKey API 密钥
         * @return 当前建造者实例
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置 API 端点的基础 URL。
         *
         * @param baseUrl 基础 URL
         * @return 当前建造者实例
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置 API 请求的端点路径。
         *
         * <p>允许为使用不同于标准 OpenAI 路径的兼容 API 自定义端点，
         * 例如 "/v4/chat/completions"、"/api/v1/llm/chat" 等。
         * 为 null 时使用默认端点路径。
         *
         * @param endpointPath 端点路径（例如 "/v1/chat/completions"）
         * @return 当前建造者实例
         */
        public Builder endpointPath(String endpointPath) {
            this.endpointPath = endpointPath;
            return this;
        }

        /**
         * 设置生成时使用的模型名称。
         *
         * @param modelName 模型名称（例如 "gpt-4"、"gpt-3.5-turbo"）
         * @return 当前建造者实例
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置是否启用流式模式。
         *
         * @param stream true 表示流式，false 表示非流式
         * @return 当前建造者实例
         */
        public Builder stream(Boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * 设置文本生成的温度参数。
         *
         * <p>较高值（例如 0.8）使输出更随机；较低值（例如 0.2）使输出更集中、更确定。
         *
         * @param temperature 0 到 2 之间的温度值
         * @return 当前建造者实例
         */
        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * 设置 top-p（核采样）参数。
         *
         * <p>通过核采样控制多样性：考虑累计概率超过 top_p 的最小词元集合。
         *
         * @param topP 0 到 1 之间的 top-p 值
         * @return 当前建造者实例
         */
        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * 设置要生成的最大词元数量。
         *
         * @param maxTokens 最大词元限制
         * @return 当前建造者实例
         */
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * 设置要生成的最大补全词元数量。
         *
         * <p>这是 {@link #maxTokens(Integer)} 的替代选项，适用于支持
         * {@code max_completion_tokens} 的兼容 OpenAI API。此建造者不强制它与
         * {@code maxTokens} 互斥；可以同时设置，支持两个字段的格式化器会原样转发。
         *
         * @param maxCompletionTokens 最大补全词元限制
         * @return 当前建造者实例
         */
        public Builder maxCompletionTokens(Integer maxCompletionTokens) {
            this.maxCompletionTokens = maxCompletionTokens;
            return this;
        }

        /**
         * 设置频率惩罚。
         *
         * <p>根据词元在已生成文本中的出现频率施加惩罚，减少重复。
         * 值越高，抑制重复的作用越强。
         *
         * @param frequencyPenalty -2 到 2 之间的频率惩罚
         * @return 当前建造者实例
         */
        public Builder frequencyPenalty(Double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /**
         * 设置存在惩罚。
         *
         * <p>对已在文本中出现的词元施加惩罚，减少重复。
         * 值越高，抑制重复的作用越强。
         *
         * @param presencePenalty -2 到 2 之间的存在惩罚
         * @return 当前建造者实例
         */
        public Builder presencePenalty(Double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /**
         * 设置思考预算（推理／思考内容的最大词元数量）。
         *
         * <p>此参数适用于支持思考模式的模型。设置后模型会在最终答案之前展示推理过程；
         * 部分模型可能因设置此参数而自动启用思考模式。
         *
         * @param thinkingBudget 思考内容的最大词元数量
         * @return 当前建造者
         */
        public Builder thinkingBudget(Integer thinkingBudget) {
            this.thinkingBudget = thinkingBudget;
            return this;
        }

        /**
         * 设置 o1 模型的推理努力级别。
         *
         * <p>控制模型投入推理的程度。有效值为 "low"、"medium" 和 "high"。
         *
         * @param reasoningEffort 推理努力级别
         * @return 当前建造者
         */
        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        /**
         * 设置超时与重试行为的执行配置。
         *
         * <p>配置后，模型 API 调用会按照执行配置应用超时和重试逻辑，
         * 包括超时时长、最大尝试次数、退避和错误过滤。
         *
         * @param executionConfig 执行配置；null 表示不设置
         * @return 当前建造者实例
         */
        public Builder executionConfig(ExecutionConfig executionConfig) {
            this.executionConfig = executionConfig;
            return this;
        }

        /**
         * 设置控制模型如何使用工具的工具选择配置。
         *
         * <p>控制模型可以调用工具、必须调用工具还是必须调用指定工具：
         * <ul>
         *   <li>{@link ToolChoice.Auto} — 由模型决定（null 时的默认值）</li>
         *   <li>{@link ToolChoice.None} — 禁止工具调用</li>
         *   <li>{@link ToolChoice.Required} — 至少调用一个工具</li>
         *   <li>{@link ToolChoice.Specific} — 强制调用指定工具</li>
         * </ul>
         *
         * @param toolChoice 工具选择配置；null 表示默认值（auto）
         * @return 当前建造者实例
         * @see ToolChoice
         */
        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        /**
         * 设置 top-k 采样参数。
         *
         * <p>限制模型每一步仅考虑概率最高的 K 个词元。
         * 较低值使输出更集中，较高值允许更丰富的变化。
         *
         * @param topK top-k 值
         * @return 当前建造者实例
         */
        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        /**
         * 设置用于确定性生成的随机种子。
         *
         * <p>设置后，模型会尝试对相同输入和种子生成相同输出，从而支持结果复现。
         *
         * @param seed 种子值
         * @return 当前建造者实例
         */
        public Builder seed(Long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * 设置是否为提示缓存启用缓存控制。
         *
         * <p>为 true 时，格式化器会自动将 <code>cache_control:
         * {"type": "ephemeral"}</code> 添加到系统消息和请求中的最后一条消息。
         *
         * @param cacheControl true 表示启用缓存控制，false 表示禁用
         * @return 当前建造者实例
         */
        public Builder cacheControl(Boolean cacheControl) {
            this.cacheControl = cacheControl;
            return this;
        }

        /**
         * 设置是否在工具使用阶段启用并行函数调用。
         *
         * @param parallelToolCalls true 表示启用并行工具调用，false 表示禁用
         * @return 当前建造者实例
         */
        public Builder parallelToolCalls(Boolean parallelToolCalls) {
            this.parallelToolCalls = parallelToolCalls;
            return this;
        }

        /**
         * 设置结构化输出的响应格式。
         *
         * @param responseFormat 响应格式配置
         * @return 当前建造者实例
         */
        public Builder responseFormat(ResponseFormat responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        /**
         * 添加要加入 API 请求的附加 HTTP 请求头。
         *
         * @param key 请求头名称
         * @param value 请求头值
         * @return 当前建造者实例
         */
        public Builder additionalHeader(String key, String value) {
            if (this.additionalHeaders == null) {
                this.additionalHeaders = new HashMap<>();
            }
            this.additionalHeaders.put(key, value);
            return this;
        }

        /**
         * 设置要加入 API 请求的全部附加 HTTP 请求头。
         *
         * @param headers 请求头映射
         * @return 当前建造者实例
         */
        public Builder additionalHeaders(Map<String, String> headers) {
            this.additionalHeaders = headers != null ? new HashMap<>(headers) : null;
            return this;
        }

        /**
         * 添加要加入请求体的附加参数。
         *
         * @param key 参数名称
         * @param value 参数值
         * @return 当前建造者实例
         */
        public Builder additionalBodyParam(String key, Object value) {
            if (this.additionalBodyParams == null) {
                this.additionalBodyParams = new HashMap<>();
            }
            this.additionalBodyParams.put(key, value);
            return this;
        }

        /**
         * 设置要加入请求体的全部附加参数。
         *
         * @param params 参数映射
         * @return 当前建造者实例
         */
        public Builder additionalBodyParams(Map<String, Object> params) {
            this.additionalBodyParams = params != null ? new HashMap<>(params) : null;
            return this;
        }

        /**
         * 添加要加入 API 请求的附加查询参数。
         *
         * @param key 参数名称
         * @param value 参数值
         * @return 当前建造者实例
         */
        public Builder additionalQueryParam(String key, String value) {
            if (this.additionalQueryParams == null) {
                this.additionalQueryParams = new HashMap<>();
            }
            this.additionalQueryParams.put(key, value);
            return this;
        }

        /**
         * 设置要加入 API 请求的全部附加查询参数。
         *
         * @param params 参数映射
         * @return 当前建造者实例
         */
        public Builder additionalQueryParams(Map<String, String> params) {
            this.additionalQueryParams = params != null ? new HashMap<>(params) : null;
            return this;
        }

        /**
         * 使用已设置的值构建新的 GenerateOptions 实例。
         *
         * @return 新的 GenerateOptions 实例
         */
        public GenerateOptions build() {
            return new GenerateOptions(this);
        }
    }
}
