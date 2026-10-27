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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.session.SessionExecution;
import io.agentscope.core.session.SessionExportSink;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 纯粹的智能体工厂与调用器：负责从已注册工厂创建智能体，并使用提示调用它们。
 *
 * <p>这是<em>智能体内部</em>的一层。它<strong>没有</strong>会话注册表、执行通道管理
 * 或运行跟踪。智能体内部的 {@link AgentSpawnTool} 直接使用它进行轻量级子智能体调用。
 */
public final class DefaultAgentManager {

    private volatile Map<String, SubagentFactory> agentFactories;
    private volatile Map<String, SubagentDeclaration> declarations;
    private final WorkspaceManager workspaceManager;

    /**
     * 根据子智能体条目构建管理器（工厂加上用于远程配置的可选
     * {@link SubagentDeclaration} 元数据）。
     */
    public DefaultAgentManager(List<SubagentEntry> entries, WorkspaceManager workspaceManager) {
        Map<String, SubagentFactory> factories = new HashMap<>();
        Map<String, SubagentDeclaration> decls = new HashMap<>();
        for (SubagentEntry e : entries) {
            factories.put(e.name(), e.factory());
            if (e.declaration() != null) {
                decls.put(e.name(), e.declaration());
            }
        }
        this.agentFactories = Map.copyOf(factories);
        this.declarations = Map.copyOf(decls);
        this.workspaceManager = workspaceManager;
    }

    /**
     * 用新快照替换当前条目集合。
     */
    public void refreshEntries(List<SubagentEntry> entries) {
        // 【学习提示】每个 Map 都先构建为不可变快照，再通过 volatile 引用发布。
        // 注意两个字段分别赋值，不是一个原子事务；不能把单个快照的完整性
        // 推导成 factories 与 declarations 两张表在并发读取时必然同属一个版本。
        Map<String, SubagentFactory> factories = new HashMap<>();
        Map<String, SubagentDeclaration> decls = new HashMap<>();
        for (SubagentEntry e : entries) {
            factories.put(e.name(), e.factory());
            if (e.declaration() != null) {
                decls.put(e.name(), e.declaration());
            }
        }
        this.agentFactories = Map.copyOf(factories);
        this.declarations = Map.copyOf(decls);
    }

    /**
     * {@link #refreshEntries(List)} 的原子别名，由
     * {@link io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware}
     * 在每个推理步骤替换已注册子智能体集合。下面两个 volatile 引用赋值确保
     * 并发读取者看到完整的旧快照或新快照，而不是部分状态。
     */
    public void replaceAgents(List<SubagentEntry> entries) {
        refreshEntries(entries);
    }

    /**
     * 可安全应对竞争的查找并创建操作。执行 volatile 读取时，若 {@code agentId}
     * 没有注册工厂，或注册声明仅允许 {@link SubagentDeclaration.Mode#PRIMARY}
     * （不能作为子智能体启动），返回 {@link Optional#empty()}；否则返回新建的智能体。
     * 当注册表可能被并发替换（例如调用之间动态重载）时，优先使用此方法，而不是分两步执行
     * {@link #hasAgent(String)} 与 {@link #createAgent(String, RuntimeContext)}。
     *
     * <p>{@code parentRc} 转发给 {@link SubagentFactory#create(RuntimeContext)}，
     * 使子智能体能够按父级身份划分持久化状态。没有父级上下文时传入
     * {@link RuntimeContext#empty()}。
     */
    public Optional<Agent> createAgentIfPresent(String agentId, RuntimeContext parentRc) {
        if (agentId == null) {
            return Optional.empty();
        }
        SubagentFactory factory = agentFactories.get(agentId);
        if (factory == null) {
            return Optional.empty();
        }
        SubagentDeclaration decl = declarations.get(agentId);
        if (decl != null && decl.getMode() == SubagentDeclaration.Mode.PRIMARY) {
            return Optional.empty();
        }
        return Optional.of(factory.create(parentRc != null ? parentRc : RuntimeContext.empty()));
    }

    /**
     * 当 {@code agentId} 注册为仅允许 {@code PRIMARY} 的声明时返回 {@code true}。
     * 这使 {@link io.agentscope.harness.agent.tool.AgentSpawnTool} 能在
     * {@link #createAgentIfPresent} 返回空时，给出更有帮助的错误消息
     * （“仅限 PRIMARY，不能作为子智能体启动”），而非笼统的“未知 agent_id”。
     */
    public boolean isPrimaryOnly(String agentId) {
        if (agentId == null) return false;
        SubagentDeclaration decl = declarations.get(agentId);
        return decl != null && decl.getMode() == SubagentDeclaration.Mode.PRIMARY;
    }

    /**
     * 指定智能体 ID 是否已注册工厂。
     */
    public boolean hasAgent(String agentId) {
        return agentId != null && agentFactories.containsKey(agentId);
    }

    /**
     * 以 {@code agent_id} 为键的已注册子智能体工厂不可变视图。
     */
    public Map<String, SubagentFactory> getAgentFactories() {
        return agentFactories;
    }

    /**
     * 指定 {@code agent_id} 的可选声明元数据（例如远程 URL）。
     */
    public Optional<SubagentDeclaration> getDeclaration(String agentId) {
        return Optional.ofNullable(declarations.get(agentId));
    }

    /**
     * 从已注册工厂创建新的智能体实例。
     *
     * @throws IllegalArgumentException 指定 ID 没有注册工厂时抛出
     */
    public Agent createAgent(String agentId, RuntimeContext parentRc) {
        SubagentFactory factory = agentFactories.get(agentId);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown agent_id: " + agentId);
        }
        return factory.create(parentRc != null ? parentRc : RuntimeContext.empty());
    }

    /**
     * 使用用户提示调用智能体。同时处理普通 {@link Agent} 和 {@link
     * HarnessAgent}（为后者注入 {@link RuntimeContext}）。
     *
     * <p>对于 {@link HarnessAgent} 子智能体，传播 {@code userId}，使隔离键解析
     * （例如 {@code USER} 作用域的沙箱槽位）正常工作。始终分配独立于父会话的
     * 全新 {@code sessionId}。
     *
     * @param agent 待调用的智能体
     * @param sessionId 新的子智能体专属会话 ID
     * @param userId 父级用户 ID（可以为 {@code null}）
     * @param prompt 待发送的用户消息
     */
    public Mono<Msg> invokeAgent(Agent agent, String sessionId, String userId, String prompt) {
        return invokeAgent(agent, sessionId, userId, prompt, null);
    }

    public Mono<Msg> invokeAgent(
            Agent agent, String sessionId, String userId, String prompt, RuntimeContext parentRc) {
        RuntimeContext ctx = childContext(sessionId, userId, parentRc);
        if (agent instanceof ReActAgent react) {
            return react.call(List.of(userMessage(prompt)), ctx);
        }
        if (agent instanceof HarnessAgent harness) {
            return harness.call(userMessage(prompt), ctx);
        }
        return agent.call(List.of(userMessage(prompt)));
    }

    /**
     * 调用智能体，并将其执行返回为带来源标记的 {@link Event} {@link Flux}。
     *
     * <p>返回流中的每个事件都带有 {@link EventSource}，由 {@code source}
     * 结合子级 {@code agentId}/{@code sessionId} 构建。这使父级消费者无需带外元数据
     * 即可识别每个事件由哪个子智能体产生。
     *
     * <p>{@code parentSource} 参数应为父级 Reactor Context 中已有的
     * {@link EventSource}（若存在）。当父级本身也是子智能体时，将其路径用作前缀，
     * 从而在多层嵌套中保留完整调用层级路径。
     *
     * @param agent 待调用的智能体
     * @param sessionId 新的子智能体专属会话 ID
     * @param userId 父级用户 ID（可以为 {@code null}）
     * @param prompt 待发送的用户消息
     * @param source 写入每个发出事件的 {@link EventSource}
     * @param options 传给子智能体的流式配置
     * @return 带来源标记事件的 {@link Flux}；绝不为 null
     */
    public Flux<Event> invokeAgentStream(
            Agent agent,
            String sessionId,
            String userId,
            String prompt,
            EventSource source,
            StreamOptions options) {
        return invokeAgentStream(agent, sessionId, userId, prompt, source, options, null);
    }

    public Flux<Event> invokeAgentStream(
            Agent agent,
            String sessionId,
            String userId,
            String prompt,
            EventSource source,
            StreamOptions options,
            RuntimeContext parentRc) {
        Flux<Event> childFlux;
        StreamOptions effective = options != null ? options : StreamOptions.defaults();
        RuntimeContext ctx = childContext(sessionId, userId, parentRc);
        if (agent instanceof ReActAgent react) {
            childFlux = react.stream(List.of(userMessage(prompt)), effective, ctx);
        } else if (agent instanceof HarnessAgent harness) {
            childFlux = harness.stream(List.of(userMessage(prompt)), effective, ctx);
        } else {
            childFlux = agent.stream(List.of(userMessage(prompt)), effective);
        }
        return childFlux.map(event -> event.withSource(source));
    }

    private static RuntimeContext childContext(
            String sessionId, String userId, RuntimeContext parent) {
        RuntimeContext child =
                parent == null
                        ? RuntimeContext.builder().sessionId(sessionId).userId(userId).build()
                        : RuntimeContext.builder(parent)
                                .sessionId(sessionId)
                                .userId(userId)
                                .build();
        var recorder = SessionRecorder.from(parent);
        child.put(SessionRecorder.CONTEXT_KEY, null);
        child.put(SessionRecorder.TURN_ID_KEY, null);
        child.put(SessionExportSink.CONTEXT_KEY, null);
        child.put(SessionExecution.CONTEXT_KEY, null);
        child.setAgentState(null);
        if (recorder != null)
            child.put(
                    SessionRecorder.PARENT_KEY,
                    Map.of(
                            "parentSessionId",
                            parent.getSessionId(),
                            "parentRunId",
                            recorder.runId(),
                            "parentTurnId",
                            recorder.turnId()));
        return child;
    }

    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    private static Msg userMessage(String prompt) {
        return Msg.builder().role(MsgRole.USER).textContent(prompt).build();
    }
}
