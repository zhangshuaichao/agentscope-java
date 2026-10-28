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
package io.agentscope.extensions.controlplane.model;

import io.agentscope.extensions.controlplane.model.ContextSnapshot.ContextMessage;
import io.agentscope.extensions.controlplane.model.ContextSnapshot.ToolInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * 根据二级事件流增量维护一个会话的有效上下文视图和 {@code contextHash}。
 *
 * <p>{@code compaction} 事件将视图重置为单条摘要消息，对应框架后续发送给模型的压缩视图。
 *
 * <p>这是从事件重建的近似值，权威上下文仍来自 {@code FrameworkAdapter.extractContext()}。其价值在于始终可从内存获取，使一级快照和变更检测无需重新读取框架状态。
 *
 * <p>非线程安全，由桥接器持有自己的锁串行化访问。
 *
 * <p>【学习提示】观测缓存不等于执行状态：事件缺失或定制中间件可能使推导视图与实际请求不同。查询应优先使用适配器的实时读取，不能反过来用跟踪器覆盖框架状态。
 */
public final class ContextTracker {

    private final String sessionId;
    private final String framework;
    private final List<ContextMessage> messages = new ArrayList<>();

    private String systemPrompt = "";
    private String effectiveSystemPrompt = "";
    private List<ToolInfo> tools = List.of();
    private int maxTokens;

    private boolean compacted;
    private String compactionSummary = "";
    private int originalMessageCount;
    private long compactedAt;
    private int tokensIn;
    private int tokensOut;

    /**
     * 当前占用有效上下文窗口的词元数，用于计算压力。取最近模型调用的 inputTokens，不跨消息累加。
     */
    private int contextUsedTokens;

    private int messageCount;
    private String lastHash;
    private String model = "";

    public ContextTracker(String sessionId, String framework) {
        this.sessionId = sessionId;
        this.framework = framework == null ? "" : framework;
        this.lastHash = snapshot().computeHash();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getContextHash() {
        return lastHash;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public int getEffectiveMessageCount() {
        return messages.size();
    }

    public int getTokensIn() {
        return tokensIn;
    }

    public int getTokensOut() {
        return tokensOut;
    }

    public int getContextUsedTokens() {
        return contextUsedTokens;
    }

    /**
 * 实时有效上下文词元数，用于 pressure = used / maxTokens。
 */
    public void setContextUsedTokens(int contextUsedTokens) {
        this.contextUsedTokens = Math.max(0, contextUsedTokens);
    }

    public boolean isCompacted() {
        return compacted;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    /**
 * 当前会话模型的上下文窗口大小，用于压力比率。
 */
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = Math.max(0, maxTokens);
    }

    public String getModel() {
        return model;
    }

    /**
 * 来自智能体配置的模型名，若已知。
 */
    public void setModel(String model) {
        this.model = model == null ? "" : model;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }

    /**
     * 此会话最近观察到的中间件组合后系统提示，可能不同于构建器基础提示。首轮完成 {@code onSystemPrompt} 前为空。
     */
    public void setEffectiveSystemPrompt(String effectiveSystemPrompt) {
        this.effectiveSystemPrompt = effectiveSystemPrompt == null ? "" : effectiveSystemPrompt;
    }

    public String getEffectiveSystemPrompt() {
        return effectiveSystemPrompt;
    }

    /**
 * 优先使用最近有效提示，缺失时回退到构建器基础提示。
 */
    public String resolveSystemPrompt() {
        return effectiveSystemPrompt.isEmpty() ? systemPrompt : effectiveSystemPrompt;
    }

    public void setTools(List<ToolInfo> tools) {
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * 消费一个事件。
     *
     * @return 上下文哈希变化时为 {@code true}，用于控制第四级推送
     */
    public boolean onEvent(SessionEvent event) {
        if (!sessionId.equals(event.getSessionId())) {
            return false;
        }
        tokensIn += event.getTokensIn();
        tokensOut += event.getTokensOut();

        switch (event.getEventType()) {
            case SessionEvent.MESSAGE -> {
                String role =
                        event.getRole().isEmpty() ? SessionEvent.ROLE_ASSISTANT : event.getRole();
                messages.add(ContextMessage.of(role, event.getContent()));
                messageCount++;
            }
            case SessionEvent.TOOL_CALL -> {
                String text =
                        event.getContent().isEmpty() ? event.getToolName() : event.getContent();
                messages.add(ContextMessage.of(SessionEvent.ROLE_ASSISTANT, text));
            }
            case SessionEvent.TOOL_RESULT -> {
                String text =
                        event.getToolOutput().isEmpty()
                                ? event.getContent()
                                : event.getToolOutput();
                messages.add(ContextMessage.of(SessionEvent.ROLE_TOOL, text));
            }
            case SessionEvent.COMPACTION -> {
                originalMessageCount = messageCount;
                compacted = true;
                compactionSummary = event.getContent();
                compactedAt = event.getOccurredAt();
                messages.clear();
                messages.add(
                        new ContextMessage(SessionEvent.ROLE_SYSTEM, event.getContent(), true));
            }
            default -> {
                // session_start / session_end 不改变上下文视图。
                return false;
            }
        }

        String newHash = snapshot().computeHash();
        boolean changed = !newHash.equals(lastHash);
        lastHash = newHash;
        return changed;
    }

    public ContextSnapshot snapshot() {
        return ContextSnapshot.builder(sessionId)
                .contextHash(lastHash)
                .systemPrompt(resolveSystemPrompt())
                .messages(List.copyOf(messages))
                .tools(tools)
                .compacted(compacted)
                .compactionSummary(compactionSummary)
                .originalMessageCount(originalMessageCount)
                .compactedAt(compactedAt)
                .totalTokens(tokensIn + tokensOut)
                .maxTokens(maxTokens)
                .framework(framework)
                .model(model)
                .build();
    }
}
