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
import java.util.List;

/**
 * DashScope 输出 DTO。
 *
 * <p>表示 API 响应的 output 部分。
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "choices": [
 *     {
 *       "message": {...},
 *       "finish_reason": "stop"
 *     }
 *   ],
 *   "finish_reason": "stop"
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopeOutput {

    /**
     * 候选项列表（聊天补全通常只有一项）。
     */
    @JsonProperty("choices")
    private List<DashScopeChoice> choices;

    /**
     * 顶层结束原因。
     */
    @JsonProperty("finish_reason")
    private String finishReason;

    /**
     * 旧版文本字段（result_format="text" 时）。
     */
    @JsonProperty("text")
    private String text;

    public DashScopeOutput() {}

    public List<DashScopeChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<DashScopeChoice> choices) {
        this.choices = choices;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    /**
     * 获取首个候选项（若有）。
     *
     * @return 首个候选项；无候选时为 null
     */
    public DashScopeChoice getFirstChoice() {
        if (choices != null && !choices.isEmpty()) {
            return choices.get(0);
        }
        return null;
    }
}
