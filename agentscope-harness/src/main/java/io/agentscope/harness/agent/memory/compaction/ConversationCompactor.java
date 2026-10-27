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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.context.ContextModelCalls;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig.TruncateArgsConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * <h2>算法</h2>
 * <ol>
 * <li><b>检查触发条件</b>：token 数或消息数达到阈值。</li>
 * <li><b>确定切分点</b>：寻找使尾部落入保留预算的最早索引；绝不将 ASSISTANT 工具调用与其 TOOL 结果拆开。</li>
 * <li><b>记忆提取写入</b>（可选）：通过 {@link MemoryFlushManager#flushMemories} 从前缀提取长期记忆。</li>
 * <li><b>摘要</b>：调用一次 LLM，将前缀提炼为结构化摘要。</li>
 * <li><b>重建</b>：返回 {@code [summaryUserMsg] + preservedTail}。</li>
 * </ol>
 *
 * <p>调用方负责同步更新智能体的工作记忆与面向 LLM 的消息列表，参见 {@link CompactionMiddleware}。
 *
 * <p>【学习提示】压缩有损地缩短模型当前可见历史，不删除原生会话日志。
 * 旧摘要参与下一轮摘要以延续上下文，但不再次作为长期记忆提取的原始事实。
 */
public class ConversationCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompactor.class);

    /** 保存在消息名称中、用于识别注入摘要消息的标记。 */
    public static final String SUMMARY_MSG_NAME = "__compaction_summary__";

    private final Model model;
    private final MemoryFlushManager flushManager;

    public ConversationCompactor(Model model, MemoryFlushManager flushManager) {
        this.model = model;
        this.flushManager = flushManager;
    }

    // -------------------------------------------------------------------------
    // 公共 API
    // -------------------------------------------------------------------------

    /**
     * 满足触发条件时，对给定对话消息执行压缩。
     *
     * <p>只应传入<em>对话</em>消息（非 SYSTEM）。调用方必须在调用前分离系统消息，并在调用后重新放回开头。
     *
     * @param conversationMessages 非 SYSTEM 消息（USER / ASSISTANT / TOOL）
     * @param config 压缩配置
     * @param agentId 会话历史引用使用的智能体标识
     * @param sessionId 会话历史引用使用的会话标识
     * @return 对话未变化时返回 {@code Optional.empty()}，否则返回替换消息列表。
     *     跳过摘要时，替换列表包含轻量缩减；执行摘要后则为 {@code [summaryUserMsg] + preservedTail}。
     */
    public Mono<Optional<List<Msg>>> compactIfNeeded(
            RuntimeContext rc,
            List<Msg> conversationMessages,
            CompactionConfig config,
            String agentId,
            String sessionId) {
        var recorder = SessionRecorder.from(rc);
        int beforeMsgCount = conversationMessages == null ? 0 : conversationMessages.size();
        int beforeTokenCount = TokenCounterUtil.calculateToken(conversationMessages);
        boolean triggered =
                conversationMessages != null
                        && !conversationMessages.isEmpty()
                        && shouldCompact(conversationMessages, beforeTokenCount, config);
        if (recorder == null || !triggered)
            return compactCandidate(rc, conversationMessages, config, agentId, sessionId);
        String id = UUID.randomUUID().toString();
        return Mono.usingWhen(
                recorder.record(
                                "compaction/start",
                                Map.of("compactionId", id, "inputMessages", conversationMessages))
                        .thenReturn(new CompactionExecution(id)),
                execution ->
                        compactCandidate(rc, conversationMessages, config, agentId, sessionId)
                                .doOnSuccess(execution.result::set),
                execution ->
                        recorder.record(
                                "compaction/end",
                                completedCompactionEndPayload(
                                        execution.id,
                                        beforeMsgCount,
                                        beforeTokenCount,
                                        execution.afterMessages(conversationMessages))),
                (execution, error) ->
                        SessionLogException.causedBy(error)
                                ? Mono.empty()
                                : recorder.record(
                                        "compaction/end",
                                        compactionEndPayload(
                                                execution.id,
                                                "failed",
                                                beforeMsgCount,
                                                beforeTokenCount)),
                execution ->
                        recorder.record(
                                "compaction/end",
                                compactionEndPayload(
                                        execution.id,
                                        "cancelled",
                                        beforeMsgCount,
                                        beforeTokenCount)));
    }

    private static final class CompactionExecution {
        private final String id;
        private final AtomicReference<Optional<List<Msg>>> result = new AtomicReference<>();

        private CompactionExecution(String id) {
            this.id = id;
        }

        private List<Msg> afterMessages(List<Msg> fallback) {
            Optional<List<Msg>> compacted = result.get();
            return compacted == null
                    ? fallback == null ? List.of() : fallback
                    : compacted.orElse(fallback == null ? List.of() : fallback);
        }
    }

    private static Map<String, Object> completedCompactionEndPayload(
            String compactionId,
            int beforeMsgCount,
            int beforeTokenCount,
            List<Msg> afterMessages) {
        Map<String, Object> payload =
                compactionEndPayload(compactionId, "completed", beforeMsgCount, beforeTokenCount);
        payload.put("afterMsgCount", afterMessages.size());
        payload.put("afterTokenCount", TokenCounterUtil.calculateToken(afterMessages));
        return payload;
    }

    private static Map<String, Object> compactionEndPayload(
            String compactionId, String status, int beforeMsgCount, int beforeTokenCount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("compactionId", compactionId);
        payload.put("status", status);
        payload.put("beforeMsgCount", beforeMsgCount);
        payload.put("beforeTokenCount", beforeTokenCount);
        return payload;
    }

    private Mono<Optional<List<Msg>>> compactCandidate(
            RuntimeContext rc,
            List<Msg> conversationMessages,
            CompactionConfig config,
            String agentId,
            String sessionId) {

        if (conversationMessages == null || conversationMessages.isEmpty()) {
            return Mono.just(Optional.empty());
        }

        if (config.getStrategy() != null
                && shouldCompact(
                        conversationMessages,
                        TokenCounterUtil.calculateToken(conversationMessages),
                        config)) {
            return Mono.defer(
                            () ->
                                    config.getStrategy()
                                            .compact(
                                                    new ConversationCompactionStrategy.Request(
                                                            rc,
                                                            conversationMessages,
                                                            agentId,
                                                            sessionId,
                                                            config.getTriggerTokens())))
                    .switchIfEmpty(
                            Mono.error(
                                    new IllegalStateException(
                                            "empty compaction strategy publisher")))
                    .flatMap(
                            candidate ->
                                    candidate.isPresent()
                                            ? Mono.just(candidate)
                                            : compactStandard(
                                                    rc,
                                                    conversationMessages,
                                                    config,
                                                    agentId,
                                                    sessionId));
        }
        return compactStandard(rc, conversationMessages, config, agentId, sessionId);
    }

    private Mono<Optional<List<Msg>>> compactStandard(
            RuntimeContext rc,
            List<Msg> conversationMessages,
            CompactionConfig config,
            String agentId,
            String sessionId) {
        // 步骤 1a：轻量参数截断（不调用 LLM）。
        // 步骤 1b：工具结果汇总裁剪（不调用 LLM）。
        List<Msg> messages =
                pruneToolResults(
                        truncateArgs(conversationMessages, config.getTruncateArgsConfig()),
                        config.getPruneConfig());

        int totalTokens = TokenCounterUtil.calculateToken(messages);
        if (!shouldCompact(messages, totalTokens, config)) {
            return Mono.just(
                    messages.equals(conversationMessages)
                            ? Optional.empty()
                            : Optional.of(messages));
        }

        int cutoff = determineCutoffIndex(messages, totalTokens, config);
        if (cutoff <= 0) {
            log.debug("Compaction triggered but safe cutoff is 0 — returning lightweight result");
            return Mono.just(
                    messages.equals(conversationMessages)
                            ? Optional.empty()
                            : Optional.of(messages));
        }

        // 将旧摘要保留在摘要输入中，使每次压缩建立在前次摘要之上；
        // 但从记忆提取输入中排除旧摘要，以免重复提取。
        List<Msg> summaryInput = new ArrayList<>(messages.subList(0, cutoff));
        List<Msg> flushInput = filterSummaryMessages(summaryInput);
        List<Msg> tail = new ArrayList<>(messages.subList(cutoff, messages.size()));

        log.info(
                "Compaction triggered: total={} msgs / {} tokens, cutoff={}, keeping={} msgs",
                messages.size(),
                totalTokens,
                cutoff,
                tail.size());

        // 步骤 2：仅从本次新压缩的原始消息中提取并写入长期记忆（尽力执行）。
        Mono<Void> flushStep =
                config.isFlushBeforeCompact()
                        ? flushManager
                                .flushMemories(rc, flushInput)
                                .doOnSuccess(v -> log.debug("Memory flush before compaction done"))
                                .onErrorResume(
                                        e -> {
                                            if ((ExceptionUtils.containsInterruptedException(e)
                                                    || SessionLogException.causedBy(e))) {
                                                return Mono.error(e);
                                            }
                                            log.warn(
                                                    "Memory flush before compaction failed: {}",
                                                    e.getMessage());
                                            return Mono.empty();
                                        })
                        : Mono.empty();

        // 原生日志已经保留全部压缩前消息。引用逻辑历史 API，
        // 绝不另写一份完整记录，也不暴露私有日志路径。
        String historyReference =
                SessionRecorder.from(rc) == null
                        ? null
                        : "session_history(agentId="
                                + JsonUtils.getJsonCodec().toJson(agentId)
                                + ", sessionId="
                                + JsonUtils.getJsonCodec().toJson(sessionId)
                                + ")";

        // 步骤 4：由 LLM 对旧摘要与本次新压缩的前缀一起生成摘要。
        return flushStep
                .then(summarizePrefix(summaryInput, config))
                .map(
                        summary -> {
                            Msg summaryMsg =
                                    buildSummaryMessage(summary, historyReference)
                                            .withMetadata(
                                                    Map.of(
                                                            "context.summary_version", 1,
                                                            "context.covered_messages",
                                                                    summaryInput.stream()
                                                                            .map(Msg::getId)
                                                                            .toList(),
                                                            "context.summary_model",
                                                                    model.getModelName()));
                            List<Msg> compacted = new ArrayList<>();
                            compacted.add(summaryMsg);
                            compacted.addAll(tail);
                            log.info(
                                    "Compaction complete: {} msgs → 1"
                                            + " summary + {} tail = {}"
                                            + " total",
                                    messages.size(),
                                    tail.size(),
                                    compacted.size());
                            return Optional.of(compacted);
                        });
    }

    // -------------------------------------------------------------------------
    // 触发逻辑
    // -------------------------------------------------------------------------

    private static boolean shouldCompact(
            List<Msg> messages, int totalTokens, CompactionConfig config) {
        if (config.getTriggerMessages() > 0 && messages.size() >= config.getTriggerMessages()) {
            log.debug(
                    "Compaction trigger: message count {} >= {}",
                    messages.size(),
                    config.getTriggerMessages());
            return true;
        }
        if (config.getTriggerTokens() > 0 && totalTokens >= config.getTriggerTokens()) {
            log.debug(
                    "Compaction trigger: token count {} >= {}",
                    totalTokens,
                    config.getTriggerTokens());
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // 切分点与分区逻辑
    // -------------------------------------------------------------------------

    /**
     * 确定切分索引，将待摘要前缀与待保留尾部分开。
     *
     * <p>调整切分点，确保 ASSISTANT/TOOL 配对不会被拆开。
     */
    private static int determineCutoffIndex(
            List<Msg> messages, int totalTokens, CompactionConfig config) {
        int rawCutoff;
        if (config.getKeepTokens() > 0) {
            rawCutoff = findTokenBasedCutoff(messages, totalTokens, config.getKeepTokens());
        } else {
            rawCutoff = findMessageBasedCutoff(messages, config.getKeepMessages());
        }
        return findSafeCutoffPoint(messages, rawCutoff);
    }

    /** 返回最早的索引，使 {@code messages[index:]} 落入 token 预算。 */
    private static int findTokenBasedCutoff(List<Msg> messages, int totalTokens, int keepTokens) {
        if (totalTokens <= keepTokens) {
            return 0;
        }
        // 二分查找后缀 token 数不超过 keepTokens 的最早索引。
        int left = 0;
        int right = messages.size();
        int candidate = messages.size();
        int maxIter = Integer.SIZE - Integer.numberOfLeadingZeros(messages.size()) + 1;
        for (int i = 0; i < maxIter && left < right; i++) {
            int mid = (left + right) / 2;
            if (TokenCounterUtil.calculateToken(messages.subList(mid, messages.size()))
                    <= keepTokens) {
                candidate = mid;
                right = mid;
            } else {
                left = mid + 1;
            }
        }
        // 限制边界，确保至少保留 1 条消息。
        return Math.min(candidate, messages.size() - 1);
    }

    /** 返回能够原样保留最后 {@code keepMessages} 条消息的切分点。 */
    private static int findMessageBasedCutoff(List<Msg> messages, int keepMessages) {
        if (messages.size() <= keepMessages) {
            return 0;
        }
        return messages.size() - keepMessages;
    }

    /**
     * 调整切分点，避免拆开 ASSISTANT 工具调用与 TOOL 结果的配对。
     *
     * <p>若 {@code cutoffIndex} 处的消息角色为 TOOL，则向前查找工具调用块与这些结果对应的
     * ASSISTANT 消息，将切分点移到该 ASSISTANT 消息之前，令调用与结果一起保留在尾部。
     *
     * <p>【学习提示】前缀使用不包含切分索引的子列表；因此切分点回退到调用消息的位置，
     * 实际是扩大保留尾部，而不是把调用消息送入摘要前缀。
     */
    private static int findSafeCutoffPoint(List<Msg> messages, int cutoffIndex) {
        if (cutoffIndex <= 0 || cutoffIndex >= messages.size()) {
            return cutoffIndex;
        }

        Msg atCutoff = messages.get(cutoffIndex);
        if (atCutoff.getRole() != MsgRole.TOOL) {
            return cutoffIndex;
        }

        // 收集切分点及其后连续 TOOL 消息中的工具调用 ID。
        List<String> toolCallIds = new ArrayList<>();
        int idx = cutoffIndex;
        while (idx < messages.size() && messages.get(idx).getRole() == MsgRole.TOOL) {
            for (ContentBlock block : messages.get(idx).getContent()) {
                if (block instanceof ToolResultBlock tr && tr.getId() != null) {
                    toolCallIds.add(tr.getId());
                }
            }
            idx++;
        }

        if (toolCallIds.isEmpty()) {
            // 未找到 ID：跳过全部 TOOL 消息，避免留下孤立结果。
            return idx;
        }

        // 向前查找发起这些工具调用的 ASSISTANT 消息。
        for (int i = cutoffIndex - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ToolUseBlock tu && toolCallIds.contains(tu.getId())) {
                        // 将切分点移到这条 ASSISTANT 消息之前。
                        return i;
                    }
                }
            }
        }

        // 回退处理：跳过全部 TOOL 消息。
        return idx;
    }

    // -------------------------------------------------------------------------
    // 摘要生成
    // -------------------------------------------------------------------------

    private Mono<String> summarizePrefix(List<Msg> prefix, CompactionConfig config) {
        if (prefix.isEmpty()) {
            return Mono.just("No previous conversation history.");
        }

        String formatted = formatMessagesForSummary(prefix);
        String prompt = config.getSummaryPrompt().replace("{messages}", formatted);

        List<Msg> summarizationInput =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(prompt).build())
                                .build());

        return ContextModelCalls.auxiliary(model, summarizationInput)
                .reduce(
                        new StringBuilder(),
                        (sb, resp) -> {
                            if (resp.getContent() != null) {
                                for (ContentBlock block : resp.getContent()) {
                                    if (block instanceof TextBlock tb && tb.getText() != null) {
                                        sb.append(tb.getText());
                                    }
                                }
                            }
                            return sb;
                        })
                .map(StringBuilder::toString)
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .switchIfEmpty(
                        Mono.error(new IllegalStateException("Summary model returned no content")));
    }

    /**
     * 将消息列表格式化为适合摘要 LLM 阅读的文本块。
     *
     * <p>工具调用保留名称与参数预览；工具结果保留名称、错误状态和首尾预览。省略的内容会明确标记。
     */
    static String formatMessagesForSummary(List<Msg> messages) {
        return messages.stream()
                .filter(m -> m.getRole() != null && m.getRole() != MsgRole.SYSTEM)
                .map(ConversationCompactor::renderMessageForSummary)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    private static final int SUMMARY_PREVIEW_CHARS = 600;

    private static String renderMessageForSummary(Msg msg) {
        String roleLabel =
                switch (msg.getRole()) {
                    case USER -> "Human";
                    case ASSISTANT -> "AI";
                    case TOOL -> "Tool";
                    default -> msg.getRole().name();
                };

        StringBuilder sb = new StringBuilder(roleLabel).append(": ");
        boolean first = true;
        for (ContentBlock block : msg.getContent()) {
            if (!first) sb.append(" ");
            first = false;
            if (block instanceof TextBlock tb && tb.getText() != null && !tb.getText().isBlank()) {
                sb.append(tb.getText().strip());
            } else if (block instanceof ToolUseBlock tu) {
                sb.append("[tool_call: ")
                        .append(tu.getName())
                        .append(" ")
                        .append(renderToolArgsForSummary(tu))
                        .append("]");
            } else if (block instanceof ToolResultBlock tr) {
                sb.append("[tool_result: ").append(tr.getName() != null ? tr.getName() : "?");
                if (tr.getState() == ToolResultState.ERROR) {
                    sb.append(" (error)");
                }
                sb.append("] ");
                sb.append(buildPrunePreview(extractToolResultText(tr), SUMMARY_PREVIEW_CHARS));
            }
        }
        return sb.toString().strip();
    }

    /** 保留每个参数名称及较短的值；对较大的值分别缩略。 */
    private static String renderToolArgsForSummary(ToolUseBlock toolUse) {
        String json = JsonUtils.resolveToolCallArgsJson(toolUse);
        if (json.length() <= SUMMARY_PREVIEW_CHARS) {
            return json;
        }
        var codec = JsonUtils.getJsonCodec();
        Map<?, ?> args = codec.fromJson(json, Map.class);
        return args.entrySet().stream()
                .map(
                        entry ->
                                codec.toJson(entry.getKey())
                                        + ":"
                                        + buildPrunePreview(
                                                codec.toJson(entry.getValue()),
                                                SUMMARY_PREVIEW_CHARS))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String extractToolResultText(ToolResultBlock tr) {
        if (tr.getOutput() == null) return "";
        return tr.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining(" "));
    }

    // -------------------------------------------------------------------------
    // 摘要消息构建
    // -------------------------------------------------------------------------

    /**
     * 构建承载摘要的 USER 消息。
     *
     * <p>原生会话处于活动状态时，包含逻辑历史查询引用；否则生成不声称存在持久化完整历史的摘要。
     *
     * <p>两种形式都使用相同的延续前导：摘要是既定背景，模型直接在此基础上继续，
     * 不复述、不重新问候，也不提及摘要步骤本身。
     *
     * <p>消息名称设为 {@link #SUMMARY_MSG_NAME}，供钩子识别生成的摘要；
     * 基于内容的稳定 ID 使重复摘要可识别。
     *
     * <p>【学习提示】这里的 USER 只是摘要注入的消息角色，不表示用户刚刚发出新指令或提供了新的授权。
     */
    private static Msg buildSummaryMessage(String summary, String historyReference) {
        String historyHint =
                historyReference == null
                        ? ""
                        : "Earlier conversation details are retrievable with "
                                + historyReference
                                + ".\n\n";
        String content =
                "You are continuing a conversation that has been summarized.\n\n"
                        + historyHint
                        + "A condensed summary of the conversation so far follows. Treat it as "
                        + "established background: continue the work directly — do not recap the "
                        + "summary, do not greet the user anew, and do not mention the "
                        + "summarization.\n\n<summary>\n"
                        + summary
                        + "\n</summary>";
        return Msg.builder()
                .id(buildSummaryMessageId(content))
                .role(MsgRole.USER)
                .name(SUMMARY_MSG_NAME)
                .content(TextBlock.builder().text(content).build())
                .build();
    }

    private static String buildSummaryMessageId(String content) {
        UUID stableId = UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8));
        return SUMMARY_MSG_NAME + ":" + stableId;
    }

    // -------------------------------------------------------------------------
    // 摘要消息过滤（支持链式摘要）
    // -------------------------------------------------------------------------

    /**
     * 从列表中移除先前注入的摘要消息。
     *
     * <p>旧摘要仍参与下一次摘要输入，但记忆提取写入只能处理新压缩的原始消息，以避免重复提取。
     */
    static List<Msg> filterSummaryMessages(List<Msg> messages) {
        return messages.stream()
                .filter(message -> !SUMMARY_MSG_NAME.equals(message.getName()))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // 参数截断（摘要前，不调用 LLM）
    // -------------------------------------------------------------------------

    /**
     * 工具结果汇总裁剪：从后向前遍历 TOOL 消息，保护最近 {@code protectTokens} 对应的工具输出，
     * 当可裁剪总量超过 {@code minimumTokens} 时，将较旧的过大结果替换为首尾预览。
     *
     * <p>此操作不调用 LLM；未发生裁剪时返回原始列表。裁剪块保留元数据和执行状态，
     * 只将输出文本替换为预览，因此依赖 {@code agentscope.tool_result_evicted} 或
     * {@link ToolResultBlock#METADATA_SUSPENDED} 等标记的下游消费者仍可工作。
     * 不过这些保留标记描述的是裁剪前载荷：消费者不能假定块文本仍是完整载荷，
     * 例如转存指针引用的内容在裁剪后不再完整内联。
     */
    List<Msg> pruneToolResults(List<Msg> messages, CompactionConfig.PruneConfig pruneConfig) {
        if (pruneConfig == null || messages == null || messages.isEmpty()) {
            return messages;
        }

        int protectBudget = pruneConfig.getProtectTokens();
        int maxChars = pruneConfig.getMaxOutputChars();
        Set<String> excluded = pruneConfig.getExcludedTools();

        int protectedTokens = 0;
        int prunableTokens = 0;
        List<int[]> toPrune = new ArrayList<>();

        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() != MsgRole.TOOL) {
                continue;
            }
            List<ContentBlock> blocks = msg.getContent();
            if (blocks == null) {
                continue;
            }
            for (int j = blocks.size() - 1; j >= 0; j--) {
                if (!(blocks.get(j) instanceof ToolResultBlock tr)) {
                    continue;
                }
                if (tr.getName() != null && excluded.contains(tr.getName())) {
                    continue;
                }
                String text = extractToolResultText(tr);
                if (text.isBlank()) {
                    continue;
                }
                // 仅计入当前结果，不要把同一批工具消息中的其他结果一起计入。
                int tokens = TokenCounterUtil.calculateToken(List.of(msg.withContent(List.of(tr))));
                if (protectedTokens < protectBudget) {
                    protectedTokens += tokens;
                    continue;
                }
                if (text.length() > maxChars) {
                    prunableTokens += tokens;
                    toPrune.add(new int[] {i, j});
                }
            }
        }

        if (prunableTokens < pruneConfig.getMinimumTokens() || toPrune.isEmpty()) {
            return messages;
        }

        List<Msg> result = new ArrayList<>(messages);
        for (int[] pos : toPrune) {
            int msgIdx = pos[0];
            int blockIdx = pos[1];
            Msg msg = result.get(msgIdx);
            List<ContentBlock> newBlocks = new ArrayList<>(msg.getContent());
            ToolResultBlock tr = (ToolResultBlock) newBlocks.get(blockIdx);
            String text = extractToolResultText(tr);
            String preview = buildPrunePreview(text, maxChars);
            ToolResultBlock pruned =
                    ToolResultBlock.builder()
                            .id(tr.getId())
                            .name(tr.getName())
                            .output(List.of(TextBlock.builder().text(preview).build()))
                            .metadata(tr.getMetadata())
                            .state(tr.getState())
                            .build();
            newBlocks.set(blockIdx, pruned);
            result.set(
                    msgIdx,
                    Msg.builder()
                            .id(msg.getId())
                            .name(msg.getName())
                            .role(msg.getRole())
                            .content(newBlocks)
                            .metadata(msg.getMetadata())
                            .timestamp(msg.getTimestamp())
                            .usage(msg.getUsage())
                            .build());
        }

        log.info("Pruned {} tool results (~{} tokens freed)", toPrune.size(), prunableTokens);
        return result;
    }

    private static String buildPrunePreview(String text, int maxChars) {
        int half = maxChars / 2;
        String head = text.substring(0, Math.min(half, text.length()));
        String tail =
                text.length() > half ? text.substring(Math.max(text.length() - half, half)) : "";
        int removed = text.length() - head.length() - tail.length();
        if (removed <= 0) {
            return text;
        }
        return head + "\n\n...(" + removed + " chars pruned)...\n\n" + tail;
    }

    /**
     * 截断旧消息中过大的 {@code ToolUseBlock} 参数值。
     *
     * <p>这是轻量且不调用 LLM 的步骤，使用独立于完整摘要且更低的触发阈值。
     * 只修改保留窗口之前的消息，近期消息不变。
     *
     * <p>{@code truncateConfig} 为 {@code null} 时原样返回原始列表。
     */
    List<Msg> truncateArgs(List<Msg> messages, TruncateArgsConfig truncateConfig) {
        if (truncateConfig == null || messages == null || messages.isEmpty()) {
            return messages;
        }

        int totalTokens = TokenCounterUtil.calculateToken(messages);
        if (!shouldTruncateArgs(messages, totalTokens, truncateConfig)) {
            return messages;
        }

        int cutoff = determineTruncateCutoff(messages, truncateConfig);
        if (cutoff >= messages.size()) {
            return messages; // 截断窗口内没有消息。
        }

        boolean anyModified = false;
        List<Msg> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            if (i < cutoff && msg.getRole() == MsgRole.ASSISTANT) {
                Msg truncated = truncateToolUseArgs(msg, truncateConfig);
                result.add(truncated);
                if (truncated != msg) {
                    anyModified = true;
                }
            } else {
                result.add(msg);
            }
        }

        if (anyModified) {
            log.debug("Arg truncation applied to messages before index {}", cutoff);
        }
        return anyModified ? result : messages;
    }

    private static boolean shouldTruncateArgs(
            List<Msg> messages, int totalTokens, TruncateArgsConfig cfg) {
        if (cfg.getTriggerMessages() > 0 && messages.size() >= cfg.getTriggerMessages()) {
            return true;
        }
        return cfg.getTriggerTokens() > 0 && totalTokens >= cfg.getTriggerTokens();
    }

    private static int determineTruncateCutoff(List<Msg> messages, TruncateArgsConfig cfg) {
        if (cfg.getKeepTokens() > 0) {
            // 按 token 预算确定保留窗口：从末尾扫描。
            int tokensKept = 0;
            for (int i = messages.size() - 1; i >= 0; i--) {
                int msgTokens = TokenCounterUtil.calculateToken(List.of(messages.get(i)));
                if (tokensKept + msgTokens > cfg.getKeepTokens()) {
                    return i + 1;
                }
                tokensKept += msgTokens;
            }
            return 0;
        }
        // 按消息数量确定保留窗口。
        int keep = cfg.getKeepMessages();
        return Math.max(0, messages.size() - keep);
    }

    /**
     * 返回消息副本，其中过大的 {@code ToolUseBlock} 参数值已缩短。
     * 没有参数超限时，原样返回原始消息引用。
     */
    private static Msg truncateToolUseArgs(Msg msg, TruncateArgsConfig cfg) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return msg;
        }

        boolean anyModified = false;
        List<ContentBlock> newBlocks = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block instanceof ToolUseBlock tu) {
                ToolUseBlock truncated = truncateToolUseBlock(tu, cfg);
                newBlocks.add(truncated);
                if (truncated != tu) {
                    anyModified = true;
                }
            } else {
                newBlocks.add(block);
            }
        }

        if (!anyModified) {
            return msg;
        }
        return msg.withContent(newBlocks);
    }

    /**
     * 返回 {@code ToolUseBlock} 的副本，其中过大的字符串参数值已截断；
     * 无需截断时返回原始对象。
     */
    private static ToolUseBlock truncateToolUseBlock(ToolUseBlock tu, TruncateArgsConfig cfg) {
        // 对于服务端工具调用，以提供商的重放元数据为准。
        if (tu.isServerTool()) {
            return tu;
        }

        Map<String, Object> input = tu.getInput();
        if (input == null || input.isEmpty()) {
            return tu;
        }

        boolean anyModified = false;
        Map<String, Object> newInput = new HashMap<>(input);
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getValue() instanceof String s && s.length() > cfg.getMaxArgLength()) {
                newInput.put(entry.getKey(), s.substring(0, 20) + cfg.getTruncationText());
                anyModified = true;
            }
        }

        if (!anyModified) {
            return tu;
        }
        // 移除原始内容，使请求格式化器序列化截断后的输入。
        return ToolUseBlock.builder()
                .id(tu.getId())
                .name(tu.getName())
                .input(newInput)
                .metadata(tu.getMetadata())
                .state(tu.getState())
                .build();
    }
}
