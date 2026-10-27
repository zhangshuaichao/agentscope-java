/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ollama 聊天响应 DTO，表示 Ollama 聊天 API 的响应。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaResponse {

    /**
     * 生成响应的模型名称。
     */
    @JsonProperty("model")
    private String model;

    /**
     * 创建时间戳。
     */
    @JsonProperty("created_at")
    private String createdAt;

    /**
     * 响应消息。
     */
    @JsonProperty("message")
    private OllamaMessage message;

    /**
     * 生成是否完成。
     */
    @JsonProperty("done")
    private Boolean done;

    /**
     * 生成响应耗时。
     */
    @JsonProperty("total_duration")
    private Long totalDuration;

    /**
     * 加载模型耗时。
     */
    @JsonProperty("load_duration")
    private Long loadDuration;

    /**
     * 提示中的 token 数量。
     */
    @JsonProperty("prompt_eval_count")
    private Integer promptEvalCount;

    /**
     * 从 Ollama 提示缓存读取的提示 token 数量。
     */
    @JsonProperty("prompt_eval_cached_count")
    private Integer promptEvalCachedCount;

    /**
     * 评估提示耗时。
     */
    @JsonProperty("prompt_eval_duration")
    private Long promptEvalDuration;

    /**
     * 响应中的 token 数量。
     */
    @JsonProperty("eval_count")
    private Integer evalCount;

    /**
     * 生成响应耗时。
     */
    @JsonProperty("eval_duration")
    private Long evalDuration;

    /**
     * 生成停止的原因（例如 "stop"、"length"）。
     */
    @JsonProperty("done_reason")
    private String doneReason;

    /**
     * 请求失败时的错误消息。
     */
    @JsonProperty("error")
    private String error;

    public OllamaResponse() {}

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public OllamaMessage getMessage() {
        return message;
    }

    public void setMessage(OllamaMessage message) {
        this.message = message;
    }

    public Boolean getDone() {
        return done;
    }

    public void setDone(Boolean done) {
        this.done = done;
    }

    public Long getTotalDuration() {
        return totalDuration;
    }

    public void setTotalDuration(Long totalDuration) {
        this.totalDuration = totalDuration;
    }

    public Long getLoadDuration() {
        return loadDuration;
    }

    public void setLoadDuration(Long loadDuration) {
        this.loadDuration = loadDuration;
    }

    public Integer getPromptEvalCount() {
        return promptEvalCount;
    }

    public Integer getPromptEvalCachedCount() {
        return promptEvalCachedCount;
    }

    public void setPromptEvalCachedCount(Integer promptEvalCachedCount) {
        this.promptEvalCachedCount = promptEvalCachedCount;
    }

    public void setPromptEvalCount(Integer promptEvalCount) {
        this.promptEvalCount = promptEvalCount;
    }

    public Long getPromptEvalDuration() {
        return promptEvalDuration;
    }

    public void setPromptEvalDuration(Long promptEvalDuration) {
        this.promptEvalDuration = promptEvalDuration;
    }

    public Integer getEvalCount() {
        return evalCount;
    }

    public void setEvalCount(Integer evalCount) {
        this.evalCount = evalCount;
    }

    public Long getEvalDuration() {
        return evalDuration;
    }

    public void setEvalDuration(Long evalDuration) {
        this.evalDuration = evalDuration;
    }

    public String getDoneReason() {
        return doneReason;
    }

    public void setDoneReason(String doneReason) {
        this.doneReason = doneReason;
    }
}
