/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.tool;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.Type;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ToolResultConverter 的默认实现，处理带模式信息的 JSON 序列化。
 *
 * <p>此转换器：
 * <ul>
 * <li>将 null 结果转换为 "null" 文本</li>
 * <li>将 void 结果转换为 "Done" 文本</li>
 * <li>原样传递 ToolResultBlock 实例</li>
 * <li>将对象序列化为带模式信息的 JSON</li>
 * <li>序列化失败时回退到 toString()</li>
 * </ul>
 */
public class DefaultToolResultConverter implements ToolResultConverter {

    private static final Logger logger = LoggerFactory.getLogger(DefaultToolResultConverter.class);

    @Override
    public ToolResultBlock convert(Object result, Type returnType) {
        if (result == null) {
            return handleNull();
        }

        if (returnType != null && returnType == Void.TYPE) {
            return handleVoid();
        }

        // 结果已是 ToolResultBlock 时直接返回
        if (result instanceof ToolResultBlock) {
            return (ToolResultBlock) result;
        }

        return serialize(result, returnType);
    }

    /**
     * 处理 null 结果。
     *
     * @return 包含 "null" 文本的 ToolResultBlock
     */
    protected ToolResultBlock handleNull() {
        return ToolResultBlock.of(List.of(TextBlock.builder().text("null").build()));
    }

    /**
     * 处理 void 返回类型。
     *
     * @return 包含 "Done" 文本的 ToolResultBlock
     */
    protected ToolResultBlock handleVoid() {
        return ToolResultBlock.of(List.of(TextBlock.builder().text("Done").build()));
    }

    /**
     * 将结果序列化为带模式信息的 JSON 字符串。
     *
     * @param result 要序列化的结果
     * @param returnType 返回类型
     * @return 包含 JSON 字符串和模式的 ToolResultBlock
     */
    protected ToolResultBlock serialize(Object result, Type returnType) {
        try {
            String json = JsonUtils.getJsonCodec().toJson(result);
            return ToolResultBlock.of(List.of(TextBlock.builder().text(json).build()));
        } catch (Exception e) {
            // 回退到字符串表示
            logger.warn("Failed to serialize result to JSON, falling back to toString()", e);
            return ToolResultBlock.of(
                    List.of(TextBlock.builder().text(String.valueOf(result)).build()));
        }
    }
}
