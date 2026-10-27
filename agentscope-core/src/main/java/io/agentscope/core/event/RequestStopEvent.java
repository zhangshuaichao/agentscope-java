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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.GenerateReason;

/**
 * 请求智能体在当前阶段完成后暂停执行。
 *
 * <p>可从 {@link io.agentscope.core.middleware.MiddlewareBase} 中发出此事件
 * （例如在 {@code onReasoning} 或 {@code onActing} 内），以停止智能体循环。
 * 智能体先完成正在执行的步骤，再返回一个 {@code Msg}，其 {@link GenerateReason}
 * 被设为 {@code generateReason}（默认为 {@link GenerateReason#MIDDLEWARE_STOP_REQUESTED}）。
 *
 * <p>待处理状态通过 {@link io.agentscope.core.message.ToolCallState} 持久化在
 * {@code AgentState.context} 中，因此调用方可再次执行 {@code agent.call(...)} 恢复：
 * 待处理的工具调用会自动被识别，执行随之继续。
 *
 * <p>典型用途：
 * <ul>
 *   <li>成本／预算保护（词元用量超过阈值时停止）
 *   <li>审计／合规检查点（破坏性操作前进行人工审核）
 *   <li>调试单步执行（每轮暂停）
 *   <li>内容审核关卡
 * </ul>
 */
public class RequestStopEvent extends AgentEvent {

    private final String reason;
    private final GenerateReason generateReason;

    @JsonCreator
    public RequestStopEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("reason") String reason,
            @JsonProperty("generateReason") GenerateReason generateReason) {
        super(id, createdAt);
        this.reason = reason;
        this.generateReason =
                generateReason != null ? generateReason : GenerateReason.MIDDLEWARE_STOP_REQUESTED;
    }

    /**
     * 使用默认的 {@link GenerateReason#MIDDLEWARE_STOP_REQUESTED} 创建停止请求。
     *
     * @param reason 供人阅读的停止请求原因说明
     */
    public RequestStopEvent(String reason) {
        this(reason, GenerateReason.MIDDLEWARE_STOP_REQUESTED);
    }

    /**
     * 使用显式生成原因创建停止请求。权限 HITL 流程在内部用它呈现
     * {@link GenerateReason#PERMISSION_ASKING}。
     *
     * @param reason 供人阅读的说明
     * @param generateReason 要设置在返回 Msg 上的原因
     */
    public RequestStopEvent(String reason, GenerateReason generateReason) {
        this.reason = reason;
        this.generateReason =
                generateReason != null ? generateReason : GenerateReason.MIDDLEWARE_STOP_REQUESTED;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.REQUEST_STOP;
    }

    /** 供人阅读的停止请求原因说明。 */
    public String getReason() {
        return reason;
    }

    /** 要赋给返回 Msg 的 {@link GenerateReason}。 */
    public GenerateReason getGenerateReason() {
        return generateReason;
    }
}
