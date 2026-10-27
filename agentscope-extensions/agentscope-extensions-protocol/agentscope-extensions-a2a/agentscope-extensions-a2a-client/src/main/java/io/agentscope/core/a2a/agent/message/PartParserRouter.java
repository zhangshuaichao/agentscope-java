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

import io.agentscope.core.message.ContentBlock;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

/**
 * 按 {@link Part} 类型选择 {@link PartParser} 的路由器。
 */
public class PartParserRouter {

    /**
     * 将 {@link Part} 解析为 {@link ContentBlock}。
     *
     * @param part 待解析的 Part
     * @return 解析后的内容块；Part 为 null 或不受支持时返回 null
     */
    public ContentBlock parse(Part<?> part) {
        if (null == part) {
            return null;
        }
        if (part instanceof TextPart textPart) {
            return new TextPartParser().parse(textPart);
        }
        if (part instanceof FilePart filePart) {
            return new FilePartParser().parse(filePart);
        }
        if (part instanceof DataPart dataPart) {
            return new DataPartParser().parse(dataPart);
        }
        return null;
    }
}
