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
import java.util.Objects;

/**
 * 表示消息中以 URL 或 Base64 为来源的图像内容。
 *
 * <p>此内容块支持两种图像来源：
 * <ul>
 * <li>URL 来源：通过 HTTP/HTTPS URL 或本地文件 URL 访问的图像</li>
 * <li>Base64 来源：以 Base64 字符串编码并附带 MIME 类型的图像</li>
 * </ul>
 *
 * <p>图像块用于多模态 AI 交互，使智能体能够处理图像、图表、屏幕截图等视觉信息。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ImageBlock extends ContentBlock {

    private final Source source;

    private final Integer minPixels;

    private final Integer maxPixels;

    /**
     * 使用来源创建图像块。
     *
     * @param source 图像来源（URL 或 Base64）
     * @throws NullPointerException 当 source 为 null 时
     */
    public ImageBlock(@JsonProperty("source") Source source) {
        this(source, null, null);
    }

    /**
     * 创建用于 JSON 反序列化的图像块。
     *
     * @param source 图像来源（URL 或 Base64）
     * @param minPixels 输入图像的最小像素阈值
     * @param maxPixels 输入图像的最大像素阈值
     * @throws NullPointerException 当 source 为 null 时
     */
    @JsonCreator
    private ImageBlock(
            @JsonProperty("source") Source source,
            @JsonProperty("min_pixels") Integer minPixels,
            @JsonProperty("max_pixels") Integer maxPixels) {
        this.source = Objects.requireNonNull(source, "source cannot be null");
        this.minPixels = minPixels;
        this.maxPixels = maxPixels;
    }

    /**
     * 获取图像来源。
     *
     * @return 包含 URL 或 Base64 数据的图像来源
     */
    public Source getSource() {
        return source;
    }

    /**
     * 获取输入图像的最小像素阈值。
     *
     * @return 最小像素阈值
     */
    public Integer getMinPixels() {
        return minPixels;
    }

    /**
     * 获取输入图像的最大像素阈值。
     *
     * @return 最大像素阈值
     */
    public Integer getMaxPixels() {
        return maxPixels;
    }

    /**
     * 创建用于构造 ImageBlock 实例的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 ImageBlock 实例的构建器。
     */
    public static class Builder {

        private Source source;

        private Integer minPixels;

        private Integer maxPixels;

        /**
         * 设置图像来源。
         *
         * @param source 图像来源（URL 或 Base64）
         * @return 当前构建器，支持链式调用
         */
        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        /**
         * 设置输入图像帧的最小像素阈值。
         *
         * @param minPixels 最小像素阈值
         * @return 当前构建器，支持链式调用
         */
        public Builder minPixels(Integer minPixels) {
            this.minPixels = minPixels;
            return this;
        }

        /**
         * 设置输入图像帧的最大像素阈值。
         *
         * @param maxPixels 最大像素阈值
         * @return 当前构建器，支持链式调用
         */
        public Builder maxPixels(Integer maxPixels) {
            this.maxPixels = maxPixels;
            return this;
        }

        /**
         * 使用配置的来源构建 ImageBlock。
         *
         * @return 新的 ImageBlock 实例
         * @throws NullPointerException 当 source 为 null 时
         */
        public ImageBlock build() {
            return new ImageBlock(source, minPixels, maxPixels);
        }
    }
}
