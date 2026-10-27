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

/**
 * DashScope 搜索选项 DTO。
 *
 * <p>表示 API 请求中的搜索策略配置，在 enable_search 为 true 时控制互联网搜索行为。
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "enable_source": true,
 *   "enable_citation": true,
 *   "citation_format": "[<number>]",
 *   "forced_search": false,
 *   "search_strategy": "turbo",
 *   "enable_search_extension": false,
 *   "prepend_search_result": false
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeSearchOptions {

    /**
     * 是否在返回结果中展示搜索信息，默认 false。
     */
    @JsonProperty("enable_source")
    private Boolean enableSource;

    /**
     * 是否启用 [1] 或 [ref_1] 等引用标记。
     * 仅在 enable_source 为 true 时生效，默认 false。
     */
    @JsonProperty("enable_citation")
    private Boolean enableCitation;

    /**
     * 引用标记样式，仅在 enable_citation 为 true 时生效。
     * 默认 "[<number>]"，可选 "[<number>]"、"[ref_<number>]"。
     */
    @JsonProperty("citation_format")
    private String citationFormat;

    /**
     * 是否强制启用搜索，默认 false。
     */
    @JsonProperty("forced_search")
    private Boolean forcedSearch;

    /**
     * 互联网信息搜索策略，默认 "turbo"。
     * 可选 "turbo"、"max"、"agent"、"agent_max"。
     */
    @JsonProperty("search_strategy")
    private SearchStrategy searchStrategy;

    /**
     * 是否启用特定领域增强，默认 false。
     */
    @JsonProperty("enable_search_extension")
    private Boolean enableSearchExtension;

    /**
     * 流式输出且 enable_source 为 true 时，配置首个数据包是否只包含搜索来源信息。默认 false。
     * 原文文档注明 DashScope Java SDK 当时尚不支持此项；这里按 API 规范保留以完整覆盖参数。
     */
    @JsonProperty("prepend_search_result")
    private Boolean prependSearchResult;

    public DashScopeSearchOptions() {}

    public Boolean getEnableSource() {
        return enableSource;
    }

    public void setEnableSource(Boolean enableSource) {
        this.enableSource = enableSource;
    }

    public Boolean getEnableCitation() {
        return enableCitation;
    }

    public void setEnableCitation(Boolean enableCitation) {
        this.enableCitation = enableCitation;
    }

    public String getCitationFormat() {
        return citationFormat;
    }

    public void setCitationFormat(String citationFormat) {
        this.citationFormat = citationFormat;
    }

    public Boolean getForcedSearch() {
        return forcedSearch;
    }

    public void setForcedSearch(Boolean forcedSearch) {
        this.forcedSearch = forcedSearch;
    }

    public SearchStrategy getSearchStrategy() {
        return searchStrategy;
    }

    public void setSearchStrategy(SearchStrategy searchStrategy) {
        this.searchStrategy = searchStrategy;
    }

    public Boolean getEnableSearchExtension() {
        return enableSearchExtension;
    }

    public void setEnableSearchExtension(Boolean enableSearchExtension) {
        this.enableSearchExtension = enableSearchExtension;
    }

    public Boolean getPrependSearchResult() {
        return prependSearchResult;
    }

    public void setPrependSearchResult(Boolean prependSearchResult) {
        this.prependSearchResult = prependSearchResult;
    }

    public enum SearchStrategy {
        /**
         * 兼顾响应速度和搜索效果，适合多数场景（默认）。
         */
        @JsonProperty("turbo")
        TURBO,

        /**
         * 采用更全面的策略调用多源搜索引擎，获取更详尽结果，但响应时间可能更长。
         */
        @JsonProperty("max")
        MAX,

        /**
         * 可多次调用网页搜索工具与大模型，实现多轮信息检索和内容整合。
         * <p>原文列出的适用模型为 qwen3.5-plus、qwen3.5-plus-2026-02-15、qwen3.5-flash、qwen3.5-flash-2026-02-23、qwen3-max 与 qwen3-max-2026-01-23（仅流式）、qwen3-max-2026-01-23 非思考模式，以及 qwen3-max-2025-09-23。
         * 启用时仅支持搜索来源（enable_source: true），其他网页搜索选项不可用。
         */
        @JsonProperty("agent")
        AGENT,

        /**
         * 在 agent 策略基础上支持网页提取器。
         * <p>原文列出的适用模型为 qwen3.5-plus、qwen3.5-plus-2026-02-15、qwen3.5-flash、qwen3.5-flash-2026-02-23，以及 qwen3-max 与 qwen3-max-2026-01-23 的思考模式。
         * 启用时仅支持搜索来源（enable_source: true），其他网页搜索选项不可用。
         */
        @JsonProperty("agent_max")
        AGENT_MAX
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DashScopeSearchOptions searchOptions = new DashScopeSearchOptions();

        public Builder enableSource(Boolean enableSource) {
            searchOptions.setEnableSource(enableSource);
            return this;
        }

        public Builder enableCitation(Boolean enableCitation) {
            searchOptions.setEnableCitation(enableCitation);
            return this;
        }

        public Builder citationFormat(String citationFormat) {
            searchOptions.setCitationFormat(citationFormat);
            return this;
        }

        public Builder forcedSearch(Boolean forcedSearch) {
            searchOptions.setForcedSearch(forcedSearch);
            return this;
        }

        public Builder searchStrategy(SearchStrategy searchStrategy) {
            searchOptions.setSearchStrategy(searchStrategy);
            return this;
        }

        public Builder enableSearchExtension(Boolean enableSearchExtension) {
            searchOptions.setEnableSearchExtension(enableSearchExtension);
            return this;
        }

        public Builder prependSearchResult(Boolean prependSearchResult) {
            searchOptions.setPrependSearchResult(prependSearchResult);
            return this;
        }

        public DashScopeSearchOptions build() {
            return searchOptions;
        }
    }
}
