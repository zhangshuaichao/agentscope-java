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
import java.util.Map;

/**
 * OpenAI 多模态消息内容片段 DTO。
 *
 * <p>每个片段具有类型及对应数据：
 * <ul>
 * <li>"text" 类型对应 text 字段。</li>
 * <li>"image_url" 类型对应 image_url 字段。</li>
 * <li>"video_url" 类型对应 video_url 字段。</li>
 * <li>"input_audio" 类型对应 input_audio 字段。</li>
 * </ul>
 *
 * <p>文本片段示例：
 * <pre>{@code
 * {"type": "text", "text": "What's in this image?"}
 * }</pre>
 *
 * <p>图像片段示例：
 * <pre>{@code
 * {"type": "image_url", "image_url": {"url": "https://example.com/image.jpg"}}
 * }</pre>
 *
 * <p>视频片段示例：
 * <pre>{@code
 * {"type": "video_url", "videoUrl": {"url": "https://example.com/video.mp4"}}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIContentPart {

    /** 内容类型："text"、"image_url"、"video_url" 或 "input_audio"。 */
    @JsonProperty("type")
    private String type;

    /** 文本内容，供 type="text" 使用。 */
    @JsonProperty("text")
    private String text;

    /** 图像 URL 对象，供 type="image_url" 使用。 */
    @JsonProperty("image_url")
    private OpenAIImageUrl imageUrl;

    /** 音频输入对象，供 type="input_audio" 使用。 */
    @JsonProperty("input_audio")
    private OpenAIInputAudio inputAudio;

    /** 视频 URL 对象，供 type="video_url" 使用。 */
    @JsonProperty("video_url")
    private OpenAIVideoUrl videoUrl;

    /** 此内容片段的缓存控制配置。 */
    @JsonProperty("cache_control")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> cacheControl;

    public OpenAIContentPart() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public OpenAIImageUrl getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(OpenAIImageUrl imageUrl) {
        this.imageUrl = imageUrl;
    }

    public OpenAIInputAudio getInputAudio() {
        return inputAudio;
    }

    public void setInputAudio(OpenAIInputAudio inputAudio) {
        this.inputAudio = inputAudio;
    }

    public OpenAIVideoUrl getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(OpenAIVideoUrl videoUrl) {
        this.videoUrl = videoUrl;
    }

    /**
     * 获取缓存控制配置。
     *
     * @return 缓存控制配置；未配置时返回 null
     */
    public Map<String, String> getCacheControl() {
        return cacheControl;
    }

    /**
     * 设置缓存控制配置。
     *
     * @param cacheControl 缓存控制配置
     */
    public void setCacheControl(Map<String, String> cacheControl) {
        this.cacheControl = cacheControl;
    }

    /**
     * 创建文本内容片段。
     *
     * @param text 文本内容
     * @return 新的 OpenAIContentPart
     */
    public static OpenAIContentPart text(String text) {
        OpenAIContentPart part = new OpenAIContentPart();
        part.setType("text");
        part.setText(text);
        return part;
    }

    /**
     * 创建图像内容片段。
     *
     * @param url 图像 URL 或 base64 数据 URI
     * @return 新的 OpenAIContentPart
     */
    public static OpenAIContentPart imageUrl(String url) {
        OpenAIContentPart part = new OpenAIContentPart();
        part.setType("image_url");
        part.setImageUrl(new OpenAIImageUrl(url));
        return part;
    }

    /**
     * 创建带细节级别的图像内容片段。
     *
     * @param url 图像 URL 或 base64 数据 URI
     * @param detail 细节级别："low"、"high" 或 "auto"
     * @return 新的 OpenAIContentPart
     */
    public static OpenAIContentPart imageUrl(String url, String detail) {
        OpenAIContentPart part = new OpenAIContentPart();
        part.setType("image_url");
        part.setImageUrl(new OpenAIImageUrl(url, detail));
        return part;
    }

    /**
     * 创建音频内容片段。
     *
     * @param data base64 编码的音频数据
     * @param format 音频格式，例如 "wav"、"mp3"
     * @return 新的 OpenAIContentPart
     */
    public static OpenAIContentPart inputAudio(String data, String format) {
        OpenAIContentPart part = new OpenAIContentPart();
        part.setType("input_audio");
        part.setInputAudio(new OpenAIInputAudio(data, format));
        return part;
    }

    /**
     * 创建视频内容片段。
     *
     * @param url 视频 URL 或 base64 数据 URI
     * @return 新的 OpenAIContentPart
     */
    public static OpenAIContentPart videoUrl(String url) {
        OpenAIContentPart part = new OpenAIContentPart();
        part.setType("video_url");
        part.setVideoUrl(new OpenAIVideoUrl(url));
        return part;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OpenAIContentPart part = new OpenAIContentPart();

        public Builder type(String type) {
            part.setType(type);
            return this;
        }

        public Builder text(String text) {
            part.setText(text);
            return this;
        }

        public Builder imageUrl(OpenAIImageUrl imageUrl) {
            part.setImageUrl(imageUrl);
            return this;
        }

        public Builder inputAudio(OpenAIInputAudio inputAudio) {
            part.setInputAudio(inputAudio);
            return this;
        }

        public Builder videoUrl(OpenAIVideoUrl videoUrl) {
            part.setVideoUrl(videoUrl);
            return this;
        }

        /**
         * 设置缓存控制配置。
         *
         * @param cacheControl 缓存控制配置
         * @return 当前构建器
         */
        public Builder cacheControl(Map<String, String> cacheControl) {
            part.setCacheControl(cacheControl);
            return this;
        }

        public OpenAIContentPart build() {
            OpenAIContentPart result = part;
            part = new OpenAIContentPart();
            return result;
        }
    }
}
