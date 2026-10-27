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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import java.lang.reflect.Type;

/**
 * 将工具方法返回值转换为 ToolResultBlock。自定义实现可为特定工具覆写转换逻辑。
 *
 * <p>用户可定制工具结果的转换和呈现方式，包括 JSON 序列化、元数据添加、敏感数据过滤或大型输出压缩。
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * public class CustomConverter implements ToolResultConverter {
 *     @Override
 *     public ToolResultBlock convert(Object result, Type returnType) {
 *         // 自定义转换逻辑
 *         return ToolResultBlock.of(...);
 *     }
 * }
 *
 * @Tool(
 *     name = "my_tool",
 *     converter = CustomConverter.class
 * )
 * public String myTool() { ... }
 * }</pre>
 *
 * @see DefaultToolResultConverter
 */
public interface ToolResultConverter {

    /**
     * 将工具调用结果转换为 ToolResultBlock。
     *
     * @param result 工具调用结果（可为 null）
     * @param returnType 工具方法返回类型（可为 null）
     * @return 包含转换结果的 ToolResultBlock（永不为 null）
     */
    ToolResultBlock convert(Object result, Type returnType);
}
