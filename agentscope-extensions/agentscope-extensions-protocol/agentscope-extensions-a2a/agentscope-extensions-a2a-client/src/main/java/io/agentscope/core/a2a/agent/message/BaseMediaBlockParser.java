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

import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import org.a2aproject.sdk.spec.FileContent;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将媒体内容块解析为 {@link FilePart} 的抽象基类。
 *
 * <p>提供按类型将 {@link Source} 解析为 {@link FileContent} 的共用功能：
 * <ul>
 *   <li>将 {@link Base64Source} 转为 {@link FileWithBytes}</li>
 *   <li>将 {@link URLSource} 转为 {@link FileWithUri}</li>
 * </ul>
 *
 * <p>所有 {@link FileContent} 应包含 {@link FileContent#mimeType()}，
 * 但 {@link Source} 中仅 {@link Base64Source} 提供 {@link Base64Source#getMediaType()}，
 * 因此对于 {@link URLSource}，需要解析 {@link URLSource#getUrl()}
 * 来获取 {@link FileContent#mimeType()}。
 *
 * <p>{@link Source} 未定义 name 字段，因此需要为 {@link FileContent#name()}
 * 生成随机名称，目前使用 {@link UUID}。
 *
 * @param <T> 待解析内容块的类型
 */
public abstract class BaseMediaBlockParser<T extends ContentBlock>
        implements ContentBlockParser<T> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * 获取内容块的媒体类型。
     *
     * @return 媒体类型字符串
     */
    protected abstract String getMediaType();

    /**
     * 将内容块的来源解析为 FilePart。
     *
     * <p>来源类型未知时返回 null。
     *
     * @param source 待解析的来源
     * @return 解析后的 FilePart
     */
    protected Part<?> parseSource(Source source) {
        FileContent file;
        if (source instanceof Base64Source base64Source) {
            file = parseFromBase64Source(base64Source);
        } else if (source instanceof URLSource urlSource) {
            file = parseFromUrlSource(urlSource);
        } else {
            LoggerUtil.warn(log, "Unsupported source type: {}", source.getClass().getName());
            return null;
        }
        return new FilePart(file, new HashMap<>());
    }

    private FileContent parseFromBase64Source(Base64Source source) {
        return new FileWithBytes(source.getMediaType(), generateRandomFileName(), source.getData());
    }

    private FileContent parseFromUrlSource(URLSource urlSource) {
        String url = urlSource.getUrl();
        return new FileWithUri(tryToParseMimeTypeFromUrl(url), generateRandomFileName(), url);
    }

    private String tryToParseMimeTypeFromUrl(String url) {
        try {
            URL javaUrl = new URL(url);
            String mimeType = Files.probeContentType(Paths.get(javaUrl.getPath()));
            if (Objects.isNull(mimeType)) {
                return getMediaType();
            }
            return mimeType.startsWith(getMediaType()) ? mimeType : getMediaType();
        } catch (Exception ignored) {
            return getMediaType();
        }
    }

    private String generateRandomFileName() {
        return UUID.randomUUID().toString();
    }
}
