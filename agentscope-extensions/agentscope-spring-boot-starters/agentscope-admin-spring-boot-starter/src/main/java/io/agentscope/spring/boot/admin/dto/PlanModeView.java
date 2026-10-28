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
package io.agentscope.spring.boot.admin.dto;

import io.agentscope.core.state.PlanModeContextState;

/**
 * {@link PlanModeContextState} 的传输视图，以及表示构建智能体时是否接入 {@code PlanModeMiddleware} 的服务端标记。
 *
 * <p>{@code planMiddlewareEnabled=false} 表示切换 {@code planActive} 只修改 AgentState 标记；由于构建时未使用 {@code Builder.enablePlanMode()}，只读执行链不存在。用于诊断为何“计划模式”期间仍发生写入。
 */
public record PlanModeView(
        String sessionId,
        boolean planActive,
        String currentPlanFile,
        boolean planMiddlewareEnabled) {

    public static PlanModeView of(
            String sessionId, PlanModeContextState state, boolean middlewareEnabled) {
        if (state == null) {
            return new PlanModeView(sessionId, false, null, middlewareEnabled);
        }
        return new PlanModeView(
                sessionId, state.isPlanActive(), state.getCurrentPlanFile(), middlewareEnabled);
    }
}
