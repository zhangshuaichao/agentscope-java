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
import java.util.List;
import java.util.Map;

/**
 * DashScope 多模态消息内容片段 DTO。
 *
 * <p>每个片段可包含文本、图像、音频或视频中的一种内容。
 *
 * <p>文本片段示例：
 * <pre>{@code
 * {"text": "What's in this image?"}
 * }</pre>
 *
 * <p>图像 URL 片段示例：
 * <pre>{@code
 * {"image": "https://example.com/image.jpg"}
 * }</pre>
 *
 * <p>Base64 图像片段示例：
 * <pre>{@code
 * {"image": "data:image/png;base64,iVBORw0KGgo..."}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeContentPart {

    /**
     * 文本内容。
     */
    @JsonProperty("text")
    private String text;

    /**
     * 图像 URL 或 Base64 data URI。
     */
    @JsonProperty("image")
    private String image;

    /**
     * 音频 URL 或 Base64 data URI。
     */
    @JsonProperty("audio")
    private String audio;

    /**
     * 视频 URL 或帧列表。
     */
    @JsonProperty("video")
    private Object video;

    /**
     * 每秒帧数，范围 [0.1, 10]，默认 2.0。
     */
    @JsonProperty("fps")
    private Float fps;

    /**
     * 视频中最多提取的帧数。
     */
    @JsonProperty("max_frames")
    private Integer maxFrames;

    /**
     * 输入图像或视频帧的最小像素阈值。
     */
    @JsonProperty("min_pixels")
    private Integer minPixels;

    /**
     * 输入图像或视频帧的最大像素阈值。
     */
    @JsonProperty("max_pixels")
    private Integer maxPixels;

    /**
     * 限制视频提取全部帧的总像素数（单张像素数 × 总帧数）。
     */
    @JsonProperty("total_pixels")
    private Integer totalPixels;

    /**
     * 此内容片段的缓存控制配置。
     */
    @JsonProperty("cache_control")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> cacheControl;

    public DashScopeContentPart() {}

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getAudio() {
        return audio;
    }

    public void setAudio(String audio) {
        this.audio = audio;
    }

    public Object getVideo() {
        return video;
    }

    public void setVideo(Object video) {
        this.video = video;
    }

    public Float getFps() {
        return fps;
    }

    public void setFps(Float fps) {
        this.fps = fps;
    }

    public Integer getMaxFrames() {
        return maxFrames;
    }

    public void setMaxFrames(Integer maxFrames) {
        this.maxFrames = maxFrames;
    }

    public Integer getMinPixels() {
        return minPixels;
    }

    public void setMinPixels(Integer minPixels) {
        this.minPixels = minPixels;
    }

    public Integer getMaxPixels() {
        return maxPixels;
    }

    public void setMaxPixels(Integer maxPixels) {
        this.maxPixels = maxPixels;
    }

    public Integer getTotalPixels() {
        return totalPixels;
    }

    public void setTotalPixels(Integer totalPixels) {
        this.totalPixels = totalPixels;
    }

    /**
     * 获取缓存控制配置。
     *
     * @return 缓存控制配置；未配置时为 null
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
     * 将视频作为 URL 字符串获取。
     *
     * @return 视频 URL；不是字符串时为 null
     */
    public String getVideoAsString() {
        if (video instanceof String) {
            return (String) video;
        }
        return null;
    }

    /**
     * 将视频作为帧列表获取。
     *
     * @return 视频帧；不是列表时为 null
     */
    @SuppressWarnings("unchecked")
    public List<String> getVideoAsList() {
        if (video instanceof List) {
            return (List<String>) video;
        }
        return null;
    }

    /**
     * 创建文本内容片段。
     *
     * @param text 文本内容
     * @return 新的 DashScopeContentPart
     */
    public static DashScopeContentPart text(String text) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setText(text);
        return part;
    }

    /**
     * 创建图像内容片段。
     *
     * @param imageUrl 图像 URL 或 Base64 data URI
     * @return 新的 DashScopeContentPart
     */
    public static DashScopeContentPart image(String imageUrl) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setImage(imageUrl);
        return part;
    }

    /**
     * 创建音频内容片段。
     *
     * @param audioUrl 音频 URL 或 Base64 data URI
     * @return 新的 DashScopeContentPart
     */
    public static DashScopeContentPart audio(String audioUrl) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setAudio(audioUrl);
        return part;
    }

    /**
     * 从 URL 创建视频内容片段。
     *
     * @param videoUrl 视频 URL
     * @return 新的 DashScopeContentPart
     */
    public static DashScopeContentPart video(String videoUrl) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setVideo(videoUrl);
        return part;
    }

    /**
     * 从帧列表创建视频内容片段。
     *
     * @param frames 帧 URL 列表
     * @return 新的 DashScopeContentPart
     */
    public static DashScopeContentPart videoFrames(List<String> frames) {
        DashScopeContentPart part = new DashScopeContentPart();
        part.setVideo(frames);
        return part;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DashScopeContentPart part = new DashScopeContentPart();

        public Builder text(String text) {
            part.setText(text);
            return this;
        }

        public Builder image(String image) {
            part.setImage(image);
            return this;
        }

        public Builder audio(String audio) {
            part.setAudio(audio);
            return this;
        }

        public Builder video(Object video) {
            part.setVideo(video);
            return this;
        }

        public Builder fps(Float fps) {
            part.setFps(fps);
            return this;
        }

        public Builder maxFrames(Integer maxFrames) {
            part.setMaxFrames(maxFrames);
            return this;
        }

        public Builder minPixels(Integer minPixels) {
            part.setMinPixels(minPixels);
            return this;
        }

        public Builder maxPixels(Integer maxPixels) {
            part.setMaxPixels(maxPixels);
            return this;
        }

        public Builder totalPixels(Integer totalPixels) {
            part.setTotalPixels(totalPixels);
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

        public DashScopeContentPart build() {
            return part;
        }
    }
}
