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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理工具注册和查找的内部注册表。
 *
 * <p>维护工具名称到实现的映射，以及已注册工具函数的元数据，由 {@link Toolkit} 内部用于组织和检索工具。
 *
 * <p><b>线程安全：</b>内部使用 {@link ConcurrentHashMap}，支持并发注册和查找。
 *
 * <p><b>主要职责：</b>
 * <ul>
 * <li>按名称存储和检索 {@link AgentTool} 实现</li>
 * <li>维护生成模式所需的 {@link RegisteredToolFunction} 元数据</li>
 * <li>支持动态移除工具，配合基于工具组的激活机制</li>
 * </ul>
 */
class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final Map<String, RegisteredToolFunction> registeredTools = new ConcurrentHashMap<>();

    /**
     * 注册工具及其元数据。
     *
     * @param toolName 工具名称
     * @param tool AgentTool 实现
     * @param registered 带元数据的 RegisteredToolFunction 包装器
     */
    void registerTool(String toolName, AgentTool tool, RegisteredToolFunction registered) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        tools.put(toolName, tool);
        registeredTools.put(toolName, registered);
    }

    /**
     * 按名称获取工具。
     *
     * @param name 工具名称
     * @return AgentTool；未找到时为 null
     */
    AgentTool getTool(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return tools.get(name);
    }

    /**
     * 按名称获取已注册工具函数。
     *
     * @param name 工具名称
     * @return RegisteredToolFunction；未找到时为 null
     */
    RegisteredToolFunction getRegisteredTool(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return registeredTools.get(name);
    }

    /**
     * 获取全部工具名称。
     *
     * @return 工具名称集合
     */
    Set<String> getToolNames() {
        return new HashSet<>(tools.keySet());
    }

    /**
     * 获取全部已注册工具函数。
     *
     * @return 工具名称到 RegisteredToolFunction 的映射
     */
    Map<String, RegisteredToolFunction> getAllRegisteredTools() {
        return new ConcurrentHashMap<>(registeredTools);
    }

    /**
     * 按名称移除工具。
     *
     * @param toolName 要移除的工具名称
     */
    void removeTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        tools.remove(toolName);
        registeredTools.remove(toolName);
    }

    /**
     * 仅当当前实例与期望实例一致时原子性地移除工具。使用 {@link ConcurrentHashMap#remove(Object, Object)} 避免检查与使用之间的竞态（TOCTOU）。
     *
     * @param toolName 要移除的工具名称
     * @param expected 期望的 AgentTool 实例（按身份比较）
     * @return 移除成功为 true；已被替换或不存在时为 false
     */
    boolean removeToolIfSame(String toolName, AgentTool expected) {
        boolean removed = tools.remove(toolName, expected);
        if (removed) {
            registeredTools.remove(toolName);
        }
        return removed;
    }

    /**
     * 按名称批量移除工具。
     *
     * @param toolNames 要移除的工具名称集合
     */
    void removeTools(Set<String> toolNames) {
        toolNames.forEach(this::removeTool);
    }

    /**
     * 将此注册表的全部工具及注册元数据复制到另一注册表。
     *
     * <p>工具通过引用共享（无状态且线程安全），仅复制注册条目（包括 {@link RegisteredToolFunction} 元数据），为构建期智能体隔离提供独立注册表。
     *
     * @param target 接收工具副本的目标注册表
     */
    void copyTo(ToolRegistry target) {
        for (Map.Entry<String, AgentTool> entry : tools.entrySet()) {
            String toolName = entry.getKey();
            AgentTool tool = entry.getValue();
            RegisteredToolFunction registered = registeredTools.get(toolName);
            target.registerTool(
                    toolName,
                    tool,
                    registered == null
                            ? null
                            : new RegisteredToolFunction(
                                    tool,
                                    registered.getExtendedModel(),
                                    registered.getMcpClientName(),
                                    registered.getPresetParameters()));
        }
    }
}
