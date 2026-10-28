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
package io.agentscope.core.tool.test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具测试辅助方法。
 *
 * <p>提供创建工具参数、验证响应与测试执行的辅助方法。
 */
public class ToolTestUtils {

    /**
     * 创建简单工具参数。
     */
    public static Map<String, Object> createSimpleParams(String key, Object value) {
        Map<String, Object> params = new HashMap<>();
        params.put(key, value);
        return params;
    }

    /**
     * 创建复杂工具参数。
     */
    public static Map<String, Object> createComplexParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("string_param", "test value");
        params.put("int_param", 42);
        params.put("double_param", 3.14);
        params.put("boolean_param", true);
        params.put("array_param", new String[] {"a", "b", "c"});
        return params;
    }

    /**
     * 验证工具响应。
     */
    public static boolean isValidToolResultBlock(ToolResultBlock response) {
        return response != null && response.getOutput() != null && !response.getOutput().isEmpty();
    }

    /**
     * 通过检查元数据判断工具响应是否含错误。
     */
    public static boolean isErrorResponse(ToolResultBlock response) {
        if (response == null || response.getMetadata() == null) {
            return false;
        }
        Object error = response.getMetadata().get("error");
        return error != null && Boolean.TRUE.equals(error);
    }

    /**
     * 创建带文本内容的成功响应。
     */
    public static ToolResultBlock createSuccessResponse(String content) {
        return ToolResultBlock.text(content);
    }

    /**
     * 创建带错误消息的错误响应。
     */
    public static ToolResultBlock createErrorResponse(String errorMessage) {
        return ToolResultBlock.error(errorMessage);
    }

    /**
     * 从响应提取字符串内容。
     */
    public static String extractContent(ToolResultBlock response) {
        if (response == null || response.getOutput() == null || response.getOutput().isEmpty()) {
            return null;
        }

        if (response.getOutput().get(0) instanceof TextBlock) {
            return ((TextBlock) response.getOutput().get(0)).getText();
        }

        return null;
    }

    private ToolTestUtils() {
        // 工具类
    }
}
