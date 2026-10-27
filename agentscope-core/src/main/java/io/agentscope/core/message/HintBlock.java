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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 在推理过程中向大语言模型提供提示信息的内容块。
 *
 * <p>由中间件或记忆系统（例如 RAG）注入，为智能体推理提供上下文指引，而不作为对话历史的一部分。
 */
public final class HintBlock extends ContentBlock {

    private final String id;
    private final String hint;
    private final String source;

    @JsonCreator
    public HintBlock(
            @JsonProperty("id") String id,
            @JsonProperty("hint") String hint,
            @JsonProperty("source") String source) {
        this.id = id;
        this.hint = hint;
        this.source = source;
    }

    public HintBlock(String id, String hint) {
        this(id, hint, null);
    }

    /**
     * 获取提示块的唯一标识符。
     *
     * @return 提示块 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取提示文本。
     *
     * @return 提示内容
     */
    public String getHint() {
        return hint;
    }

    /**
     * 返回提示的发送者或来源。团队消息使用发送者的显示名称（例如 {@code "alice"}）；系统通知可为 {@code "system"} 或 {@code null}。
     */
    public String getSource() {
        return source;
    }
}
