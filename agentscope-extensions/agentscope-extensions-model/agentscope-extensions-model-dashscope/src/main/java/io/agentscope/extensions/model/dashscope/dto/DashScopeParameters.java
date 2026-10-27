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
package io.agentscope.extensions.model.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.formatter.ResponseFormat;
import java.util.List;

/**
 * DashScope API 参数 DTO。
 *
 * <p>包含 API 请求的生成参数。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeParameters {

    /**
     * 结果格式，聊天补全应为 "message"。
     */
    @JsonProperty("result_format")
    private String resultFormat = "message";

    /**
     * 流式响应是否采用增量输出。
     */
    @JsonProperty("incremental_output")
    private Boolean incrementalOutput;

    /**
     * 采样温度（0.0–2.0）。
     */
    @JsonProperty("temperature")
    private Double temperature;

    /**
     * 核采样参数（0.0–1.0）。
     */
    @JsonProperty("top_p")
    private Double topP;

    /**
     * Top-K 采样参数。
     */
    @JsonProperty("top_k")
    private Integer topK;

    /**
     * 最大生成 token 数。
     */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /**
     * 停止序列。
     */
    @JsonProperty("stop")
    private List<String> stop;

    /**
     * 启用思考/推理模式。
     */
    @JsonProperty("enable_thinking")
    private Boolean enableThinking;

    /**
     * 思考 token 预算。
     */
    @JsonProperty("thinking_budget")
    private Integer thinkingBudget;

    /**
     * 推理力度级别（例如 "low"、"medium"、"high"、"max"、"xhigh"）。
     */
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;

    /**
     * 启用搜索模式。
     */
    @JsonProperty("enable_search")
    private Boolean enableSearch;

    /**
     * 网页搜索策略。
     */
    @JsonProperty("search_options")
    private DashScopeSearchOptions searchOptions;

    /**
     * 启用代码解释器模式。
     */
    @JsonProperty("enable_code_interpreter")
    private Boolean enableCodeInterpreter;

    /**
     * 可用工具列表。
     */
    @JsonProperty("tools")
    private List<DashScopeTool> tools;

    /**
     * 工具选择配置，可以为 "auto"、"none" 或指定工具对象。
     */
    @JsonProperty("tool_choice")
    private Object toolChoice;

    /**
     * 是否启用并行工具调用。
     */
    @JsonProperty("parallel_tool_calls")
    private Boolean parallelToolCalls;

    /**
     * 用于复现的随机种子。
     */
    @JsonProperty("seed")
    private Integer seed;

    /**
     * 频率惩罚（-2.0 至 2.0）。
     */
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    /**
     * 存在惩罚（-2.0 至 2.0）。
     */
    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    /**
     * 重复惩罚（0.0 至 2.0）。
     */
    @JsonProperty("repetition_penalty")
    private Double repetitionPenalty;

    /**
     * LLM 响应格式配置。
     */
    @JsonProperty("response_format")
    ResponseFormat responseFormat;

    public DashScopeParameters() {}

    public String getResultFormat() {
        return resultFormat;
    }

    public void setResultFormat(String resultFormat) {
        this.resultFormat = resultFormat;
    }

    public Boolean getIncrementalOutput() {
        return incrementalOutput;
    }

    public void setIncrementalOutput(Boolean incrementalOutput) {
        this.incrementalOutput = incrementalOutput;
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

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public List<String> getStop() {
        return stop;
    }

    public void setStop(List<String> stop) {
        this.stop = stop;
    }

    public Boolean getEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(Boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    public Integer getThinkingBudget() {
        return thinkingBudget;
    }

    public void setThinkingBudget(Integer thinkingBudget) {
        this.thinkingBudget = thinkingBudget;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public Boolean getEnableSearch() {
        return enableSearch;
    }

    public void setEnableSearch(Boolean enableSearch) {
        this.enableSearch = enableSearch;
    }

    public DashScopeSearchOptions getSearchOptions() {
        return searchOptions;
    }

    public void setSearchOptions(DashScopeSearchOptions searchOptions) {
        this.searchOptions = searchOptions;
    }

    public Boolean getEnableCodeInterpreter() {
        return enableCodeInterpreter;
    }

    public void setEnableCodeInterpreter(Boolean enableCodeInterpreter) {
        this.enableCodeInterpreter = enableCodeInterpreter;
    }

    public List<DashScopeTool> getTools() {
        return tools;
    }

    public void setTools(List<DashScopeTool> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public Boolean getParallelToolCalls() {
        return parallelToolCalls;
    }

    public void setParallelToolCalls(Boolean parallelToolCalls) {
        this.parallelToolCalls = parallelToolCalls;
    }

    public Integer getSeed() {
        return seed;
    }

    public void setSeed(Integer seed) {
        this.seed = seed;
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

    public Double getRepetitionPenalty() {
        return repetitionPenalty;
    }

    public void setRepetitionPenalty(Double repetitionPenalty) {
        this.repetitionPenalty = repetitionPenalty;
    }

    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(ResponseFormat responseFormat) {
        this.responseFormat = responseFormat;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DashScopeParameters params = new DashScopeParameters();

        public Builder resultFormat(String resultFormat) {
            params.setResultFormat(resultFormat);
            return this;
        }

        public Builder incrementalOutput(Boolean incrementalOutput) {
            params.setIncrementalOutput(incrementalOutput);
            return this;
        }

        public Builder temperature(Double temperature) {
            params.setTemperature(temperature);
            return this;
        }

        public Builder topP(Double topP) {
            params.setTopP(topP);
            return this;
        }

        public Builder topK(Integer topK) {
            params.setTopK(topK);
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            params.setMaxTokens(maxTokens);
            return this;
        }

        public Builder stop(List<String> stop) {
            params.setStop(stop);
            return this;
        }

        public Builder enableThinking(Boolean enableThinking) {
            params.setEnableThinking(enableThinking);
            return this;
        }

        public Builder thinkingBudget(Integer thinkingBudget) {
            params.setThinkingBudget(thinkingBudget);
            return this;
        }

        public Builder reasoningEffort(String reasoningEffort) {
            params.setReasoningEffort(reasoningEffort);
            return this;
        }

        public Builder enableSearch(Boolean enableSearch) {
            params.setEnableSearch(enableSearch);
            return this;
        }

        public Builder searchOptions(DashScopeSearchOptions searchOptions) {
            params.setSearchOptions(searchOptions);
            return this;
        }

        public Builder enableCodeInterpreter(Boolean enableCodeInterpreter) {
            params.setEnableCodeInterpreter(enableCodeInterpreter);
            return this;
        }

        public Builder tools(List<DashScopeTool> tools) {
            params.setTools(tools);
            return this;
        }

        public Builder toolChoice(Object toolChoice) {
            params.setToolChoice(toolChoice);
            return this;
        }

        public Builder parallelToolCalls(Boolean parallelToolCalls) {
            params.setParallelToolCalls(parallelToolCalls);
            return this;
        }

        public Builder seed(Integer seed) {
            params.setSeed(seed);
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            params.setFrequencyPenalty(frequencyPenalty);
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            params.setPresencePenalty(presencePenalty);
            return this;
        }

        public Builder repetitionPenalty(Double repetitionPenalty) {
            params.setRepetitionPenalty(repetitionPenalty);
            return this;
        }

        public Builder responseFormat(ResponseFormat responseFormat) {
            params.setResponseFormat(responseFormat);
            return this;
        }

        public DashScopeParameters build() {
            return params;
        }
    }
}
