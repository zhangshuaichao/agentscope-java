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
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.a2aproject.sdk.spec.DataPart;

/**
 * 将 {@link DataPart} 转换为 {@link ContentBlock} 的解析器。
 *
 * <p>根据元数据，将 {@link DataPart} 转换为不同的 {@link ContentBlock}：
 * <ul>
 *   <li>{@link MessageConstants#BLOCK_TYPE_METADATA_KEY} 为
 *       {@link MessageConstants.BlockContent#TYPE_TOOL_USE} 时，解析为 {@link ToolUseBlock}</li>
 *   <li>{@link MessageConstants#BLOCK_TYPE_METADATA_KEY} 为
 *       {@link MessageConstants.BlockContent#TYPE_TOOL_RESULT} 时，解析为 {@link ToolResultBlock}</li>
 *   <li>没有 {@link MessageConstants#BLOCK_TYPE_METADATA_KEY} 时，解析为 {@link TextBlock}</li>
 * </ul>
 */
public class DataPartParser implements PartParser<DataPart> {

    @Override
    public ContentBlock parse(DataPart part) {
        if (isCommonDataPart(part)) {
            return parseToTextBlock(part);
        }
        return parseToToolBlock(part);
    }

    private boolean isCommonDataPart(DataPart part) {
        if (null == part.metadata()) {
            return true;
        }
        return null == part.metadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY);
    }

    private ContentBlock parseToTextBlock(DataPart part) {
        String dataJsonString = JsonUtils.getJsonCodec().toJson(part.data());
        return TextBlock.builder().text(dataJsonString).build();
    }

    private ContentBlock parseToToolBlock(DataPart part) {
        // 已在 isCommonDataPart() 中确认值存在。
        String blockType = part.metadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY).toString();
        return switch (blockType) {
            case MessageConstants.BlockContent.TYPE_TOOL_USE -> parseToToolUseBlock(part);
            case MessageConstants.BlockContent.TYPE_TOOL_RESULT -> parseToToolResultBlock(part);
            default -> null;
        };
    }

    private ContentBlock parseToToolUseBlock(DataPart part) {
        ToolUseBlock.Builder builder = ToolUseBlock.builder();
        builder.id(getToolCallId(part)).name(getToolName(part));
        builder.metadata(getOriginalMetadata(part));
        builder.input(asMap(part.data()));
        return builder.build();
    }

    private ContentBlock parseToToolResultBlock(DataPart part) {
        ToolResultBlock.Builder builder = ToolResultBlock.builder();
        builder.id(getToolCallId(part)).name(getToolName(part));
        builder.metadata(getOriginalMetadata(part));
        Object output = asMap(part.data()).get(MessageConstants.TOOL_RESULT_OUTPUT_METADATA_KEY);
        if (output instanceof String) {
            // 适配 Python AgentScope ToolResultBlock 定义，Python 工具结果输出规格为
            // `str | List[TextBlock | ImageBlock | AudioBlock | VideoBlock]`
            builder.output(TextBlock.builder().text(output.toString()).build());
        } else if (output instanceof List<?> outputList) {
            builder.output(toContentBlocks(outputList));
        } else {
            builder.output(List.of());
        }
        return builder.build();
    }

    private String getToolCallId(DataPart part) {
        Object toolCallId = part.metadata().get(MessageConstants.TOOL_CALL_ID_METADATA_KEY);
        return null != toolCallId ? toolCallId.toString() : null;
    }

    private String getToolName(DataPart part) {
        Object toolName = part.metadata().get(MessageConstants.TOOL_NAME_METADATA_KEY);
        return null != toolName ? toolName.toString() : null;
    }

    private Map<String, Object> getOriginalMetadata(DataPart part) {
        Map<String, Object> result = new HashMap<>(part.metadata());
        // 移除 AgentScope 内部元数据。
        result.remove(MessageConstants.TOOL_CALL_ID_METADATA_KEY);
        result.remove(MessageConstants.TOOL_NAME_METADATA_KEY);
        result.remove(MessageConstants.BLOCK_TYPE_METADATA_KEY);
        // A2A 元数据可能包含 JSON null；工具结果块不接受 null 值。
        result.values().removeIf(Objects::isNull);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object data) {
        return data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * 重建工具结果内容块。通过网络接收的每个块都是 JSON 对象，
     * 其 {@code type} 标识块类型；未知块保留为 JSON 文本。
     */
    private static List<ContentBlock> toContentBlocks(List<?> items) {
        List<ContentBlock> blocks = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof ContentBlock block) {
                blocks.add(block);
                continue;
            }
            try {
                blocks.add(JsonUtils.getJsonCodec().convertValue(item, ContentBlock.class));
            } catch (JsonException | IllegalArgumentException e) {
                blocks.add(TextBlock.builder().text(JsonUtils.getJsonCodec().toJson(item)).build());
            }
        }
        return blocks;
    }
}
