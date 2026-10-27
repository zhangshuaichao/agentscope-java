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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * 创建元工具的工厂，使智能体能动态管理工具组。
 */
class MetaToolFactory {

    private final ToolGroupManager groupManager;
    private final ToolRegistry toolRegistry;

    MetaToolFactory(ToolGroupManager groupManager, ToolRegistry toolRegistry) {
        this.groupManager = groupManager;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 创建 reset_equipped_tools 元工具。
     *
     * @return 用于 reset_equipped_tools 的 AgentTool
     */
    AgentTool createResetEquippedToolsAgentTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "reset_equipped_tools";
            }

            @Override
            public String getDescription() {
                return "Reset your equipped tools based on your current task requirements. "
                        + "These tools are organized into different groups, and you can "
                        + "activate/deactivate them by specifying which groups to keep "
                        + "active.\n\n"
                        + "**Important: The input list is the FINAL set of active tool "
                        + "groups, not incremental changes.** Any group not included in "
                        + "the list will be deactivated, regardless of its previous "
                        + "state.\n\n"
                        + "**Best practice**: Activate only what you need for the current "
                        + "task, and promptly deactivate groups as soon as they are no "
                        + "longer needed to conserve context space.\n\n"
                        + groupManager.getNotes();
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");

                Map<String, Object> properties = new HashMap<>();
                Map<String, Object> toActivateParam = new HashMap<>();
                toActivateParam.put("type", "array");

                Map<String, Object> items = new HashMap<>();
                items.put("type", "string");

                // 枚举中只包含 META 作用域的工具组
                List<String> availableGroups = new ArrayList<>(groupManager.getMetaGroupNames());
                if (!availableGroups.isEmpty()) {
                    items.put("enum", availableGroups);
                }

                toActivateParam.put("items", items);
                toActivateParam.put(
                        "description",
                        "The FINAL list of tool group names to keep active. "
                                + "Groups NOT in this list will be deactivated. "
                                + "Pass an empty list to deactivate all groups.");

                properties.put("to_activate", toActivateParam);
                schema.put("properties", properties);
                schema.put("required", List.of("to_activate"));

                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                try {
                    @SuppressWarnings("unchecked")
                    List<String> toActivate = (List<String>) param.getInput().get("to_activate");

                    if (toActivate == null) {
                        return Mono.just(
                                ToolResultBlock.error("Missing required parameter: to_activate"));
                    }

                    String result = resetEquippedToolsImpl(toActivate, resolveToolContext(param));
                    return Mono.just(ToolResultBlock.success(result));
                } catch (Exception e) {
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                }
            }
        };
    }

    /**
     * 从工具调用的运行时上下文解析本次调用的 {@link ToolContextState}，使元工具作用于当前会话的激活集合，而非共享工具组管理器。
     *
     * @param param 工具调用参数
     * @return 解析到的工具上下文状态；不可用时为 {@code null}
     */
    private ToolContextState resolveToolContext(ToolCallParam param) {
        RuntimeContext rc = param.getRuntimeContext();
        AgentState state = rc != null ? rc.getAgentState() : null;
        return state != null ? state.getToolContext() : null;
    }

    /**
     * 实现 reset_equipped_tools 逻辑。
     *
     * <p>采用<b>替换语义</b>：停用输入列表之外的全部 META 作用域工具组，EXTERNAL 作用域组不受影响。激活集合始终写入每次调用的 {@link ToolContextState}（会话的唯一事实来源），从不修改共享工具组管理器。缺少会话上下文时，工具快速失败，不触碰共享状态。
     *
     * @param toActivate 要激活的工具组名称列表（必须均为 META 作用域）
     * @param tcs 本次调用的工具上下文状态（不能为 null）
     * @return 描述结果状态的响应消息
     * @throws IllegalArgumentException 当任意工具组不存在时
     */
    private String resetEquippedToolsImpl(List<String> toActivate, ToolContextState tcs) {
        // 验证全部工具组均存在且属于 META 作用域
        for (String groupName : toActivate) {
            groupManager.validateGroupExists(groupName);
            ToolGroup group = groupManager.getToolGroup(groupName);
            if (group.getScope() != ToolGroupScope.META) {
                throw new IllegalArgumentException(
                        "Group '" + groupName + "' is not manageable by this tool.");
            }
        }

        // 缺少会话上下文时，绝不回退到修改共享工具组管理器。缺失
        // 运行时上下文属于集成故障，此处修改共享状态会造成
        // 破坏，因此应快速失败。
        if (tcs == null) {
            throw new IllegalStateException(
                    "reset_equipped_tools requires a per-session runtime context");
        }

        // 唯一事实来源：原子性地保留 EXTERNAL 作用域工具组，并替换 META 作用域
        // 工具组，
        // 避免此次替换丢失并发添加的工具（例如技能激活）。
        tcs.replaceActivatedGroups(groupManager.getMetaGroupNames(), toActivate);

        // 构造响应（与 Python 格式对齐）
        if (toActivate.isEmpty()) {
            // 此处仅停用 META 工具组；保留 EXTERNAL 作用域组。
            return "All META tool groups are currently deactivated.";
        }

        String groupNames = toActivate.stream().collect(Collectors.joining(", "));
        StringBuilder result = new StringBuilder();
        result.append("The currently activated tool group(s): ").append(groupNames).append(".\n");

        // 收集带描述的工具组，用于工具说明块
        List<ToolGroup> activatedGroups = new ArrayList<>();
        for (String groupName : toActivate) {
            ToolGroup group = groupManager.getToolGroup(groupName);
            if (group != null) {
                activatedGroups.add(group);
            }
        }

        boolean hasInstructions =
                activatedGroups.stream()
                        .anyMatch(g -> g.getDescription() != null && !g.getDescription().isEmpty());
        if (hasInstructions) {
            result.append("<tool-instructions>\n");
            for (ToolGroup group : activatedGroups) {
                if (group.getDescription() != null && !group.getDescription().isEmpty()) {
                    result.append(
                            String.format(
                                    "<group name=\"%s\">%s</group>\n",
                                    group.getName(), group.getDescription()));
                }
            }
            result.append("</tool-instructions>");
        }

        return result.toString();
    }
}
