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
package io.agentscope.extensions.model.openai.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completion API 请求 DTO。
 *
 * <p>表示 OpenAI Chat Completion API 的请求结构。
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "model": "gpt-4o",
 *   "messages": [...],
 *   "stream": true,
 *   "temperature": 0.7,
 *   "max_tokens": 1000
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIRequest {

    /** 要使用的模型，例如 "gpt-4o"、"gpt-3.5-turbo"。 */
    @JsonProperty("model")
    private String model;

    /** 对话中的消息列表。 */
    @JsonProperty("messages")
    private List<OpenAIMessage> messages;

    /** 是否以流式返回响应。 */
    @JsonProperty("stream")
    private Boolean stream;

    /** 流式响应选项。 */
    @JsonProperty("stream_options")
    private OpenAIStreamOptions streamOptions;

    /** 采样温度，范围 0.0～2.0。 */
    @JsonProperty("temperature")
    private Double temperature;

    /** 核采样参数，范围 0.0～1.0。 */
    @JsonProperty("top_p")
    private Double topP;

    /** 最大生成 token 数。 */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /** 最大补全 token 数，作为 max_tokens 的替代字段。 */
    @JsonProperty("max_completion_tokens")
    private Integer maxCompletionTokens;

    /** 频率惩罚，范围 -2.0～2.0。 */
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    /** 出现惩罚，范围 -2.0～2.0。 */
    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    /** 停止序列。 */
    @JsonProperty("stop")
    private Object stop;

    /** 用于复现的随机种子。 */
    @JsonProperty("seed")
    private Integer seed;

    /** 要生成的补全数量。 */
    @JsonProperty("n")
    private Integer n;

    /** 可用工具列表。 */
    @JsonProperty("tools")
    private List<OpenAITool> tools;

    /**
     * 工具选择配置。
     * 可以是 "auto"、"none"、"required"，或指定工具的对象。
     */
    @JsonProperty("tool_choice")
    private Object toolChoice;

    /** 用于滥用监测的用户标识。 */
    @JsonProperty("user")
    private String user;

    /** 响应格式配置，支持 Map 和 ResponseFormat 对象。 */
    @JsonProperty("response_format")
    private Object responseFormat;

    /** 对数概率配置。 */
    @JsonProperty("logprobs")
    private Boolean logprobs;

    /** 要返回的最高对数概率数量。 */
    @JsonProperty("top_logprobs")
    private Integer topLogprobs;

    /**
     * o1 模型的推理投入程度。
     * 取值为 "low"、"medium"、"high"。
     */
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;

    /**
     * 模型用于思考/推理内容的最大 token 数。
     * 供 Qwen3、DeepSeek-R1 等推理模型限制内部推理用量。
     */
    @JsonProperty("thinking_budget")
    private Integer thinkingBudget;

    /**
     * 控制是否允许并行工具调用。
     * 设为 false 可禁用并行调用。
     */
    @JsonProperty("parallel_tool_calls")
    private Boolean parallelToolCalls;

    /**
     * 请求的服务层级。
     * 取值包括 "auto"、"default"。
     */
    @JsonProperty("service_tier")
    private String serviceTier;

    /** 是否存储对话以用于模型蒸馏。 */
    @JsonProperty("store")
    private Boolean store;

    /** 请求元数据，与 store=true 配合使用。 */
    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    /** 用于优化延迟的预测配置。 */
    @JsonProperty("prediction")
    private Map<String, Object> prediction;

    /**
     * 响应的输出模态。
     * 例如：["text", "audio"]
     */
    @JsonProperty("modalities")
    private List<String> modalities;

    /** 音频输出配置。 */
    @JsonProperty("audio")
    private Map<String, Object> audio;

    public OpenAIRequest() {}

    /**
     * 是否在响应中包含推理详情。
     * 某些模型（如 OpenRouter 上的 Gemini 3）需要此字段才能正确支持工具调用。
     */
    @JsonProperty("include_reasoning")
    private Boolean includeReasoning;

    /** 请求的附加参数。 */
    private Map<String, Object> extraParams;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<OpenAIMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<OpenAIMessage> messages) {
        this.messages = messages;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public OpenAIStreamOptions getStreamOptions() {
        return streamOptions;
    }

    public void setStreamOptions(OpenAIStreamOptions streamOptions) {
        this.streamOptions = streamOptions;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Integer getMaxCompletionTokens() {
        return maxCompletionTokens;
    }

    public void setMaxCompletionTokens(Integer maxCompletionTokens) {
        this.maxCompletionTokens = maxCompletionTokens;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public Object getStop() {
        return stop;
    }

    public void setStop(Object stop) {
        this.stop = stop;
    }

    public Integer getSeed() {
        return seed;
    }

    public void setSeed(Integer seed) {
        this.seed = seed;
    }

    public Integer getN() {
        return n;
    }

    public void setN(Integer n) {
        this.n = n;
    }

    public List<OpenAITool> getTools() {
        return tools;
    }

    public void setTools(List<OpenAITool> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public Object getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(Object responseFormat) {
        this.responseFormat = responseFormat;
    }

    public Boolean getLogprobs() {
        return logprobs;
    }

    public void setLogprobs(Boolean logprobs) {
        this.logprobs = logprobs;
    }

    public Integer getTopLogprobs() {
        return topLogprobs;
    }

    public void setTopLogprobs(Integer topLogprobs) {
        this.topLogprobs = topLogprobs;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public Integer getThinkingBudget() {
        return thinkingBudget;
    }

    public void setThinkingBudget(Integer thinkingBudget) {
        this.thinkingBudget = thinkingBudget;
    }

    public Boolean getParallelToolCalls() {
        return parallelToolCalls;
    }

    public void setParallelToolCalls(Boolean parallelToolCalls) {
        this.parallelToolCalls = parallelToolCalls;
    }

    public String getServiceTier() {
        return serviceTier;
    }

    public void setServiceTier(String serviceTier) {
        this.serviceTier = serviceTier;
    }

    public Boolean getStore() {
        return store;
    }

    public void setStore(Boolean store) {
        this.store = store;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Map<String, Object> getPrediction() {
        return prediction;
    }

    public void setPrediction(Map<String, Object> prediction) {
        this.prediction = prediction;
    }

    public List<String> getModalities() {
        return modalities;
    }

    public void setModalities(List<String> modalities) {
        this.modalities = modalities;
    }

    public Map<String, Object> getAudio() {
        return audio;
    }

    public void setAudio(Map<String, Object> audio) {
        this.audio = audio;
    }

    public Boolean getIncludeReasoning() {
        return includeReasoning;
    }

    public void setIncludeReasoning(Boolean includeReasoning) {
        this.includeReasoning = includeReasoning;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtraParams() {
        return extraParams;
    }

    @JsonAnySetter
    public void addExtraParam(String key, Object value) {
        if (this.extraParams == null) {
            this.extraParams = new HashMap<>();
        }
        this.extraParams.put(key, value);
    }

    public void setExtraParams(Map<String, Object> extraParams) {
        this.extraParams = extraParams;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OpenAIRequest request = new OpenAIRequest();

        public Builder model(String model) {
            request.setModel(model);
            return this;
        }

        public Builder messages(List<OpenAIMessage> messages) {
            request.setMessages(messages);
            return this;
        }

        public Builder stream(Boolean stream) {
            request.setStream(stream);
            return this;
        }

        public Builder streamOptions(OpenAIStreamOptions streamOptions) {
            request.setStreamOptions(streamOptions);
            return this;
        }

        public Builder temperature(Double temperature) {
            request.setTemperature(temperature);
            return this;
        }

        public Builder topP(Double topP) {
            request.setTopP(topP);
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            request.setMaxTokens(maxTokens);
            return this;
        }

        public Builder maxCompletionTokens(Integer maxCompletionTokens) {
            request.setMaxCompletionTokens(maxCompletionTokens);
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            request.setFrequencyPenalty(frequencyPenalty);
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            request.setPresencePenalty(presencePenalty);
            return this;
        }

        public Builder stop(Object stop) {
            request.setStop(stop);
            return this;
        }

        public Builder seed(Integer seed) {
            request.setSeed(seed);
            return this;
        }

        public Builder n(Integer n) {
            request.setN(n);
            return this;
        }

        public Builder tools(List<OpenAITool> tools) {
            request.setTools(tools);
            return this;
        }

        public Builder toolChoice(Object toolChoice) {
            request.setToolChoice(toolChoice);
            return this;
        }

        public Builder user(String user) {
            request.setUser(user);
            return this;
        }

        public Builder responseFormat(Map<String, Object> responseFormat) {
            request.setResponseFormat(responseFormat);
            return this;
        }

        public Builder includeReasoning(Boolean includeReasoning) {
            request.setIncludeReasoning(includeReasoning);
            return this;
        }

        public Builder extraParam(String key, Object value) {
            request.addExtraParam(key, value);
            return this;
        }

        public Builder extraParams(Map<String, Object> extraParams) {
            request.setExtraParams(extraParams);
            return this;
        }

        public OpenAIRequest build() {
            OpenAIRequest result = request;
            request = new OpenAIRequest();
            return result;
        }

        public Builder logprobs(Boolean logprobs) {
            request.setLogprobs(logprobs);
            return this;
        }

        public Builder topLogprobs(Integer topLogprobs) {
            request.setTopLogprobs(topLogprobs);
            return this;
        }

        public Builder reasoningEffort(String reasoningEffort) {
            request.setReasoningEffort(reasoningEffort);
            return this;
        }

        public Builder thinkingBudget(Integer thinkingBudget) {
            request.setThinkingBudget(thinkingBudget);
            return this;
        }

        public Builder parallelToolCalls(Boolean parallelToolCalls) {
            request.setParallelToolCalls(parallelToolCalls);
            return this;
        }

        public Builder serviceTier(String serviceTier) {
            request.setServiceTier(serviceTier);
            return this;
        }

        public Builder store(Boolean store) {
            request.setStore(store);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            request.setMetadata(metadata);
            return this;
        }

        public Builder prediction(Map<String, Object> prediction) {
            request.setPrediction(prediction);
            return this;
        }

        public Builder modalities(List<String> modalities) {
            request.setModalities(modalities);
            return this;
        }

        public Builder audio(Map<String, Object> audio) {
            request.setAudio(audio);
            return this;
        }
    }
}
