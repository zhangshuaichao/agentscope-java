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

import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 为模型提供多种格式的工具模式。
 *
 * <p>生成发送给模型的工具模式，告知其可用工具。按激活工具组筛选，仅包含激活组中的工具或未分组工具。
 *
 * <p><b>主要职责：</b>
 * <ul>
 * <li>生成 OpenAI 格式的工具模式（基于 Map）</li>
 * <li>生成供模型 API 使用的 {@link ToolSchema} 对象</li>
 * <li>根据 {@link ToolGroupManager} 激活状态筛选</li>
 * <li>使用 {@link RegisteredToolFunction} 扩展参数保证模式准确</li>
 * </ul>
 *
 * <p><b>筛选逻辑：</b>工具当且仅当满足以下条件之一时纳入模式：
 * <ul>
 * <li>未分组，即未分配给任何工具组</li>
 * <li>至少属于一个当前已激活的组</li>
 * </ul>
 */
class ToolSchemaProvider {

    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;

    /**
     * 使用给定注册表和工具组管理器创建 ToolSchemaProvider。
     *
     * @param toolRegistry 用于检索已注册工具的注册表
     * @param groupManager 用于检查工具组激活状态的管理器
     */
    ToolSchemaProvider(ToolRegistry toolRegistry, ToolGroupManager groupManager) {
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
    }

    /**
     * 获取供模型使用的 ToolSchema 对象，遵循激活工具组的筛选规则。
     *
     * @return ToolSchema 对象列表
     */
    List<ToolSchema> getToolSchemas() {
        return buildSchemas(groupManager.getActiveToolNames());
    }

    /**
     * 根据显式提供的激活组名称筛选工具模式，不依赖共享的逐组激活标志，是 {@link #getToolSchemas()} 的逐调用、无状态版本。
     *
     * @param activeGroups 视为激活的组名称
     * @return 对给定组可见的 ToolSchema 列表（包括全部未分组工具）
     */
    List<ToolSchema> getToolSchemas(Collection<String> activeGroups) {
        return buildSchemas(groupManager.getActiveToolNames(activeGroups));
    }

    private List<ToolSchema> buildSchemas(Set<String> activeTools) {
        List<ToolSchema> schemas = new ArrayList<>();
        List<RegisteredToolFunction> registeredTools =
                new ArrayList<>(toolRegistry.getAllRegisteredTools().values());

        for (RegisteredToolFunction registered : registeredTools) {
            AgentTool tool = registered.getTool();
            String toolName = tool.getName();

            // 筛选：包含未分组工具或任意激活组中的工具
            if (groupManager.isGroupedTool(toolName) && !activeTools.contains(toolName)) {
                continue; // 跳过未激活的已分组工具
            }

            ToolSchema schema =
                    ToolSchema.builder()
                            .name(toolName)
                            .description(tool.getDescription())
                            .parameters(registered.getExtendedParameters())
                            .strict(tool.getStrict())
                            .deferLoading(tool.getDeferLoading())
                            .outputSchema(tool.getOutputSchema())
                            .build();
            schemas.add(schema);
        }

        return schemas;
    }
}
