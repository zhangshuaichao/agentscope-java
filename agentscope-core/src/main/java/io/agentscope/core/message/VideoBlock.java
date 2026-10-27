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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示消息中的视频内容。
 *
 * <p>此内容块支持两种视频来源：
 * <ul>
 * <li>URL 来源：可通过 HTTP/HTTPS URL 或本地文件 URL 访问的视频文件</li>
 * <li>Base64 来源：以 Base64 字符串编码并附带 MIME 类型的视频</li>
 * </ul>
 *
 * <p>视频块支持高级多模态 AI 交互，使智能体能够处理或分析演示、教程、监控录像等包含运动和时间元素的视频内容。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class VideoBlock extends ContentBlock {

    private final Source source;

    private final Float fps;

    private final Integer maxFrames;

    private final Integer minPixels;

    private final Integer maxPixels;

    private final Integer totalPixels;

    /**
     * 使用来源创建视频块。
     *
     * @param source 视频来源（URL 或 Base64）
     */
    public VideoBlock(@JsonProperty("source") Source source) {
        this(source, null, null, null, null, null);
    }

    /**
     * 创建用于 JSON 反序列化的视频块。
     *
     * @param source 视频来源（URL 或 Base64）
     * @param fps 每秒帧数，范围为 [0.1, 10]，默认值为 2.0
     * @param maxFrames 从视频中采集的最大帧数
     * @param minPixels 输入视频帧的最小像素阈值
     * @param maxPixels 输入视频帧的最大像素阈值
     * @param totalPixels 从视频提取的所有帧的总像素上限（单幅图像像素数 × 总帧数）
     */
    @JsonCreator
    private VideoBlock(
            @JsonProperty("source") Source source,
            @JsonProperty("fps") Float fps,
            @JsonProperty("max_frames") Integer maxFrames,
            @JsonProperty("min_pixels") Integer minPixels,
            @JsonProperty("max_pixels") Integer maxPixels,
            @JsonProperty("total_pixels") Integer totalPixels) {
        this.source = source;
        this.fps = fps;
        this.maxFrames = maxFrames;
        this.minPixels = minPixels;
        this.maxPixels = maxPixels;
        this.totalPixels = totalPixels;
    }

    /**
     * 获取视频内容的来源。
     *
     * @return 包含 URL 或 Base64 数据的视频来源
     */
    public Source getSource() {
        return source;
    }

    /**
     * 获取视频的每秒帧数（FPS）。
     *
     * @return 每秒帧数
     */
    public Float getFps() {
        return fps;
    }

    /**
     * 获取从视频中采集的最大帧数。
     *
     * @return 最大帧数
     */
    public Integer getMaxFrames() {
        return maxFrames;
    }

    /**
     * 获取输入视频帧的最小像素阈值。
     *
     * @return 最小像素阈值
     */
    public Integer getMinPixels() {
        return minPixels;
    }

    /**
     * 获取输入视频帧的最大像素阈值。
     *
     * @return 最大像素阈值
     */
    public Integer getMaxPixels() {
        return maxPixels;
    }

    /**
     * 获取从视频提取的所有帧的总像素数（单幅图像像素数 × 总帧数）。
     * @return 总像素数
     */
    public Integer getTotalPixels() {
        return totalPixels;
    }

    /**
     * 创建用于构造 VideoBlock 实例的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 VideoBlock 实例的构建器。
     */
    public static class Builder {

        private Source source;

        private Float fps;

        private Integer maxFrames;

        private Integer minPixels;

        private Integer maxPixels;

        private Integer totalPixels;

        /**
         * 设置视频内容的来源。
         *
         * @param source 视频来源（URL 或 Base64）
         * @return 当前构建器，支持链式调用
         */
        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        /**
         * 设置视频的每秒帧数（FPS）。
         *
         * @param fps 每秒帧数
         * @return 当前构建器，支持链式调用
         */
        public Builder fps(Float fps) {
            this.fps = fps;
            return this;
        }

        /**
         * 设置从视频中采集的最大帧数。
         *
         * @param maxFrames 最大帧数
         * @return 当前构建器，支持链式调用
         */
        public Builder maxFrames(Integer maxFrames) {
            this.maxFrames = maxFrames;
            return this;
        }

        /**
         * 设置输入视频帧的最小像素阈值。
         *
         * @param minPixels 最小像素阈值
         * @return 当前构建器，支持链式调用
         */
        public Builder minPixels(Integer minPixels) {
            this.minPixels = minPixels;
            return this;
        }

        /**
         * 设置输入视频帧的最大像素阈值。
         *
         * @param maxPixels 最大像素阈值
         * @return 当前构建器，支持链式调用
         */
        public Builder maxPixels(Integer maxPixels) {
            this.maxPixels = maxPixels;
            return this;
        }

        /**
         * 设置从视频提取的所有帧的总像素数（单幅图像像素数 × 总帧数）。
         *
         * @param totalPixels 总像素数
         * @return 当前构建器，支持链式调用
         */
        public Builder totalPixels(Integer totalPixels) {
            this.totalPixels = totalPixels;
            return this;
        }

        /**
         * 使用配置的来源构建 VideoBlock。
         *
         * @return 新的 VideoBlock 实例
         */
        public VideoBlock build() {
            return new VideoBlock(source, fps, maxFrames, minPixels, maxPixels, totalPixels);
        }
    }
}
