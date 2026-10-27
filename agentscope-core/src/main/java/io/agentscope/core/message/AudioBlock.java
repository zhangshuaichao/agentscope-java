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
import java.util.Objects;

/**
 * 表示消息中的音频内容。
 *
 * <p>此内容块支持两种音频来源：
 * <ul>
 * <li>URL 来源：可通过 HTTP/HTTPS URL 或本地文件 URL 访问的音频文件</li>
 * <li>Base64 来源：以 Base64 字符串编码并附带 MIME 类型的音频</li>
 * </ul>
 *
 * <p>音频块支持多模态 AI 交互，使智能体能够处理或生成语音、音乐、音效等听觉信息。
 */
public final class AudioBlock extends ContentBlock {

    private final Source source;

    /**
     * 创建用于 JSON 反序列化的音频块。
     *
     * @param source 音频来源（URL 或 Base64）
     * @throws NullPointerException 当 source 为 null 时
     */
    @JsonCreator
    public AudioBlock(@JsonProperty("source") Source source) {
        this.source = Objects.requireNonNull(source, "source cannot be null");
    }

    /**
     * 获取音频内容的来源。
     *
     * @return 包含 URL 或 Base64 数据的音频来源
     */
    public Source getSource() {
        return source;
    }

    /**
     * 创建用于构造 AudioBlock 实例的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 AudioBlock 实例的构建器。
     */
    public static class Builder {

        private Source source;

        /**
         * 设置音频内容的来源。
         *
         * @param source 音频来源（URL 或 Base64）
         * @return 当前构建器，支持链式调用
         */
        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        /**
         * 使用配置的来源构建 AudioBlock。
         *
         * @return 新的 AudioBlock 实例
         * @throws NullPointerException 当 source 为 null 时
         */
        public AudioBlock build() {
            return new AudioBlock(source);
        }
    }
}
