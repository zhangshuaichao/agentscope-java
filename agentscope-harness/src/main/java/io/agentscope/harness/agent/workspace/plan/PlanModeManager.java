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
package io.agentscope.harness.agent.workspace.plan;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.PlanModeContextState;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.Objects;

/**
 * 协调单个智能体的计划模式。
 *
 * <p>状态（计划模式是否开启、哪个 Markdown 文件保存当前计划）保存在
 * {@link PlanModeContextState} 中，并归属于智能体的 {@link AgentState}，随会话持久化，能够跨重启或节点交接保留。
 * 这使动态运行时计划模式切换可以在分布式环境中生效，而进程内布尔值在此情况下会丢失。
 *
 * <p>计划 Markdown 文件只通过 {@link WorkspaceManager} 写入，绝不直接使用
 * {@code java.nio.file.Files}，因此会落到智能体文件系统配置的本地、沙箱或远程后端。
 * 每个隔离作用域的逻辑路径都保持 {@code <planDir>/PLAN.md}；隔离由文件系统透明应用
 * （远程存储的命名空间、沙箱的快照键），而不是编码到路径中。
 *
 * <p>【学习提示】此类负责计划状态和文件管理；计划模式下哪些工具可调用，
 * 还需要中间件的工具门控配合，不能仅靠状态字段或提示词保证限制生效。
 */
public final class PlanModeManager {

    /** 计划文件的默认工作区相对目录。 */
    public static final String DEFAULT_PLAN_DIR = "plans";

    private final WorkspaceManager workspaceManager;
    private final String planDir;

    public PlanModeManager(WorkspaceManager workspaceManager, String planDir) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
        String dir = planDir == null || planDir.isBlank() ? DEFAULT_PLAN_DIR : planDir.trim();
        // 规范化：去除开头和末尾的斜杠。
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        this.planDir = dir.isEmpty() ? DEFAULT_PLAN_DIR : dir;
    }

    public boolean isPlanActive(AgentState state) {
        return state != null && state.getPlanModeContext().isPlanActive();
    }

    /**
     * 进入计划模式，操作幂等。确保记录计划文件路径，未设置时使用默认值。
     *
     * @return 计划文件的工作区相对路径
     */
    public String enter(AgentState state) {
        Objects.requireNonNull(state, "state");
        PlanModeContextState ctx = state.getPlanModeContext();
        ctx.setPlanActive(true);
        if (ctx.getCurrentPlanFile() == null || ctx.getCurrentPlanFile().isBlank()) {
            ctx.setCurrentPlanFile(defaultPlanFile());
        }
        return ctx.getCurrentPlanFile();
    }

    /** 退出计划模式，回到 BUILD。操作幂等，保留 {@code currentPlanFile} 供参考。 */
    public void exit(AgentState state) {
        Objects.requireNonNull(state, "state");
        state.getPlanModeContext().setPlanActive(false);
    }

    /** 当前与此智能体关联的计划文件的工作区相对路径。 */
    public String planFilePath(AgentState state) {
        if (state != null) {
            String current = state.getPlanModeContext().getCurrentPlanFile();
            if (current != null && !current.isBlank()) {
                return current;
            }
        }
        return defaultPlanFile();
    }

    /**
     * 通过工作区文件系统写入（创建或覆盖）计划 Markdown 文件，并在状态中记录其路径。
     *
     * @return 已写入的工作区相对路径
     */
    public String writePlan(RuntimeContext rc, AgentState state, String content) {
        Objects.requireNonNull(state, "state");
        String path = planFilePath(state);
        RuntimeContext effective = rc != null ? rc : RuntimeContext.empty();
        workspaceManager.writeUtf8WorkspaceRelative(
                effective, path, content == null ? "" : content);
        state.getPlanModeContext().setCurrentPlanFile(path);
        return path;
    }

    private String defaultPlanFile() {
        return planDir + "/PLAN.md";
    }
}
