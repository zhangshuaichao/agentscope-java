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
import io.agentscope.core.util.JsonUtils;
import java.util.HashMap;
import java.util.Map;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

/**
 * 读取和写入 A2A Part 与消息的元数据。
 */
public final class MetadataUtil {

    private MetadataUtil() {}

    /**
     * 构建含 {@link Part} 内容块类型的元数据。
     *
     * @param type 内容块类型，参见 {@link ContentBlock}
     * @return 含内容块类型的元数据
     */
    public static Map<String, Object> buildTypeMetadata(String type) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(MessageConstants.BLOCK_TYPE_METADATA_KEY, type);
        return metadata;
    }

    /**
     * 构建工具块元数据。保留条目覆盖用户条目，防止调用方伪造工具调用的传输表示。
     *
     * @param type 工具块类型
     * @param toolName 工具名称
     * @param toolCallId 工具调用 ID
     * @param userMetadata 调用方提供的元数据
     * @return 包含保留工具条目的元数据
     */
    static Map<String, Object> toolMetadata(
            String type, String toolName, String toolCallId, Map<String, Object> userMetadata) {
        Map<String, Object> metadata =
                new HashMap<>(null == userMetadata ? Map.of() : userMetadata);
        // 【学习提示】先复制用户元数据，再写入协议保留字段，确保用户同名键不能篡改
        // 工具名称、调用 ID 或块类型；这是序列化边界，不是远程调用方身份认证。
        metadata.put(MessageConstants.BLOCK_TYPE_METADATA_KEY, type);
        metadata.put(MessageConstants.TOOL_NAME_METADATA_KEY, toolName);
        metadata.put(MessageConstants.TOOL_CALL_ID_METADATA_KEY, toolCallId);
        return metadata;
    }

    /**
     * 获取 {@link Part} 的元数据。
     *
     * @param part Part
     * @return 元数据；不存在时为 {@code null}
     */
    public static Map<String, Object> metadataOf(Part<?> part) {
        if (part instanceof TextPart textPart) {
            return textPart.metadata();
        }
        if (part instanceof DataPart dataPart) {
            return dataPart.metadata();
        }
        if (part instanceof FilePart filePart) {
            return filePart.metadata();
        }
        return null;
    }

    /**
     * 以字符串形式获取 {@link Part} 的元数据值。
     *
     * @param part Part
     * @param key 元数据键
     * @return 值；Part 不含此值时为 {@code null}
     */
    public static String metadataValue(Part<?> part, String key) {
        Map<String, Object> metadata = metadataOf(part);
        Object value = null == metadata ? null : metadata.get(key);
        return null == value ? null : value.toString();
    }

    /**
     * 复制 {@link Part}，并将 {@code extra} 添加到其元数据。Part 不可变。
     * 元数据及 {@link DataPart} 数据通过 {@link #toJsonValues(Map)} 转换。
     *
     * @param part 待复制的 Part
     * @param extra 待添加的元数据，覆盖同名键
     * @return 副本
     */
    public static Part<?> withMetadata(Part<?> part, Map<String, Object> extra) {
        Map<String, Object> metadata = new HashMap<>();
        if (metadataOf(part) != null) {
            metadata.putAll(metadataOf(part));
        }
        metadata.putAll(extra);
        if (part instanceof TextPart textPart) {
            return new TextPart(textPart.text(), toJsonValues(metadata));
        }
        if (part instanceof DataPart dataPart) {
            return new DataPart(toJsonValue(dataPart.data()), toJsonValues(metadata));
        }
        if (part instanceof FilePart filePart) {
            return new FilePart(filePart.file(), toJsonValues(metadata));
        }
        return part;
    }

    /**
     * 将值转换为 JSON 值：映射、列表、字符串、数字、布尔值和 {@code null}。
     * A2A SDK 会将 POJO、枚举等其他对象写为 {@code null}。
     *
     * @param values 待转换的值
     * @return 转换后的值；{@code values} 为 {@code null} 时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toJsonValues(Map<String, Object> values) {
        return (Map<String, Object>) toJsonValue(values);
    }

    private static Object toJsonValue(Object value) {
        return JsonUtils.getJsonCodec().convertValue(value, Object.class);
    }
}
