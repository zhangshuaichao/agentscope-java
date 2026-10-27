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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI 输入音频 DTO。
 *
 * <p>表示 OpenAI API 格式的音频输入。
 *
 * <p>示例：
 * <pre>{@code
 * {
 *   "data": "base64_encoded_audio_data",
 *   "format": "wav"
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIInputAudio {

    /** base64 编码的音频数据。 */
    @JsonProperty("data")
    private String data;

    /** 音频格式，例如 "wav"、"mp3"。 */
    @JsonProperty("format")
    private String format;

    public OpenAIInputAudio() {}

    public OpenAIInputAudio(String data, String format) {
        this.data = data;
        this.format = format;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }
}
