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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 带有模式扩展和执行元数据的 AgentTool 包装器。
 *
 * <p>包装 AgentTool，并添加扩展模式信息、MCP 客户端关联和预设参数等元数据。
 */
class RegisteredToolFunction {

    private final AgentTool tool;
    private final ExtendedModel extendedModel; // 无扩展时为 null
    private final String mcpClientName; // 非 MCP 工具为 null
    private volatile Map<String, Object>
            presetParameters; // 用于上下文注入的预设参数

    /**
     * 规范化预设参数映射，保证返回值非 null。
     *
     * @param params 预设参数（可为 null）
     * @return params 的新 HashMap 副本；为 null 时返回空映射
     */
    private static Map<String, Object> normalizePresetParameters(Map<String, Object> params) {
        return params != null ? new HashMap<>(params) : Collections.emptyMap();
    }

    /**
     * 创建带元数据的已注册工具函数。
     *
     * @param tool 底层智能体工具
     * @param extendedModel 用于模式扩展的扩展模型（无扩展时为 null）
     * @param mcpClientName MCP 工具的客户端名称（非 MCP 工具为 null）
     */
    public RegisteredToolFunction(
            AgentTool tool, ExtendedModel extendedModel, String mcpClientName) {
        this(tool, extendedModel, mcpClientName, null);
    }

    /**
     * 创建带元数据和预设参数的已注册工具函数。
     *
     * @param tool 底层智能体工具
     * @param extendedModel 用于模式扩展的扩展模型（无扩展时为 null）
     * @param mcpClientName MCP 工具的客户端名称（非 MCP 工具为 null）
     * @param presetParameters 工具执行时自动注入的预设参数（无预设参数时为 null）
     */
    public RegisteredToolFunction(
            AgentTool tool,
            ExtendedModel extendedModel,
            String mcpClientName,
            Map<String, Object> presetParameters) {
        this.tool = tool;
        this.extendedModel = extendedModel;
        this.mcpClientName = mcpClientName;
        this.presetParameters = normalizePresetParameters(presetParameters);
    }

    /**
     * 获取底层智能体工具。
     *
     * @return 智能体工具实例
     */
    public AgentTool getTool() {
        return tool;
    }

    /**
     * 获取用于模式扩展的扩展模型。
     *
     * @return 扩展模型；无扩展时为 null
     */
    public ExtendedModel getExtendedModel() {
        return extendedModel;
    }

    /**
     * 获取 MCP 工具的客户端名称。
     *
     * @return MCP 客户端名称；非 MCP 工具为 null
     */
    public String getMcpClientName() {
        return mcpClientName;
    }

    /**
     * 获取工具的预设参数。
     *
     * <p>预设参数在工具执行时自动注入，不出现在 JSON 模式中。适合传递 API 密钥、用户 ID 或会话信息等上下文。
     *
     * @return 预设参数映射的副本（永不为 null，可以为空）
     */
    public Map<String, Object> getPresetParameters() {
        return new HashMap<>(presetParameters);
    }

    /**
     * 在运行时更新工具的预设参数。
     *
     * <p>无需重新注册工具即可动态修改预设参数，适合更新会话上下文或凭据。
     *
     * @param newPresetParameters 新预设参数（null 视为空映射）
     */
    public void updatePresetParameters(Map<String, Object> newPresetParameters) {
        this.presetParameters = normalizePresetParameters(newPresetParameters);
    }

    /**
     * 将基础参数与扩展模型合并，获取扩展 JSON 模式。
     *
     * @return 合并后的参数模式
     * @throws IllegalStateException 当属性冲突时
     */
    public Map<String, Object> getExtendedParameters() {
        if (extendedModel == null) {
            return tool.getParameters();
        }
        return extendedModel.mergeWithBaseSchema(tool.getParameters());
    }
}
