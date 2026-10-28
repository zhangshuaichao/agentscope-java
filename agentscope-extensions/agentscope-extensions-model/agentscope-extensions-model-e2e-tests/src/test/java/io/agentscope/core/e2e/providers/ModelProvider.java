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
package io.agentscope.core.e2e.providers;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.Set;

/** 使用不同模型配置创建 ReActAgent 的提供方接口。<p>实现为不同 API（OpenAI 原生、DashScope 原生/兼容、百炼）提供正确配置的智能体。 */
public interface ModelProvider {

    /**
     * 使用指定配置创建 ReActAgent。
     * @param name 智能体名称
     * @param toolkit 使用的工具包
     * @return 已配置的 ReActAgent
     */
    ReActAgent createAgent(String name, Toolkit toolkit);

    /**
     * 使用指定配置创建 ReActAgent.Builder，供继续定制智能体（例如添加 Hook、记忆）。
     * @param name 智能体名称
     * @param toolkit 使用的工具包
     * @return 可继续配置的 ReActAgent.Builder
     */
    ReActAgent.Builder createAgentBuilder(String name, Toolkit toolkit);

    /**
     * 获取提供方显示名。
     * @return 提供方名称
     */
    String getProviderName();

    /**
     * 检查提供方是否支持思考模式。
     * @return 支持时为 true
     */
    boolean supportsThinking();

    /**
     * 检查提供方是否已启用，即具有所需 API 密钥。
     * @return 提供方可用于测试时为 true
     */
    boolean isEnabled();

    /**
     * 获取提供方使用的模型名。
     * @return 模型名
     */
    String getModelName();

    /**
     * 检查提供方是否支持工具调用（函数调用）。
     * <p>本测试矩阵中的某些视觉模型（例如 GLM-4V）未声明工具调用能力。
     * @return 支持工具调用时为 true
     */
    default boolean supportsToolCalling() {
        return true;
    }

    /**
     * 获取提供方支持的能力集合。
     * <p>默认实现优先读取 {@link ModelCapabilities} 注解，否则返回基本能力集合。
     * @return 支持的能力集合
     */
    default Set<ModelCapability> getCapabilities() {
        ModelCapabilities annotation = this.getClass().getAnnotation(ModelCapabilities.class);
        if (annotation != null) {
            return Set.of(annotation.value());
        }
        // 回退为从已有方法推导能力
        Set<ModelCapability> capabilities = new HashSet<>();
        capabilities.add(ModelCapability.BASIC);
        if (supportsToolCalling()) {
            capabilities.add(ModelCapability.TOOL_CALLING);
        }
        if (supportsThinking()) {
            capabilities.add(ModelCapability.THINKING);
        }
        return capabilities;
    }

    /**
     * 检查是否具备全部指定能力。
     * @param required 所需能力
     * @return 全部支持时为 true
     */
    default boolean hasCapabilities(ModelCapability... required) {
        Set<ModelCapability> capabilities = getCapabilities();
        for (ModelCapability cap : required) {
            if (!capabilities.contains(cap)) {
                return false;
            }
        }
        return true;
    }
}
