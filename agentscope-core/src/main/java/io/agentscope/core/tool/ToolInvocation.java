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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 即将执行的单次工具调用快照，捕获于模式验证和预设合并之后、真实执行之前。
 *
 * <p>拦截器 {@link ToolCallInterceptor} 观察此快照，并通过 {@link #withInput(Map)} 决定工具实际接收的输入。{@code input} 映射是允许 {@code null} 值（例如有效 JSON 参数 {@code {"options": null}}）且保留模型 JSON 键顺序的不可变视图。副本为浅复制，嵌套映射和列表与调用方共享，拦截器不得原地修改它们。
 *
 * <p>调用未携带运行时上下文且工具集未配置默认上下文时，{@code runtimeContext} 为 {@code null}；实现必须检查 null。
 *
 * <p>为支持逐智能体分派和审计，快照携带调用方身份（{@code agentId} / {@code agentName}）；无智能体的直接 {@link Toolkit#callTool} 调用中二者均为 {@code null}。有意只提供身份，不公开 {@code Agent} 句柄，避免拦截器执行生命周期操作（重新进入同会话智能体会在调用门禁处死锁），并保持其 Toolkit 层职责；智能体循环相关逻辑应放在中间件中。
 *
 * @param toolName 被调用的工具名称
 * @param tool 已解析工具实例，可访问 {@link AgentTool#isReadOnly()} 等元数据
 * @param input 最终合并输入（模型参数与预设参数）
 * @param runtimeContext 调用方身份上下文；不可用时为 {@code null}
 * @param agentId 调用方智能体唯一 ID；无智能体的直接调用中为 {@code null}
 * @param agentName 调用方智能体业务名称；无智能体的直接调用中为 {@code null}
 * @author shanhongyu
 */
public record ToolInvocation(
        String toolName,
        AgentTool tool,
        Map<String, Object> input,
        RuntimeContext runtimeContext,
        String agentId,
        String agentName) {

    public ToolInvocation {
        input = copyInput(input);
    }

    /**
     * 返回替换了输入的调用副本。
     *
     * @param newInput 替换输入映射（可为 {@code null} 或包含 {@code null} 值）
     * @return 其他组成部分不变的新调用
     */
    public ToolInvocation withInput(Map<String, Object> newInput) {
        return new ToolInvocation(toolName, tool, newInput, runtimeContext, agentId, agentName);
    }

    /**
     * 创建允许 null、保留顺序且不可修改的输入副本。
     *
     * @param input 原始输入映射，可为 {@code null}
     * @return 不可修改副本；输入为 {@code null} 或空时返回空映射
     */
    private static Map<String, Object> copyInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        // 不使用 Map.copyOf：它拒绝 null 值，且不保证迭代顺序。
        // LinkedHashMap 允许 null 并保留 JSON 键顺序，防御性复制的信任
        // 边界与 ToolUseBlock / ToolCallParam 一致。
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
