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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;

/**
 * 将 {@link ToolResultBlock} 转为 {@link DataPart} 的解析器。
 */
public class ToolResultBlockParser implements ContentBlockParser<ToolResultBlock> {

    @Override
    public Part<?> parse(ToolResultBlock contentBlock) {
        Map<String, Object> metadata =
                MetadataUtil.toolMetadata(
                        MessageConstants.BlockContent.TYPE_TOOL_RESULT,
                        contentBlock.getName(),
                        contentBlock.getId(),
                        contentBlock.getMetadata());
        // 使用普通 JSON 值：SDK 通过 Gson 写入数据，会忽略块类型
        // 注解。
        // 逐个转换块，确保写出其类型。
        List<Object> outputJson =
                contentBlock.getOutput().stream()
                        .map(block -> JsonUtils.getJsonCodec().convertValue(block, Object.class))
                        .toList();
        Map<String, Object> output =
                Map.of(MessageConstants.TOOL_RESULT_OUTPUT_METADATA_KEY, outputJson);
        return new DataPart(output, metadata);
    }
}
