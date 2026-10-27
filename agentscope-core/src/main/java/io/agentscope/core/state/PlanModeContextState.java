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
package io.agentscope.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/**
 * 智能体持久化的规划模式标记。
 *
 * <p>规划模式是只读的“先设计”阶段：当 {@link #planActive} 为 {@code true} 时，
 * {@code PlanModeMiddleware} 会拒绝修改型工具调用，让智能体能够调研并起草计划，
 * 而不实际修改内容。{@link #currentPlanFile} 是正在编辑的 Markdown 方案文件
 * 相对于工作区的路径（如果存在）。
 *
 * <p>此状态放在 {@link AgentState} 内，而不是易失的进程内存中，因为 AgentScope
 * 智能体支持分布式执行和恢复：规划模式会话必须能够跨进程重启和节点交接存续。
 * 因此，切换该标记并持久化 {@link AgentState} 是运行时动态切换模式的标准方式。
 */
@JsonPropertyOrder({"plan_active", "current_plan_file"})
public final class PlanModeContextState {

    private boolean planActive;
    private String currentPlanFile;

    public PlanModeContextState() {
        this(false, null);
    }

    @JsonCreator
    public PlanModeContextState(
            @JsonProperty("plan_active") boolean planActive,
            @JsonProperty("current_plan_file") String currentPlanFile) {
        this.planActive = planActive;
        this.currentPlanFile = currentPlanFile;
    }

    @JsonProperty("plan_active")
    public boolean isPlanActive() {
        return planActive;
    }

    public void setPlanActive(boolean planActive) {
        this.planActive = planActive;
    }

    @JsonProperty("current_plan_file")
    public String getCurrentPlanFile() {
        return currentPlanFile;
    }

    public void setCurrentPlanFile(String currentPlanFile) {
        this.currentPlanFile = currentPlanFile;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlanModeContextState other)) {
            return false;
        }
        return planActive == other.planActive
                && Objects.equals(currentPlanFile, other.currentPlanFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(planActive, currentPlanFile);
    }

    @Override
    public String toString() {
        return "PlanModeContextState{planActive="
                + planActive
                + ", currentPlanFile="
                + currentPlanFile
                + '}';
    }
}
