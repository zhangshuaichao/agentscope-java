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
package io.agentscope.spring.boot.admin.subagent;

import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import java.util.List;

/**
 * 已注册子智能体的传输视图，将资源适配器持有的投影展开：可以是已构建智能体的 {@link SubagentEntry}，也可以是从 YAML／Markdown 规范加载的 {@link SubagentDeclaration}。
 *
 * @param name 唯一子智能体名称／agent-id
 * @param description 供编排智能体判断何时委托
 * @param source 条目来源：{@code spring-bean} / {@code declaration}
 * @param workspaceMode {@link WorkspaceMode#ISOLATED} 或 {@link WorkspaceMode#SHARED}，可为 null
 * @param workspacePath 可选磁盘子智能体定义路径
 * @param model 可选覆盖模型 ID
 * @param maxIters 每次调用迭代上限，未指定时为 0
 * @param toolAllowlist 非空时，仅列表中命名的继承父工具对子智能体可见
 */
public record SubagentDescriptor(
        String name,
        String description,
        String source,
        String workspaceMode,
        String workspacePath,
        String model,
        int maxIters,
        List<String> toolAllowlist) {

    public static SubagentDescriptor of(SubagentEntry e) {
        return new SubagentDescriptor(
                e.name(), e.description(), "spring-bean", null, null, null, 0, List.of());
    }

    public static SubagentDescriptor of(SubagentDeclaration d) {
        WorkspaceMode mode = d.getWorkspaceMode();
        return new SubagentDescriptor(
                d.getName(),
                d.getDescription(),
                "declaration",
                mode == null ? null : mode.name(),
                d.getWorkspacePath() == null ? null : d.getWorkspacePath().toString(),
                d.getModel(),
                d.getMaxIters(),
                safeAllowlist(d));
    }

    private static List<String> safeAllowlist(SubagentDeclaration d) {
        try {
            // 不同 core 版本可能使用不同字段名，以防御式反射读取。
            var m = d.getClass().getMethod("getToolAllowlist");
            Object v = m.invoke(d);
            if (v instanceof List<?> raw) {
                List<String> out = new java.util.ArrayList<>(raw.size());
                for (Object o : raw) out.add(String.valueOf(o));
                return List.copyOf(out);
            }
        } catch (ReflectiveOperationException ignored) {
            // 方法缺失，属于较旧 core，继续后续分支
        }
        return List.of();
    }
}
