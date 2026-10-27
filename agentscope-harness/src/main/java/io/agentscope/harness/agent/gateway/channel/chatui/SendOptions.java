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
package io.agentscope.harness.agent.gateway.channel.chatui;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Objects;

/**
 * {@link ChatUiChannel} 请求的路由身份。调用方使用简单业务标识表达路由意图，
 * 无需理解 {@link io.agentscope.harness.agent.gateway.channel.DmScope}。
 *
 * <ul>
 *   <li>{@code userId} — 标识用户，映射到
 *       {@link io.agentscope.harness.agent.gateway.MsgContext#userId()}，用于 HarnessAgent 命名空间隔离；
 *       {@code sessionId} 为 null 时也用作默认会话键。
 *   <li>{@code sessionId} — 可选的明确对话标识；提供时，同一用户的不同会话彼此分开。
 *       为 null 时，默认每用户一个会话。
 *   <li>{@code agentId} — 多智能体配置中的可选目标覆盖；为 null 时使用渠道默认智能体。
 *   <li>{@code runtimeContext} — 合并到智能体轮次的可选调用级 {@link RuntimeContext}
 *       （属性、类型化值、强制同步标志等）。冲突时网关身份字段仍优先。
 * </ul>
 *
 * <h2>用法</h2>
 *
 * <pre>{@code
 * // One session per user (most common)
 * chat.send(SendOptions.userId("user-1"), "hello");
 *
 * // Same user, different sessions
 * chat.send(SendOptions.of("user-1", "session-a"), "hello");
 * chat.send(SendOptions.of("user-1", "session-b"), "hello");
 *
 * // Target a specific agent in multi-agent setups
 * chat.send(SendOptions.userId("user-1").withAgentId("support"), "help me");
 *
 * // Pass application context into the agent turn
 * chat.send(
 *     SendOptions.userId("user-1")
 *         .withAttribute("tenant", "acme")
 *         .withRuntimeContext(
 *             RuntimeContext.builder()
 *                 .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
 *                 .build()),
 *     "hello");
 * }</pre>
 *
 * @param userId 用户身份（必填）
 * @param sessionId 可选对话标识；null 表示每用户一个会话
 * @param agentId 可选目标智能体覆盖；null 表示默认路由
 * @param runtimeContext 可选调用方运行上下文；没有时为 null
 */
public record SendOptions(
        String userId, String sessionId, String agentId, RuntimeContext runtimeContext) {

    public SendOptions {
        Objects.requireNonNull(userId, "userId");
    }

    /**
     * 每用户一个会话，最常见的用法。
     */
    public static SendOptions userId(String userId) {
        return new SendOptions(userId, null, null, null);
    }

    /**
     * 明确指定用户和会话，同一用户可进行多段对话。
     */
    public static SendOptions of(String userId, String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return new SendOptions(userId, sessionId, null, null);
    }

    /**
     * 返回带指定智能体 ID 覆盖的副本。
     */
    public SendOptions withAgentId(String agentId) {
        return new SendOptions(userId, sessionId, agentId, runtimeContext);
    }

    /**
     * 返回携带指定 {@link RuntimeContext} 进入智能体轮次的副本，替换之前附加的上下文。
     */
    public SendOptions withRuntimeContext(RuntimeContext runtimeContext) {
        return new SendOptions(userId, sessionId, agentId, runtimeContext);
    }

    /**
     * 返回将字符串属性合并到所携带 {@link RuntimeContext} 的副本。
     * 尚无上下文时，从空上下文创建。
     */
    public SendOptions withAttribute(String key, Object value) {
        RuntimeContext next = RuntimeContext.builder(runtimeContext).put(key, value).build();
        return new SendOptions(userId, sessionId, agentId, next);
    }

    /**
     * 返回将类型化属性合并到所携带 {@link RuntimeContext} 的副本。
     * 尚无上下文时，从空上下文创建。
     */
    public <T> SendOptions withAttribute(Class<T> type, T value) {
        RuntimeContext next = RuntimeContext.builder(runtimeContext).put(type, value).build();
        return new SendOptions(userId, sessionId, agentId, next);
    }

    /**
     * 实际会话键：提供 sessionId 时使用它，否则使用 userId。
     */
    String effectiveSessionKey() {
        return sessionId != null ? sessionId : userId;
    }
}
