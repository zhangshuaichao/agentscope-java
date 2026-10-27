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

package io.agentscope.core.training.runner;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import java.lang.reflect.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过反射提取智能体配置，再用构建器重建实例并替换模型。
 * <p>新建 ReActAgent 有独立的空对话状态，但并非完整深克隆：仅复制这里显式读取的配置字段。
 * <p>【学习提示】toolkit 与若干配置／上下文按引用复用，不是“无任何共享状态”。
 * 影子智能体调用真实工具仍可能产生外部副作用；新建 AgentState 不能自动隔离文件、网络或业务数据。
 */
public class AgentCloner {
    private static final Logger logger = LoggerFactory.getLogger(AgentCloner.class);

    /**
     * 克隆智能体并替换模型。
     * @param original 原智能体
     * @param newModel 新模型，例如 TrinityModelWrapper
     * @return 新建智能体实例
     */
    public static Agent cloneWithModel(Agent original, Model newModel) {
        if (original instanceof ReActAgent) {
            return cloneReActAgent((ReActAgent) original, newModel);
        }

        // TODO：支持其他智能体类型。
        throw new UnsupportedOperationException(
                "Agent cloning not implemented for: "
                        + original.getClass().getSimpleName()
                        + ". Please implement cloning logic for this agent type.");
    }

    /**
     * 克隆 ReActAgent。
     */
    private static ReActAgent cloneReActAgent(ReActAgent original, Model newModel) {
        try {
            logger.debug("Cloning ReActAgent: {}", original.getName());

            // 提取配置字段。
            String sysPrompt = extractField(original, "sysPrompt");
            Toolkit toolkit = extractField(original, "toolkit");
            Integer maxIters = extractField(original, "maxIters");
            ExecutionConfig modelExecutionConfig = extractField(original, "modelExecutionConfig");
            ExecutionConfig toolExecutionConfig = extractField(original, "toolExecutionConfig");
            ToolExecutionContext toolExecutionContext =
                    extractField(original, "toolExecutionContext");

            // 通过构建器重建智能体。
            // v2 的 ReActAgent 不再接收外部 Memory，AgentState 由自身持有，
            // 新实例默认以空上下文开始，但复用的工具对象仍可能共享外部资源。
            ReActAgent.Builder builder =
                    ReActAgent.builder()
                            .name(original.getName() + "-shadow")
                            .description(original.getDescription())
                            .sysPrompt(sysPrompt)
                            .model(newModel) // 替换模型。
                            .toolkit(toolkit);

            // 设置可选字段。
            if (maxIters != null) {
                builder.maxIters(maxIters);
            }
            if (modelExecutionConfig != null) {
                builder.modelExecutionConfig(modelExecutionConfig);
            }
            if (toolExecutionConfig != null) {
                builder.toolExecutionConfig(toolExecutionConfig);
            }
            if (toolExecutionContext != null) {
                builder.toolExecutionContext(toolExecutionContext);
            }

            ReActAgent shadowAgent = builder.build();

            logger.debug(
                    "Successfully cloned ReActAgent: {} -> {}",
                    original.getName(),
                    shadowAgent.getName());

            return shadowAgent;

        } catch (Exception e) {
            throw new RuntimeException("Failed to clone ReActAgent: " + original.getName(), e);
        }
    }

    /**
     * 通过反射提取私有字段；仅检查运行时类的声明字段，不递归查找父类。
     */
    @SuppressWarnings("unchecked")
    private static <T> T extractField(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(obj);
        } catch (NoSuchFieldException e) {
            logger.warn("Field not found: {} in {}", fieldName, obj.getClass().getSimpleName());
            return null;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to extract field: " + fieldName + " from " + obj.getClass(), e);
        }
    }
}
