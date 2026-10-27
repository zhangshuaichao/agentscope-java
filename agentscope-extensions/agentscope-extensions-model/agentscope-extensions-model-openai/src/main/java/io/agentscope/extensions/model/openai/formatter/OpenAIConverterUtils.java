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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;

/** OpenAI 消息与对话转换工具类。 */
public final class OpenAIConverterUtils {

    private OpenAIConverterUtils() {
        // 工具类，不允许实例化。
    }

    /**
     * 将图像来源转换为 URL 字符串。
     *
     * @param source 图像来源，URLSource 或 Base64Source
     * @return URL 字符串或数据 URI
     * @throws IllegalArgumentException 来源为 null 或类型未知时抛出
     */
    public static String convertImageSourceToUrl(Source source) {
        if (source == null) {
            throw new IllegalArgumentException("Image source cannot be null");
        }
        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            if (url == null || url.isEmpty()) {
                throw new IllegalArgumentException("URLSource has null or empty URL");
            }
            return url;
        } else if (source instanceof Base64Source b64Source) {
            // 将 base64 数据转换为数据 URI。
            String data = b64Source.getData();
            if (data == null || data.isEmpty()) {
                throw new IllegalArgumentException("Base64Source has null or empty data");
            }
            String mediaType =
                    b64Source.getMediaType() != null ? b64Source.getMediaType() : "image/png";
            return "data:" + mediaType + ";base64," + data;
        } else {
            throw new IllegalArgumentException("Unknown source type: " + source.getClass());
        }
    }

    /**
     * 根据媒体类型判断音频格式。
     *
     * @param mediaType 媒体类型，例如 "audio/wav"
     * @return 格式字符串，例如 "wav"
     */
    public static String detectAudioFormat(String mediaType) {
        if (mediaType == null) {
            return "mp3";
        }
        if (mediaType.contains("wav")) {
            return "wav";
        } else if (mediaType.contains("mp3")) {
            return "mp3";
        } else if (mediaType.contains("opus")) {
            return "opus";
        } else if (mediaType.contains("flac")) {
            return "flac";
        } else {
            return "mp3";
        }
    }

    /**
     * 将视频来源转换为 URL 字符串。
     *
     * @param source 视频来源，URLSource 或 Base64Source
     * @return URL 字符串或数据 URI
     * @throws IllegalArgumentException 来源为 null 或类型未知时抛出
     */
    public static String convertVideoSourceToUrl(Source source) {
        if (source == null) {
            throw new IllegalArgumentException("Video source cannot be null");
        }
        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            if (url == null || url.isEmpty()) {
                throw new IllegalArgumentException("URLSource has null or empty URL");
            }
            return url;
        } else if (source instanceof Base64Source b64Source) {
            // 将 base64 数据转换为数据 URI。
            String data = b64Source.getData();
            if (data == null || data.isEmpty()) {
                throw new IllegalArgumentException("Base64Source has null or empty data");
            }
            String mediaType =
                    b64Source.getMediaType() != null ? b64Source.getMediaType() : "video/mp4";
            return "data:" + mediaType + ";base64," + data;
        } else {
            throw new IllegalArgumentException("Unknown source type: " + source.getClass());
        }
    }
}
