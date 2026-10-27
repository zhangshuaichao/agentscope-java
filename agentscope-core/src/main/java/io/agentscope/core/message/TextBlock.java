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
 * 表示消息中的纯文本内容。
 *
 * <p>这是最基本的内容块类型，包含简单文本，常用于用户消息、助手响应和其他文本通信。
 *
 * <p>文本可以为空，但永不为 null。为方便使用，toString() 直接返回文本内容。
 */
public final class TextBlock extends ContentBlock {

    private final String text;

    /**
     * 创建用于 JSON 反序列化的文本块。
     *
     * @param text 文本内容（null 会转换为空字符串）
     */
    @JsonCreator
    private TextBlock(@JsonProperty("text") String text) {
        this.text = text != null ? text : "";
    }

    /**
     * 获取此块的文本内容。
     *
     * @return 文本内容
     */
    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    /**
     * 创建用于构造 TextBlock 实例的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 TextBlock 实例的构建器。
     */
    public static class Builder {

        private String text;

        /**
         * 设置内容块的文本。
         *
         * @param text 文本内容
         * @return 当前构建器，支持链式调用
         */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /**
         * 使用配置的文本构建 TextBlock。
         *
         * @return 新的 TextBlock 实例（null 文本会转换为空字符串）
         */
        public TextBlock build() {
            return new TextBlock(text != null ? text : "");
        }
    }
}
