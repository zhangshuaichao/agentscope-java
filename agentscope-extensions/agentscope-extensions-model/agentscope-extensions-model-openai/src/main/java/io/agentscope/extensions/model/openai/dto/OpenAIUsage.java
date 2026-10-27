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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI 用量统计 DTO。
 *
 * <p>表示 OpenAI API 响应中的 token 用量统计。
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "prompt_tokens": 10,
 *   "completion_tokens": 20,
 *   "total_tokens": 30
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIUsage {

    /** 提示词 token 数量。 */
    @JsonProperty("prompt_tokens")
    private Integer promptTokens;

    /** 补全 token 数量。 */
    @JsonProperty("completion_tokens")
    private Integer completionTokens;

    /** token 总数，即提示词加补全。 */
    @JsonProperty("total_tokens")
    private Integer totalTokens;

    /** 由缓存提供的提示词 token 数量，适用于 DeepSeek 的 OpenAI 兼容响应。 */
    @JsonProperty("prompt_cache_hit_tokens")
    private Integer promptCacheHitTokens;

    /** 提示词 token 细分，可选。 */
    @JsonProperty("prompt_tokens_details")
    private PromptTokensDetails promptTokensDetails;

    /** 补全 token 细分，可选。 */
    @JsonProperty("completion_tokens_details")
    private CompletionTokensDetails completionTokensDetails;

    public OpenAIUsage() {}

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public Integer getPromptCacheHitTokens() {
        return promptCacheHitTokens;
    }

    public void setPromptCacheHitTokens(Integer promptCacheHitTokens) {
        this.promptCacheHitTokens = promptCacheHitTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public PromptTokensDetails getPromptTokensDetails() {
        return promptTokensDetails;
    }

    public void setPromptTokensDetails(PromptTokensDetails promptTokensDetails) {
        this.promptTokensDetails = promptTokensDetails;
    }

    public CompletionTokensDetails getCompletionTokensDetails() {
        return completionTokensDetails;
    }

    public void setCompletionTokensDetails(CompletionTokensDetails completionTokensDetails) {
        this.completionTokensDetails = completionTokensDetails;
    }

    /** 提示词 token 的详细分类。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptTokensDetails {
        @JsonProperty("cached_tokens")
        private Integer cachedTokens;

        /** 写入缓存的提示词 token 数量。 */
        @JsonProperty("cache_write_tokens")
        private Integer cacheWriteTokens;

        @JsonProperty("audio_tokens")
        private Integer audioTokens;

        public Integer getCachedTokens() {
            return cachedTokens;
        }

        public void setCachedTokens(Integer cachedTokens) {
            this.cachedTokens = cachedTokens;
        }

        public Integer getCacheWriteTokens() {
            return cacheWriteTokens;
        }

        public void setCacheWriteTokens(Integer cacheWriteTokens) {
            this.cacheWriteTokens = cacheWriteTokens;
        }

        public Integer getAudioTokens() {
            return audioTokens;
        }

        public void setAudioTokens(Integer audioTokens) {
            this.audioTokens = audioTokens;
        }
    }

    /** 补全 token 的详细分类。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompletionTokensDetails {
        @JsonProperty("reasoning_tokens")
        private Integer reasoningTokens;

        @JsonProperty("audio_tokens")
        private Integer audioTokens;

        @JsonProperty("accepted_prediction_tokens")
        private Integer acceptedPredictionTokens;

        @JsonProperty("rejected_prediction_tokens")
        private Integer rejectedPredictionTokens;

        public Integer getReasoningTokens() {
            return reasoningTokens;
        }

        public void setReasoningTokens(Integer reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
        }

        public Integer getAudioTokens() {
            return audioTokens;
        }

        public void setAudioTokens(Integer audioTokens) {
            this.audioTokens = audioTokens;
        }

        public Integer getAcceptedPredictionTokens() {
            return acceptedPredictionTokens;
        }

        public void setAcceptedPredictionTokens(Integer acceptedPredictionTokens) {
            this.acceptedPredictionTokens = acceptedPredictionTokens;
        }

        public Integer getRejectedPredictionTokens() {
            return rejectedPredictionTokens;
        }

        public void setRejectedPredictionTokens(Integer rejectedPredictionTokens) {
            this.rejectedPredictionTokens = rejectedPredictionTokens;
        }
    }
}
