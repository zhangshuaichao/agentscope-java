/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.agent.message;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import org.a2aproject.sdk.spec.FileContent;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;

/**
 * 将 {@link FilePart} 转换为 {@link ContentBlock} 的解析器。
 * <ul>
 *   <li>mimeType 为 `image/*` 的 {@link FilePart} 解析为 {@link ImageBlock}</li>
 *   <li>mimeType 为 `audio/*` 的 {@link FilePart} 解析为 {@link AudioBlock}</li>
 *   <li>mimeType 为 `video/*` 的 {@link FilePart} 解析为 {@link VideoBlock}</li>
 * </ul>
 */
public class FilePartParser implements PartParser<FilePart> {

    @Override
    public ContentBlock parse(FilePart part) {
        FileContent file = part.file();
        return switch (getPrimaryType(file.mimeType())) {
            case MessageConstants.BlockContent.TYPE_IMAGE -> parseToImageBlock(file);
            case MessageConstants.BlockContent.TYPE_AUDIO -> parseToAudioBlock(file);
            case MessageConstants.BlockContent.TYPE_VIDEO -> parseToVideoBlock(file);
            default -> null;
        };
    }

    private ImageBlock parseToImageBlock(FileContent file) {
        ImageBlock.Builder builder = ImageBlock.builder();
        Source source = buildSource(file);
        return source != null ? builder.source(source).build() : null;
    }

    private AudioBlock parseToAudioBlock(FileContent file) {
        AudioBlock.Builder builder = AudioBlock.builder();
        Source source = buildSource(file);
        return source != null ? builder.source(source).build() : null;
    }

    private VideoBlock parseToVideoBlock(FileContent file) {
        VideoBlock.Builder builder = VideoBlock.builder();
        Source source = buildSource(file);
        return source != null ? builder.source(source).build() : null;
    }

    private Source buildSource(FileContent file) {
        if (file instanceof FileWithBytes fileWithBytes) {
            return Base64Source.builder()
                    .mediaType(file.mimeType())
                    .data(fileWithBytes.bytes())
                    .build();
        } else if (file instanceof FileWithUri fileWithUri) {
            return URLSource.builder().url(fileWithUri.uri()).build();
        } else {
            return null;
        }
    }

    private String getPrimaryType(String mimeType) {
        // 没有 mimeType 时返回空字符串。
        if (mimeType == null || mimeType.isEmpty()) {
            return "";
        }
        int slashIndex = mimeType.indexOf('/');
        if (slashIndex == -1) {
            // 没有 / 时，表示它是主类型或错误类型。
            return mimeType;
        }
        // 提取主类型
        return mimeType.substring(0, slashIndex);
    }
}
