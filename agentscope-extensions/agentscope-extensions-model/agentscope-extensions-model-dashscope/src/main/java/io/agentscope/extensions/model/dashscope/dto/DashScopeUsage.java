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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope 使用统计 DTO。
 *
 * <p>表示 API 响应中的 token 使用统计。
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "input_tokens": 10,
 *   "output_tokens": 20,
 *   "total_tokens": 30
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopeUsage {

    /**
     * 输入 token 数。
     */
    @JsonProperty("input_tokens")
    private Integer inputTokens;

    /**
     * 输出 token 数。
     */
    @JsonProperty("output_tokens")
    private Integer outputTokens;

    /**
     * 总 token 数（输入加输出）。
     */
    @JsonProperty("total_tokens")
    private Integer totalTokens;

    /**
     * 输出 token 明细。
     */
    @JsonProperty("output_tokens_details")
    private OutputTokensDetails outputTokensDetails;

    /**
     * 提示 token 明细。
     */
    @JsonProperty("prompt_tokens_details")
    private PromptTokensDetails promptTokensDetails;

    /**
     * 图像 token 数（多模态）。
     */
    @JsonProperty("image_tokens")
    private Integer imageTokens;

    /**
     * 视频 token 数（多模态）。
     */
    @JsonProperty("video_tokens")
    private Integer videoTokens;

    /**
     * 音频 token 数（多模态）。
     */
    @JsonProperty("audio_tokens")
    private Integer audioTokens;

    public DashScopeUsage() {}

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public OutputTokensDetails getOutputTokensDetails() {
        return outputTokensDetails;
    }

    public void setOutputTokensDetails(OutputTokensDetails outputTokensDetails) {
        this.outputTokensDetails = outputTokensDetails;
    }

    public PromptTokensDetails getPromptTokensDetails() {
        return promptTokensDetails;
    }

    public void setPromptTokensDetails(PromptTokensDetails promptTokensDetails) {
        this.promptTokensDetails = promptTokensDetails;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Integer getImageTokens() {
        return imageTokens;
    }

    public void setImageTokens(Integer imageTokens) {
        this.imageTokens = imageTokens;
    }

    public Integer getVideoTokens() {
        return videoTokens;
    }

    public void setVideoTokens(Integer videoTokens) {
        this.videoTokens = videoTokens;
    }

    public Integer getAudioTokens() {
        return audioTokens;
    }

    public void setAudioTokens(Integer audioTokens) {
        this.audioTokens = audioTokens;
    }

    /**
     * 输出 token 明细。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OutputTokensDetails {

        /**
         * 推理模型使用的推理 token 数，属于输出 token 的子集。
         */
        @JsonProperty("reasoning_tokens")
        private Integer reasoningTokens;

        public Integer getReasoningTokens() {
            return reasoningTokens;
        }

        public void setReasoningTokens(Integer reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
        }
    }

    /**
     * 提示 token 明细。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptTokensDetails {

        /**
         * 由提示缓存提供的输入 token 数，属于输入 token 的子集。
         */
        @JsonProperty("cached_tokens")
        private Integer cachedTokens;

        /**
         * 用于创建显式提示缓存条目的输入 token 数。
         */
        @JsonProperty("cache_creation_input_tokens")
        private Integer cacheCreationInputTokens;

        public Integer getCachedTokens() {
            return cachedTokens;
        }

        public void setCachedTokens(Integer cachedTokens) {
            this.cachedTokens = cachedTokens;
        }

        public Integer getCacheCreationInputTokens() {
            return cacheCreationInputTokens;
        }

        public void setCacheCreationInputTokens(Integer cacheCreationInputTokens) {
            this.cacheCreationInputTokens = cacheCreationInputTokens;
        }
    }
}
