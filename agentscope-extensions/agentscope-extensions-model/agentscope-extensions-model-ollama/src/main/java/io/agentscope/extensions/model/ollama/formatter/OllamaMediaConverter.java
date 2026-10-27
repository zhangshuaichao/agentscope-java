/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama.formatter;

import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 处理 Ollama API 的媒体内容转换，按 Ollama 要求将 ImageBlock 转为 Base64 字符串。
 */
public class OllamaMediaConverter {

    private static final Logger log = LoggerFactory.getLogger(OllamaMediaConverter.class);

    /**
     * 为 Ollama API 将 ImageBlock 转为 Base64 字符串。
     *
     * <p>Ollama API 要求图像为 Base64 编码字符串数组。此方法处理：
     * <ul>
     *   <li>Base64 来源：返回原始 Base64 数据</li>
     *   <li>本地文件 URL：读取文件并转为 Base64</li>
     *   <li>远程 URL：下载内容并转为 Base64</li>
     * </ul>
     *
     * @param imageBlock 待转换的图像块
     * @return 图像的 Base64 编码字符串
     * @throws Exception 转换、读取文件或下载失败时抛出
     */
    public String convertImageBlockToBase64(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof Base64Source base64Source) {
            // Ollama 需要不含 data URI 前缀的原始 Base64 字符串
            return base64Source.getData();
        } else if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                // 读取本地文件并转为 Base64
                // 本地路径存在 file:// 前缀时移除它
                String path = url.startsWith("file://") ? url.substring(7) : url;
                return MediaUtils.fileToBase64(path);
            } else {
                // 下载远程 URL 内容并转为 Base64
                return MediaUtils.downloadUrlToBase64(url);
            }
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }
}
