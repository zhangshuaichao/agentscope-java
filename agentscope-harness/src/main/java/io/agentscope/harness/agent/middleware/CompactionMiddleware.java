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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 在每次 LLM 推理调用前压缩对话的中间件。
 *
 * <p>在 {@link #onReasoning} 触发。超过压缩阈值时：
 * <ol>
 *   <li>通过 {@link MemoryFlushManager} 从前缀中提取并写入长期记忆。</li>
 *   <li>原生会话日志保留压缩前的完整对话。</li>
 *   <li>通过一次 LLM 调用将前缀提炼为结构化摘要。</li>
 *   <li>将智能体工作的 {@link AgentState#contextMutable() context} 替换为
 *       {@code [summaryMsg] + preservedTail}。</li>
 *   <li>重建下游 {@link ReasoningInput}，内容为
 *       {@code [systemMsg] + [summaryMsg] + preservedTail}。</li>
 * </ol>
 *
 * <p>{@link CompactionConfig#getTriggerTokens()} 为 0 时采用默认动态模式，
 * 有效触发阈值按 {@code model.getContextWindowSize() - reserved} 计算。
 * 若模型不报告上下文窗口，则回退到 {@link CompactionConfig#FALLBACK_TRIGGER_TOKENS}。
 *
 * <p>【学习提示】压缩改变的是当前工作上下文，不是删除完整会话事实。
 * 长期记忆提取、会话日志留存、模型输入摘要是三个不同职责，不应混同为同一种“记忆”。
 */
public class CompactionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(CompactionMiddleware.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final CompactionConfig config;

    public CompactionMiddleware(
            WorkspaceManager workspaceManager, Model model, CompactionConfig config) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.config = config;
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        Model executionModel = agent instanceof ReActAgent react ? react.getModel() : model;
        if (executionModel == null) executionModel = model;
        int window = executionModel.getContextWindowSize();
        int budget =
                window > 0
                        ? Math.max(1, window - config.getReserved())
                        : CompactionConfig.FALLBACK_TRIGGER_TOKENS;
        return prepare(
                        agent,
                        ctx,
                        new ModelCallInput(
                                input.messages(), input.tools(), input.options(), executionModel),
                        budget)
                .flatMapMany(
                        prepared ->
                                next.apply(
                                        new ReasoningInput(
                                                prepared.messages(),
                                                prepared.tools(),
                                                prepared.options())));
    }

    /** 在同一次执行内重试服务商的上下文溢出，确保记录器尚未关闭。 */
    public HarnessRuntimeMiddleware overflowRecovery() {
        return new HarnessRuntimeMiddleware() {
            @Override
            public Flux<AgentEvent> onReasoning(
                    Agent agent,
                    RuntimeContext ctx,
                    ReasoningInput input,
                    Function<ReasoningInput, Flux<AgentEvent>> next) {
                Flux<AgentEvent> execution = next.apply(input);
                if (!(agent instanceof ReActAgent react) || !react.sessionLogEnabled())
                    return execution;
                return execution.onErrorResume(
                        error -> {
                            if (ExceptionUtils.containsInterruptedException(error)
                                    || SessionLogException.causedBy(error)
                                    || !isContextOverflowError(error)) return Flux.error(error);
                            AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                            if (state == null || state.getContext().isEmpty())
                                return Flux.error(error);
                            List<Msg> before = List.copyOf(state.getContext());
                            // 服务商拒绝请求前，最终请求准备器可能已经压缩了状态。
                            // 应从这份最新历史重试。
                            var retryMessages = new ArrayList<Msg>();
                            input.messages().stream()
                                    .filter(
                                            message ->
                                                    message.getRole() == MsgRole.SYSTEM
                                                            || isSynthetic(message))
                                    .forEach(retryMessages::add);
                            retryMessages.addAll(before);
                            var forced =
                                    new CompactionMiddleware(
                                            workspaceManager,
                                            model,
                                            CompactionConfig.builder()
                                                    .triggerMessages(1)
                                                    .keepMessages(1)
                                                    .keepTokens(0)
                                                    .flushBeforeCompact(
                                                            config.isFlushBeforeCompact())
                                                    .build());
                            return forced.prepareCandidate(
                                            agent,
                                            ctx,
                                            new ModelCallInput(
                                                    retryMessages,
                                                    input.tools(),
                                                    input.options(),
                                                    react.getModel()),
                                            CompactionConfig.FALLBACK_TRIGGER_TOKENS,
                                            before)
                                    .flatMapMany(
                                            candidate -> {
                                                if (candidate.history().equals(before)
                                                        || !state.getContext().equals(before))
                                                    return Flux.error(error);
                                                applyToContext(state, candidate.history());
                                                // next 创建新的模型调用，但本轮、
                                                // 执行实例和写入器保持不变。
                                                return next.apply(
                                                        new ReasoningInput(
                                                                candidate.input().messages(),
                                                                candidate.input().tools(),
                                                                candidate.input().options()));
                                            });
                        });
            }
        };
    }

    public static boolean isContextOverflowError(Throwable error) {
        String message = error.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("context_length_exceeded")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("token limit")
                || lower.contains("too many tokens")
                || lower.contains("exceeds the model's maximum")
                || lower.contains("reduce the length");
    }

    /** 在最终模型边界执行压缩，采用实际请求可分配给对话的容量。 */
    public Mono<ModelCallInput> prepare(
            Agent agent, RuntimeContext ctx, ModelCallInput input, int conversationBudget) {
        return Mono.defer(
                () -> {
                    AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                    List<Msg> original =
                            state == null ? List.of() : List.copyOf(state.contextMutable());
                    return prepareCandidate(agent, ctx, input, conversationBudget, original)
                            .map(
                                    candidate -> {
                                        if (state != null
                                                && state.contextMutable().equals(original)) {
                                            applyToContext(state, candidate.history());
                                        }
                                        return candidate.input();
                                    });
                });
    }

    /** 模型视图与候选持久化历史；创建它们绝不会替换活动历史。 */
    public record Candidate(ModelCallInput input, List<Msg> history) {
        public Candidate {
            history = List.copyOf(history);
        }
    }

    public Mono<Candidate> prepareCandidate(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            int conversationBudget,
            List<Msg> canonical) {
        return Mono.defer(
                () -> {
                    RuntimeContext rc = ctx == null ? RuntimeContext.empty() : ctx;
                    List<Msg> prefix = new ArrayList<>();
                    List<Msg> conversation = new ArrayList<>();
                    for (Msg msg : input.messages()) {
                        if (msg.getRole() == MsgRole.SYSTEM || isSynthetic(msg)) {
                            prefix.add(msg);
                        } else {
                            conversation.add(msg);
                        }
                    }
                    int trigger =
                            config.getTriggerTokens() > 0
                                    ? Math.min(config.getTriggerTokens(), conversationBudget)
                                    : conversationBudget;
                    int keep =
                            config.getKeepTokens() >= 0
                                    ? config.getKeepTokens()
                                    : Math.min(
                                            config.getKeepTokensMax(),
                                            Math.max(
                                                    config.getKeepTokensMin(),
                                                    (int)
                                                            (conversationBudget
                                                                    * config
                                                                            .getKeepTokensRatio())));
                    CompactionConfig effective =
                            config.withEffective(
                                    Math.max(1, trigger),
                                    Math.min(keep, Math.max(0, conversationBudget / 2)));
                    ConversationCompactor compactor =
                            new ConversationCompactor(
                                    model, new MemoryFlushManager(workspaceManager, model));
                    // 仅当视图与权威历史完全一致时才提交。钩子附加的上下文
                    // 绝不能泄漏到持久化历史，也不能抹除并发发生的
                    // 其他修改。
                    boolean canCommit = canonical.equals(conversation);
                    return compactor
                            .compactIfNeeded(
                                    rc,
                                    conversation,
                                    effective,
                                    agent.getName(),
                                    rc.getSessionId() == null ? "default" : rc.getSessionId())
                            .onErrorResume(
                                    error -> {
                                        if (ExceptionUtils.containsInterruptedException(error)
                                                || SessionLogException.causedBy(error))
                                            return Mono.error(error);
                                        log.warn(
                                                "Compaction failed; final request budget validation"
                                                        + " still applies",
                                                error);
                                        return Mono.just(Optional.empty());
                                    })
                            .map(
                                    result -> {
                                        if (result.isEmpty())
                                            return new Candidate(input, canonical);
                                        List<Msg> compacted = result.get();
                                        List<Msg> messages = new ArrayList<>(prefix);
                                        messages.addAll(compacted);
                                        return new Candidate(
                                                new ModelCallInput(
                                                        List.copyOf(messages),
                                                        input.tools(),
                                                        input.options(),
                                                        input.model()),
                                                canCommit ? compacted : canonical);
                                    });
                });
    }

    private static boolean isSynthetic(Msg msg) {
        return msg.getMetadata() != null
                && Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC));
    }

    private static void applyToContext(AgentState state, List<Msg> compacted) {
        if (state == null) {
            log.warn("Cannot apply compacted messages: AgentState is null");
            return;
        }
        try {
            List<Msg> ctx = state.contextMutable();
            ctx.clear();
            ctx.addAll(compacted);
            log.debug("Applied compacted messages to state ({} messages)", compacted.size());
        } catch (Exception e) {
            log.warn("Failed to apply compacted messages to state: {}", e.getMessage());
        }
    }
}
