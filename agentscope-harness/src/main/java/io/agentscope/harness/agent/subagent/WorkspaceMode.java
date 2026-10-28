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
package io.agentscope.harness.agent.subagent;

/**
 * 控制声明式子智能体运行时工作区根目录的确定方式。
 *
 * <p>五行决策表：
 *
 * <pre>
 * workspacePath  mode      runtime-workspace-root
 * ─────────────────────────────────────────────────────────────────────────────
 * set            ISOLATED  workspacePath  (definition dir is also the runtime root)
 * set            SHARED    mainWorkspace  (definition skills/knowledge ignored)
 * null           ISOLATED  mainWorkspace/agents/&lt;name&gt;/workspace/  (auto-created)
 * null           SHARED    mainWorkspace
 * (general-purpose, always SHARED)       mainWorkspace  (fully mirrors main agent)
 * </pre>
 *
 * <p>表格中文说明：指定 workspacePath 且 ISOLATED 时，定义目录也是运行根目录；指定路径且 SHARED 时，运行根为 mainWorkspace，忽略定义目录中的 skills/knowledge。不指定路径且 ISOLATED 时，自动建立 mainWorkspace/agents/名称/workspace；不指定且 SHARED 时使用主工作区。通用子智能体始终 SHARED，完整沿用主智能体工作区。
 *
 * <p><b>阶段 B-0：持久化会话分桶：</b>当启动时的
 * {@link io.agentscope.core.agent.RuntimeContext} 携带 {@code userId} 和
 * {@code parentSessionId} 时，ISOLATED 模式子智能体按父级身份划分持久化状态。
 * 组合键的形式为 {@code {declarationName}[@{parentSessionId}][#{userId}]}，
 * 统一应用于所有 {@link io.agentscope.core.state.AgentStateStore} 存储
 * （Workspace、Redis、InMemory、自定义），因为它们均已按 {@code (userId, sessionId)}
 * 划分状态。SHARED 模式保持不变：这些子智能体有意使用父级的桶，不进行多租户划分。
 */
public enum WorkspaceMode {

    /**
     * 子智能体获得自己的隔离工作区。
     *
     * <ul>
     *   <li>设置 {@link SubagentDeclaration#getWorkspacePath()} 时，该路径既是运行时根目录，
     *       也是系统提示来源（{@code AGENTS.md}）。
     *   <li>否则在 {@code mainWorkspace/agents/&lt;name&gt;/workspace/} 自动创建
     *       运行时根目录，并将内联正文用作系统提示。
     * </ul>
     */
    ISOLATED,

    /**
     * 子智能体与主智能体共享工作区。
     *
     * <ul>
     *   <li>无论 {@link SubagentDeclaration#getWorkspacePath()} 是什么，
     *       运行时根目录始终为 {@code mainWorkspace}。
     *   <li>设置 {@code workspacePath} 时，使用其中的 {@code AGENTS.md} 作为系统提示正文；
     *       但忽略定义目录中的 {@code skills/}、{@code knowledge/} 和 {@code MEMORY.md}。
     *   <li>未设置 {@code workspacePath} 时，将内联正文用作系统提示。
     * </ul>
     */
    SHARED
}
