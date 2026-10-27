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
package io.agentscope.core.agent.user;

import io.agentscope.core.message.ContentBlock;
import java.util.List;
import java.util.Map;

/**
 * 以双重表示保存用户输入信息的数据类。
 * 同时包含内容块（用于构建消息）及可选的结构化数据（用于类型化输入校验），
 * 使统一输入系统能够灵活处理简单文本及复杂结构化表单。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class UserInputData {

    private final List<ContentBlock> blocksInput;
    private final Map<String, Object> structuredInput;

    /**
     * 创建新的 UserInputData 实例。
     *
     * @param blocksInput 表示用户输入的内容块列表
     * @param structuredInput 用于类型化输入的可选结构化数据映射（可以为 null）
     */
    public UserInputData(List<ContentBlock> blocksInput, Map<String, Object> structuredInput) {
        this.blocksInput = blocksInput;
        this.structuredInput = structuredInput;
    }

    /**
     * 获取表示用户输入的内容块。
     *
     * <p>内容块可包含文本、图像、音频等多模态内容，适合用于构建消息对象。
     *
     * @return 内容块列表（可以为 null 或空）
     */
    public List<ContentBlock> getBlocksInput() {
        return blocksInput;
    }

    /**
     * 以键值映射获取结构化输入数据。
     *
     * <p>此可选表示支持类型化输入校验和复杂表单处理，例如可用映射表示包含姓名、年龄、邮箱字段的表单。
     *
     * @return 结构化输入数据映射；未提供时为 null
     */
    public Map<String, Object> getStructuredInput() {
        return structuredInput;
    }
}
