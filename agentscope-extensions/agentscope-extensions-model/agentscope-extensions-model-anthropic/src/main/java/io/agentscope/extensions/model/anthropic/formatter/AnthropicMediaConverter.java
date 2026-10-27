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
package io.agentscope.extensions.model.anthropic.formatter;

import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.UrlImageSource;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;

/** 处理 Anthropic API 的媒体内容转换。 */
public class AnthropicMediaConverter {

    /**
     * 将 ImageBlock 转换为 Anthropic ImageBlockParam。
     * 本地文件转换为 base64，远程 URL 直接使用 URL 来源。
     */
    public ImageBlockParam convertImageBlock(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                // 将本地文件转换为 base64。
                String base64Data = MediaUtils.fileToBase64(url);
                String mediaType = MediaUtils.determineMediaType(url);

                return ImageBlockParam.builder()
                        .source(
                                Base64ImageSource.builder()
                                        .data(base64Data)
                                        .mediaType(
                                                Base64ImageSource.MediaType.of(
                                                        mediaType != null
                                                                ? mediaType
                                                                : "image/png"))
                                        .build())
                        .build();
            } else {
                // 直接使用远程 URL。
                return ImageBlockParam.builder()
                        .source(UrlImageSource.builder().url(url).build())
                        .build();
            }
        } else if (source instanceof Base64Source base64Source) {
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();

            return ImageBlockParam.builder()
                    .source(
                            Base64ImageSource.builder()
                                    .data(base64Data)
                                    .mediaType(
                                            Base64ImageSource.MediaType.of(
                                                    mediaType != null ? mediaType : "image/png"))
                                    .build())
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * 解析 MIME 类型并选择图像路径，将 DataBlock 转换为 Anthropic ImageBlockParam。
     *
     * <p>此 SDK 类型仅支持图像模态。音频与视频 DataBlock 会抛出 {@link IllegalArgumentException}，
     * 因为此处使用的 Anthropic API 尚未暴露通用二进制内容块参数。
     *
     * <p>MIME 类型解析顺序：
     * <ol><li>{@code Base64Source.mediaType}：始终显式提供。</li>
     * <li>{@code URLSource.mimeType}：调用方为无扩展名 URL 提供的提示。</li>
     * <li>{@code MediaUtils.determineMediaType(url)}：基于扩展名推断。</li></ol>
     *
     * @param dataBlock 待转换的数据块
     * @return 供 Anthropic API 使用的 ImageBlockParam
     * @throws Exception 转换失败或 MIME 类型解析为非图像类别时抛出
     */
    public ImageBlockParam convertDataBlock(DataBlock dataBlock) throws Exception {
        Source source = dataBlock.getSource();
        String mimeType = MediaUtils.resolveMimeType(source);

        if (!mimeType.startsWith("image/")) {
            throw new IllegalArgumentException(
                    "Anthropic API only supports image DataBlocks; got MIME type: " + mimeType);
        }

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            if (MediaUtils.isLocalFile(url)) {
                String base64Data = MediaUtils.fileToBase64(url);
                return ImageBlockParam.builder()
                        .source(
                                Base64ImageSource.builder()
                                        .data(base64Data)
                                        .mediaType(Base64ImageSource.MediaType.of(mimeType))
                                        .build())
                        .build();
            } else {
                // 上面已验证 mimeType 属于 image/*，跳过扩展名检查，
                // 使显式提供 mimeType 的无扩展名 CDN URL 也能工作。
                return ImageBlockParam.builder()
                        .source(UrlImageSource.builder().url(url).build())
                        .build();
            }
        } else if (source instanceof Base64Source base64Source) {
            return ImageBlockParam.builder()
                    .source(
                            Base64ImageSource.builder()
                                    .data(base64Source.getData())
                                    .mediaType(Base64ImageSource.MediaType.of(mimeType))
                                    .build())
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }
}
