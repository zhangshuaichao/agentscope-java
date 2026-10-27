/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter.strategy;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AguiStreamContext {

    private static final Logger logger = LoggerFactory.getLogger(AguiStreamContext.class);

    private final String threadId;
    private final String runId;
    private final AguiAdapterConfig config;
    private final RunAgentInput runInput;

    private final List<AguiEvent> pendingEvents = new ArrayList<>();

    private final Set<String> startedTextMessages = new LinkedHashSet<>();
    private final Set<String> endedTextMessages = new LinkedHashSet<>();
    private final Set<String> startedReasoningMessages = new LinkedHashSet<>();
    private final Set<String> endedReasoningMessages = new LinkedHashSet<>();
    private final Set<String> startedToolCalls = new LinkedHashSet<>();
    private final Set<String> endedToolCalls = new LinkedHashSet<>();
    private final Set<String> adoptedToolCalls = new LinkedHashSet<>();
    private String currentTextMessageId;
    private String currentReasoningMessageId;
    private final Map<String, StringBuilder> toolResultContent = new LinkedHashMap<>();
    private final Map<String, AguiEvent.Interrupt> pendingInterrupts = new LinkedHashMap<>();
    private final Set<String> warnedMissingToolCallIdOperations = new LinkedHashSet<>();
    private final TokenUsageAccumulator tokenUsageAccumulator = new TokenUsageAccumulator();
    private final Predicate<String> isExternalTool;
    private final Map<String, String> startedToolCallNames = new LinkedHashMap<>();

    public AguiStreamContext(String threadId, String runId, AguiAdapterConfig config) {
        this(threadId, runId, config, null, null);
    }

    public AguiStreamContext(
            String threadId, String runId, AguiAdapterConfig config, RunAgentInput runInput) {
        this(threadId, runId, config, runInput, null);
    }

    /**
     * 流转换上下文。
     *
     * <p>【学习提示】工具调用与工具结果是两条关联阶段：toolCallId 是稳定关联键，参数片段不是独立调用，TOOL_CALL_END 关闭参数阶段而非证明执行成功。结果文本按 ID 缓冲后发送；恢复运行可接纳已验证的旧 ID，避免重放前一轮调用。未知 ID 的结果不能凭空建立调用关系。
     *
     * <p>{@code isExternalTool} 谓词判断工具调用是否解析为外部工具（在框架外执行，例如前端提供或仅含 schema 的工具）。禁用 {@code emitToolCallArgs} 时，外部工具仍需要 {@code TOOL_CALL_ARGS}，以便客户端调用。传入 {@code null} 时回退为匹配 {@link RunAgentInput#getTools()} 中的工具名（在无实时工具集时使用，例如重放）。
     *
     * @param isExternalTool 根据工具名解析外部工具的谓词
     */
    public AguiStreamContext(
            String threadId,
            String runId,
            AguiAdapterConfig config,
            RunAgentInput runInput,
            Predicate<String> isExternalTool) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.runInput = runInput;
        this.isExternalTool =
                isExternalTool != null ? isExternalTool : defaultExternalToolDetector(runInput);
    }

    public String getThreadId() {
        return threadId;
    }

    public String getRunId() {
        return runId;
    }

    public AguiAdapterConfig getConfig() {
        return config;
    }

    public RunAgentInput getRunInput() {
        return runInput;
    }

    public void beginEvent() {
        pendingEvents.clear();
    }

    public List<AguiEvent> drainEvents() {
        List<AguiEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public void emit(AguiEvent event) {
        pendingEvents.add(event);
    }

    TokenUsageAccumulator getTokenUsageAccumulator() {
        return tokenUsageAccumulator;
    }

    public void startTextMessage(String messageId) {
        if (startedTextMessages.add(messageId)) {
            emit(new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant"));
        }
        currentTextMessageId = messageId;
    }

    public void appendTextDelta(String messageId, String delta) {
        if (delta != null && !delta.isEmpty()) {
            startTextMessage(messageId);
            emit(new AguiEvent.TextMessageContent(threadId, runId, messageId, delta));
        }
    }

    public void closeActiveTextMessage() {
        if (currentTextMessageId == null) {
            return;
        }
        closeTextMessage(currentTextMessageId);
    }

    public void closeTextMessage(String messageId) {
        if (messageId == null
                || !startedTextMessages.contains(messageId)
                || endedTextMessages.contains(messageId)) {
            return;
        }
        endedTextMessages.add(messageId);
        if (Objects.equals(messageId, currentTextMessageId)) {
            currentTextMessageId = null;
        }
        emit(new AguiEvent.TextMessageEnd(threadId, runId, messageId));
    }

    public void startReasoningMessage(String messageId) {
        if (startedReasoningMessages.add(messageId)) {
            emit(new AguiEvent.ReasoningMessageStart(threadId, runId, messageId, "reasoning"));
        }
        currentReasoningMessageId = messageId;
    }

    public void appendReasoningDelta(String messageId, String delta) {
        if (delta != null && !delta.isEmpty()) {
            startReasoningMessage(messageId);
            emit(new AguiEvent.ReasoningMessageContent(threadId, runId, messageId, delta));
        }
    }

    public void closeActiveReasoningMessage() {
        if (currentReasoningMessageId == null) {
            return;
        }
        closeReasoningMessage(currentReasoningMessageId);
    }

    public void closeReasoningMessage(String messageId) {
        if (messageId == null
                || !startedReasoningMessages.contains(messageId)
                || endedReasoningMessages.contains(messageId)) {
            return;
        }
        endedReasoningMessages.add(messageId);
        if (Objects.equals(messageId, currentReasoningMessageId)) {
            currentReasoningMessageId = null;
        }
        emit(new AguiEvent.ReasoningMessageEnd(threadId, runId, messageId));
    }

    public void startToolCall(String toolCallId, String toolCallName) {
        if (isBlank(toolCallId)) {
            warnMissingToolCallId("ToolCallStartEvent");
            return;
        }
        if (startedToolCalls.add(toolCallId)) {
            if (toolCallName != null && !toolCallName.isBlank()) {
                startedToolCallNames.put(toolCallId, toolCallName);
            }
            emit(
                    new AguiEvent.ToolCallStart(
                            threadId, runId, toolCallId, normalizeToolCallName(toolCallName)));
        }
    }

    public void appendToolCallArgs(String toolCallId, String delta) {
        if (!hasStartedToolCall(toolCallId, "ToolCallDeltaEvent")) {
            return;
        }
        if (delta != null && !delta.isEmpty()) {
            emit(new AguiEvent.ToolCallArgs(threadId, runId, toolCallId, delta));
        }
    }

    public void endToolCall(String toolCallId) {
        if (!hasStartedToolCall(toolCallId, "ToolCallEndEvent")) {
            return;
        }
        if (endedToolCalls.add(toolCallId)) {
            emit(new AguiEvent.ToolCallEnd(threadId, runId, toolCallId));
            startedToolCallNames.remove(toolCallId);
        }
    }

    /**
     * 接收此前运行已发出调用的工具结果。
     *
     * <p>调用方必须从已知待处理工具调用取得 ID，例如经验证的用户确认。接纳操作幂等且不发送事件：不会在本轮启动工具调用、启用参数事件或清除现有结果缓冲区。与工具调用事件一样，忽略 null 或空白 ID。
     *
     * @param toolCallId 此前已发出的工具调用的稳定 ID
     */
    public void adoptToolCall(String toolCallId) {
        if (isBlank(toolCallId)) {
            warnMissingToolCallId("adoptToolCall");
            return;
        }
        adoptedToolCalls.add(toolCallId);
    }

    public void beginToolResult(String toolCallId) {
        if (!hasKnownToolCall(toolCallId, "ToolResultStartEvent")) {
            return;
        }
        toolResultContent.computeIfAbsent(toolCallId, ignored -> new StringBuilder());
    }

    public void appendToolResultText(String toolCallId, String delta) {
        if (!hasKnownToolCall(toolCallId, "ToolResultTextDeltaEvent")) {
            return;
        }
        if (delta != null && !delta.isEmpty()) {
            toolResultBuffer(toolCallId).append(delta);
        }
    }

    public void appendToolResultData(String toolCallId, ContentBlock data) {
        if (!hasKnownToolCall(toolCallId, "ToolResultDataDeltaEvent")) {
            return;
        }
        if (data == null) {
            return;
        }
        StringBuilder buffer = toolResultBuffer(toolCallId);
        if (!buffer.isEmpty()) {
            buffer.append("\n");
        }
        buffer.append(serialize(data));
    }

    public void endToolResult(String replyId, String toolCallId) {
        if (!hasKnownToolCall(toolCallId, "ToolResultEndEvent")) {
            return;
        }
        if (startedToolCalls.contains(toolCallId) && endedToolCalls.add(toolCallId)) {
            emit(new AguiEvent.ToolCallEnd(threadId, runId, toolCallId));
        }
        StringBuilder content = toolResultContent.remove(toolCallId);
        emit(
                new AguiEvent.ToolCallResult(
                        threadId,
                        runId,
                        toolCallId,
                        content != null && !content.isEmpty() ? content.toString() : null,
                        "tool",
                        replyId + ":" + toolCallId));
    }

    public void markToolCallSuspended(String toolCallId) {
        if (!hasKnownToolCall(toolCallId, "ToolResultEndEvent")) {
            return;
        }
        toolResultContent.remove(toolCallId);
    }

    public void addInterrupt(AguiEvent.Interrupt interrupt) {
        Objects.requireNonNull(interrupt, "interrupt cannot be null");
        pendingInterrupts.put(interrupt.id(), interrupt);
    }

    public List<AguiEvent.Interrupt> getPendingInterrupts() {
        return List.copyOf(pendingInterrupts.values());
    }

    public List<AguiEvent> finishPendingEvents() {
        pendingEvents.clear();
        closeActiveTextMessage();
        closeActiveReasoningMessage();
        for (String toolCallId : startedToolCalls) {
            if (!endedToolCalls.contains(toolCallId)) {
                endToolCall(toolCallId);
            }
        }
        return drainEvents();
    }

    private boolean hasStartedToolCall(String toolCallId, String eventName) {
        if (isBlank(toolCallId)) {
            warnMissingToolCallId(eventName);
            return false;
        }
        return startedToolCalls.contains(toolCallId);
    }

    private boolean hasKnownToolCall(String toolCallId, String eventName) {
        return hasStartedToolCall(toolCallId, eventName) || adoptedToolCalls.contains(toolCallId);
    }

    private StringBuilder toolResultBuffer(String toolCallId) {
        return toolResultContent.computeIfAbsent(toolCallId, ignored -> new StringBuilder());
    }

    private static String normalizeToolCallName(String toolCallName) {
        return toolCallName != null && !toolCallName.isBlank() ? toolCallName : "unknown";
    }

    private static String serialize(ContentBlock data) {
        if (data instanceof TextBlock textBlock) {
            return textBlock.getText();
        }
        try {
            return JsonUtils.getJsonCodec().toJson(data);
        } catch (JsonException e) {
            return data.toString();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void warnMissingToolCallId(String eventName) {
        if (!warnedMissingToolCallIdOperations.add(eventName)) {
            return;
        }
        logger.warn(
                "Ignoring {}: null/blank toolCallId. Upstream must supply a stable toolCallId per"
                        + " AG-UI protocol.",
                eventName);
    }

    /**
     * 已启动的工具调用是否解析为外部工具。
     *
     * <p>外部工具在框架外执行（例如前端提供或仅含 schema 的工具），即使禁用 {@code emitToolCallArgs} 也需要向客户端交付 {@code TOOL_CALL_ARGS}。流式参数块通常使用 {@code __fragment__} 等占位名，因此通过 {@code toolCallId} 解析 {@code ToolCallStart} 上记录的真实名称，而非从增量读取。
     */
    public boolean isExternalToolCall(String toolCallId) {
        String toolCallName = startedToolCallNames.get(toolCallId);
        return toolCallName != null && isExternalTool.test(toolCallName);
    }

    /**
     * 无实时工具集时（例如事件重放）使用的外部工具检测回退逻辑。匹配 {@link RunAgentInput#getTools()} 中注册的工具名。
     */
    private static Predicate<String> defaultExternalToolDetector(RunAgentInput runInput) {
        Set<String> names = frontendToolNames(runInput);
        return names.isEmpty() ? name -> false : names::contains;
    }

    private static Set<String> frontendToolNames(RunAgentInput runInput) {
        if (runInput == null || runInput.getTools() == null || runInput.getTools().isEmpty()) {
            return Set.of();
        }
        return runInput.getTools().stream().map(AguiTool::getName).collect(Collectors.toSet());
    }

    static final class TokenUsageAccumulator {
        private long cumulativeInputTokens;
        private long cumulativeOutputTokens;
        private long cumulativeCachedTokens;
        private long cumulativeCacheCreationTokens;
        private long cumulativeReasoningTokens;
        private long cumulativeToolUsePromptTokens;
        private double cumulativeTime;

        TokenUsageSnapshot add(ChatUsage usage) {
            cumulativeInputTokens += usage.getInputTokens();
            cumulativeOutputTokens += usage.getOutputTokens();
            cumulativeCachedTokens += usage.getCachedTokens();
            cumulativeCacheCreationTokens += usage.getCacheCreationTokens();
            cumulativeReasoningTokens += usage.getReasoningTokens();
            cumulativeToolUsePromptTokens += usage.getToolUsePromptTokens();
            cumulativeTime += usage.getTime();
            return new TokenUsageSnapshot(
                    new TokenUsage(
                            usage.getInputTokens(),
                            usage.getOutputTokens(),
                            usage.getCachedTokens(),
                            usage.getCacheCreationTokens(),
                            usage.getReasoningTokens(),
                            usage.getToolUsePromptTokens(),
                            usage.getTime()),
                    new TokenUsage(
                            cumulativeInputTokens,
                            cumulativeOutputTokens,
                            cumulativeCachedTokens,
                            cumulativeCacheCreationTokens,
                            cumulativeReasoningTokens,
                            cumulativeToolUsePromptTokens,
                            cumulativeTime));
        }
    }

    record TokenUsageSnapshot(TokenUsage delta, TokenUsage cumulative) {}

    record TokenUsage(
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            long cacheCreationTokens,
            long reasoningTokens,
            long toolUsePromptTokens,
            double time) {
        long totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
