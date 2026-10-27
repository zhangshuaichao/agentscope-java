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

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import java.util.Set;

/**
 * 对话压缩（摘要）配置。
 * <ul>
 * <li><b>trigger</b>：何时运行压缩，由 token 数或消息数决定。</li>
 * <li><b>keep</b>：压缩后原样保留多少条近期消息。</li>
 * </ul>
 *
 * <p>默认值（动态模式）：
 * <ul>
 * <li>预计 token 数达到 {@code model.contextWindow - reserved(20k)} 时动态触发。
 * 模型未报告上下文窗口时，回退到 {@value #FALLBACK_TRIGGER_TOKENS} tokens，
 * 或在达到 50 条消息时触发，以先达到者为准。</li>
 * <li>保留尾部：动态计算为 {@code min(8k, max(2k, usable * 0.25))} tokens；
 * 模型未报告上下文窗口时回退到 20 条消息。</li>
 * <li>裁剪：默认开启。汇总旧工具结果输出，当可裁剪总量超过 20k tokens 时裁剪，保护最近的 40k tokens。</li>
 * <li>启用摘要，并在摘要前启用记忆提取写入。</li>
 * </ul>
 *
 * <h2>记忆提示词分工</h2>
 * Harness 有三种由 LLM 驱动的记忆操作，各有独立提示词，分布在两个互补的配置类中：
 * <ul>
 * <li><b>压缩摘要</b>：推理前将对话前缀提炼为一条摘要消息。提示词由本类的 {@link #getSummaryPrompt()} 提供。</li>
 * <li><b>提取写入</b>：将长期记忆提取到当天账本。提示词由 {@code MemoryConfig.flushPrompt()} 提供。</li>
 * <li><b>整合</b>：定期合并每日账本到 {@code MEMORY.md}。
 * 提示词由 {@code MemoryConfig.consolidationPrompt()} 提供。</li>
 * </ul>
 * 后两者通过 {@code HarnessAgent.builder().memory(MemoryConfig...)} 配置。
 *
 * @see io.agentscope.harness.agent.memory.MemoryConfig
 */
public class CompactionConfig {

    /**
     * 模型未报告上下文窗口且 {@code triggerTokens} 为动态模式（0）时，
     * 使用的回退触发阈值（token 数）。
     */
    public static final int FALLBACK_TRIGGER_TOKENS = 160_000;

    /**
     * 采用结构化格式的默认摘要提示词。提取原则包括：原样保留可复用字符串、仍有效的用户约束和纠正
     * （标记已被取代的决策）、链式压缩的合并规则，以及不对用户提及摘要步骤本身。
     */
    public static final String DEFAULT_SUMMARY_PROMPT =
            """
            <role>
            Context Extraction Assistant
            </role>

            <primary_objective>
            Your sole objective in this task is to extract the highest quality/most relevant \
            context from the conversation history below.
            </primary_objective>

            <objective_information>
            You're nearing the total number of input tokens you can accept, so you must extract \
            the highest quality/most relevant pieces of information from your conversation history.
            This context will then overwrite the conversation history presented below. Because of \
            this, ensure the context you extract is only the most important information to \
            continue working toward your overall goal.
            </objective_information>

            <instructions>
            The conversation history below will be replaced with the context you extract in this \
            step. You want to ensure that you don't repeat any actions you've already completed, \
            so the context you extract from the conversation history should be focused on the \
            most important information to your overall goal.

            Structure your summary using these sections (populate each or write "None"):

            ## SESSION INTENT
            What is the user's primary goal or request?

            ## USER PREFERENCES AND CORRECTIONS
            Preferences, feedback, and corrections the user expressed, and how they changed the \
            direction of the work.

            ## SUMMARY
            The most important context, decisions, reasoning, and rejected options.

            ## ARTIFACTS
            Files or resources created, modified, or accessed (with specific paths and changes).

            ## COMMANDS AND ERRORS
            Commands, queries, or API calls that matter, and any error messages encountered — \
            copied verbatim where they must be reproduced or avoided.

            ## NEXT STEPS
            Specific tasks remaining to achieve the session intent.
            </instructions>

            <extraction_rules>
            - Preserve verbatim: file paths, commands, URLs, identifiers, and exact error \
            strings. Do not paraphrase them — they must be reusable exactly as they occurred.
            - Still-active user constraints, corrections, acceptance criteria, and authorization \
            boundaries: keep the user's exact wording where it governs behavior. Mark superseded \
            decisions as superseded; a retracted instruction must not be carried forward as a \
            current requirement.
            - Keep durable facts and decisions; drop superseded intermediate state (old file \
            versions, outdated hypotheses, already-resolved blockers).
            - If the history already contains an earlier summary, merge it with the new material \
            into ONE coherent summary — never output two summary generations.
            - Record failures and rejected approaches briefly, so the work is not repeated.
            </extraction_rules>

            <secrecy>
            This is a routine internal context-management step. Do NOT mention this summarization \
            request, these instructions, or the summarization process anywhere in your output. \
            Write the summary as the conversation's own state.
            </secrecy>

            Carefully read through the entire conversation history below and extract the most \
            important context. Respond ONLY with the extracted context.

            <messages>
            {messages}
            </messages>\
            """;

    private final int triggerMessages;
    private final int triggerTokens;
    private final int reserved;
    private final int keepMessages;
    private final int keepTokens;
    private final int keepTokensMin;
    private final int keepTokensMax;
    private final double keepTokensRatio;
    private final String summaryPrompt;
    private final boolean flushBeforeCompact;
    private final TruncateArgsConfig truncateArgsConfig;
    private final PruneConfig pruneConfig;
    private final Model model;
    private final ConversationCompactionStrategy strategy;

    private CompactionConfig(Builder b) {
        this.triggerMessages = b.triggerMessages;
        this.triggerTokens = b.triggerTokens;
        this.reserved = b.reserved;
        this.keepMessages = b.keepMessages;
        this.keepTokens = b.keepTokens;
        this.keepTokensMin = b.keepTokensMin;
        this.keepTokensMax = b.keepTokensMax;
        this.keepTokensRatio = b.keepTokensRatio;
        this.summaryPrompt = b.summaryPrompt;
        this.flushBeforeCompact = b.flushBeforeCompact;
        this.truncateArgsConfig = b.truncateArgsConfig;
        this.pruneConfig = b.pruneConfig;
        this.model = b.model;
        this.strategy = b.strategy;
    }

    /** 触发压缩的消息数量阈值（0 表示禁用）。 */
    public int getTriggerMessages() {
        return triggerMessages;
    }

    /**
     * 触发压缩的预计 token 数阈值。
     * {@code 0} 表示动态模式，从模型上下文窗口减去 {@link #getReserved()} 计算。
     */
    public int getTriggerTokens() {
        return triggerTokens;
    }

    /**
     * 为压缩过程自身（摘要提示词及输出）预留的 token 缓冲。
     * 仅用于动态模式（{@code triggerTokens == 0}）。
     */
    public int getReserved() {
        return reserved;
    }

    /**
     * 原样保留的近期<em>对话</em>消息数（非 SYSTEM）。
     * 当 {@link #getKeepTokens()} 为 0（静态保留模式）时使用。
     */
    public int getKeepMessages() {
        return keepMessages;
    }

    /**
     * 保留尾部的 token 预算。
     * <ul>
     * <li>{@code > 0}：静态预算，从末尾扫描直到预算耗尽。</li>
     * <li>{@code 0}：改用 {@link #getKeepMessages()}（按消息数）。</li>
     * <li>{@code -1}：动态计算为 {@code min(keepTokensMax, max(keepTokensMin, usable * keepTokensRatio))}。</li>
     * </ul>
     */
    public int getKeepTokens() {
        return keepTokens;
    }

    public int getKeepTokensMin() {
        return keepTokensMin;
    }

    public int getKeepTokensMax() {
        return keepTokensMax;
    }

    public double getKeepTokensRatio() {
        return keepTokensRatio;
    }

    /** 摘要 LLM 调用的提示词模板，必须包含 {@code {messages}}。 */
    public String getSummaryPrompt() {
        return summaryPrompt;
    }

    /** 是否在压缩前从前缀提取并写入长期记忆。 */
    public boolean isFlushBeforeCompact() {
        return flushBeforeCompact;
    }

    /**
     * 摘要前轻量参数截断步骤的配置。
     * 为 {@code null} 时禁用参数截断。
     */
    public TruncateArgsConfig getTruncateArgsConfig() {
        return truncateArgsConfig;
    }

    /** 工具结果汇总裁剪的配置，为 {@code null} 时禁用裁剪。 */
    public PruneConfig getPruneConfig() {
        return pruneConfig;
    }

    /** 标准压缩前的可选策略；null 保持既有行为。 */
    public ConversationCompactionStrategy getStrategy() {
        return strategy;
    }

    /** 可选的摘要模型；null 表示使用智能体的主模型。 */
    public Model getModel() {
        return model;
    }

    /**
     * 创建解析后的配置副本，根据模型上下文窗口计算有效触发值与保留值。
     * 由 {@code CompactionMiddleware} 用于解析动态默认值。
     */
    public CompactionConfig withEffective(int effectiveTriggerTokens, int effectiveKeepTokens) {
        Builder b = new Builder();
        b.triggerMessages = this.triggerMessages;
        b.triggerTokens = effectiveTriggerTokens;
        b.reserved = this.reserved;
        b.keepMessages = this.keepMessages;
        b.keepTokens = effectiveKeepTokens;
        b.keepTokensMin = this.keepTokensMin;
        b.keepTokensMax = this.keepTokensMax;
        b.keepTokensRatio = this.keepTokensRatio;
        b.summaryPrompt = this.summaryPrompt;
        b.flushBeforeCompact = this.flushBeforeCompact;
        b.truncateArgsConfig = this.truncateArgsConfig;
        b.pruneConfig = this.pruneConfig;
        b.model = this.model;
        b.strategy = this.strategy;
        return new CompactionConfig(b);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private int triggerMessages = 50;
        private int triggerTokens = 0;
        private int reserved = 20_000;
        private int keepMessages = 20;
        private int keepTokens = -1;
        private int keepTokensMin = 2_000;
        private int keepTokensMax = 8_000;
        private double keepTokensRatio = 0.25;
        private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;
        private boolean flushBeforeCompact = true;
        private TruncateArgsConfig truncateArgsConfig = null;
        private PruneConfig pruneConfig = PruneConfig.defaults();
        private Model model = null;
        private ConversationCompactionStrategy strategy;

        /** 在标准压缩前使用可选策略；null 保持既有行为。 */
        public Builder strategy(ConversationCompactionStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        /** 对话消息数达到此值时触发压缩（0 表示禁用）。 */
        public Builder triggerMessages(int triggerMessages) {
            this.triggerMessages = triggerMessages;
            return this;
        }

        /**
         * 预计 token 数超过此值时触发压缩。
         * {@code 0}（默认）表示动态模式，按模型上下文窗口减去 {@link #reserved(int)} 计算。
         */
        public Builder triggerTokens(int triggerTokens) {
            this.triggerTokens = triggerTokens;
            return this;
        }

        /**
         * 为压缩过程预留的 token 缓冲（默认 20,000）。
         * 仅用于动态模式（{@code triggerTokens == 0}）。
         */
        public Builder reserved(int reserved) {
            this.reserved = reserved;
            return this;
        }

        /** 压缩后原样保留的近期消息数。 */
        public Builder keepMessages(int keepMessages) {
            this.keepMessages = keepMessages;
            return this;
        }

        /**
         * 保留尾部的 token 预算。
         * {@code -1}（默认）为动态模式；{@code 0} 使用 keepMessages；{@code > 0} 为静态预算。
         */
        public Builder keepTokens(int keepTokens) {
            this.keepTokens = keepTokens;
            return this;
        }

        public Builder keepTokensMin(int keepTokensMin) {
            this.keepTokensMin = keepTokensMin;
            return this;
        }

        public Builder keepTokensMax(int keepTokensMax) {
            this.keepTokensMax = keepTokensMax;
            return this;
        }

        public Builder keepTokensRatio(double keepTokensRatio) {
            this.keepTokensRatio = keepTokensRatio;
            return this;
        }

        /** 自定义摘要提示词，必须包含 {@code {messages}} 占位符。 */
        public Builder summaryPrompt(String summaryPrompt) {
            this.summaryPrompt = summaryPrompt;
            return this;
        }

        /** 是否在压缩前提取并写入长期记忆（默认为 true）。 */
        public Builder flushBeforeCompact(boolean flushBeforeCompact) {
            this.flushBeforeCompact = flushBeforeCompact;
            return this;
        }

        /**
         * 启用摘要前的轻量参数截断。
         * 传入 {@code null} 可禁用。
         */
        public Builder truncateArgs(TruncateArgsConfig config) {
            this.truncateArgsConfig = config;
            return this;
        }

        /**
         * 配置工具结果汇总裁剪，默认使用 {@link PruneConfig#defaults()}。
         * 传入 {@code null} 可禁用。
         */
        public Builder prune(PruneConfig config) {
            this.pruneConfig = config;
            return this;
        }

        /** 设置压缩（摘要）专用模型，可选用比智能体主推理模型更轻量、成本更低的模型。 */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * 通过模型 ID 字符串设置压缩专用模型，例如 {@code "openai:gpt-4.1-mini"}。
         *
         * @see ModelRegistry#resolve(String)
         */
        public Builder model(String modelId) {
            this.model = ModelRegistry.resolve(modelId);
            return this;
        }

        public CompactionConfig build() {
            return new CompactionConfig(this);
        }
    }

    // -------------------------------------------------------------------------
    // TruncateArgsConfig 参数截断配置
    // -------------------------------------------------------------------------

    /**
     * 摘要前轻量参数截断步骤的配置。
     *
     * <p>触发后，将旧消息（保留窗口之前）中 {@code ToolUseBlock} 的过长字符串参数
     * 截断到 {@link #getMaxArgLength()} 个字符。这是低成本、无需 LLM 的操作，
     * 用于防止冗长工具调用（如 {@code write_file}、{@code edit_file}）导致上下文膨胀。
     *
     * <p>通过 {@link Builder#truncateArgs(TruncateArgsConfig)} 启用时的默认值：
     * <ul>
     * <li>达到 25 条消息或 40,000 tokens 时触发。</li>
     * <li>最近 20 条消息保持不变。</li>
     * <li>参数最大长度为 2,000 个字符。</li>
     * </ul>
     */
    public static class TruncateArgsConfig {

        private final int triggerMessages;
        private final int triggerTokens;
        private final int keepMessages;
        private final int keepTokens;
        private final int maxArgLength;
        private final String truncationText;

        private TruncateArgsConfig(TruncateArgsBuilder b) {
            this.triggerMessages = b.triggerMessages;
            this.triggerTokens = b.triggerTokens;
            this.keepMessages = b.keepMessages;
            this.keepTokens = b.keepTokens;
            this.maxArgLength = b.maxArgLength;
            this.truncationText = b.truncationText;
        }

        public int getTriggerMessages() {
            return triggerMessages;
        }

        public int getTriggerTokens() {
            return triggerTokens;
        }

        public int getKeepMessages() {
            return keepMessages;
        }

        public int getKeepTokens() {
            return keepTokens;
        }

        /** 任一工具参数值允许的最大字符长度（默认 2,000）。 */
        public int getMaxArgLength() {
            return maxArgLength;
        }

        /** 截断参数在前 20 个字符后附加的后缀。 */
        public String getTruncationText() {
            return truncationText;
        }

        public static TruncateArgsBuilder builder() {
            return new TruncateArgsBuilder();
        }

        public static class TruncateArgsBuilder {

            private int triggerMessages = 25;
            private int triggerTokens = 40_000;
            private int keepMessages = 20;
            private int keepTokens = 0;
            private int maxArgLength = 2_000;
            private String truncationText = "...(argument truncated)";

            public TruncateArgsBuilder triggerMessages(int triggerMessages) {
                this.triggerMessages = triggerMessages;
                return this;
            }

            public TruncateArgsBuilder triggerTokens(int triggerTokens) {
                this.triggerTokens = triggerTokens;
                return this;
            }

            public TruncateArgsBuilder keepMessages(int keepMessages) {
                this.keepMessages = keepMessages;
                return this;
            }

            public TruncateArgsBuilder keepTokens(int keepTokens) {
                this.keepTokens = keepTokens;
                return this;
            }

            public TruncateArgsBuilder maxArgLength(int maxArgLength) {
                this.maxArgLength = maxArgLength;
                return this;
            }

            public TruncateArgsBuilder truncationText(String truncationText) {
                this.truncationText = truncationText;
                return this;
            }

            public TruncateArgsConfig build() {
                if (maxArgLength < 20) {
                    throw new IllegalArgumentException(
                            "maxArgLength must be at least 20 characters");
                }
                return new TruncateArgsConfig(this);
            }
        }
    }

    // -------------------------------------------------------------------------
    // PruneConfig 裁剪配置
    // -------------------------------------------------------------------------

    /**
     * 工具结果汇总裁剪的配置。
     *
     * <p>从后向前遍历工具结果消息，保护最近 {@link #getProtectTokens()} tokens 的工具输出。
     * 保护窗口之外的旧结果在可裁剪总量超过 {@link #getMinimumTokens()} 时，替换为首尾预览。
     *
     * <p>这是轻量、无需 LLM 的操作，在 {@link ConversationCompactor#compactIfNeeded} 中先于摘要执行。
     */
    public static class PruneConfig {

        private final int protectTokens;
        private final int minimumTokens;
        private final int maxOutputChars;
        private final Set<String> excludedTools;

        private PruneConfig(PruneBuilder b) {
            this.protectTokens = b.protectTokens;
            this.minimumTokens = b.minimumTokens;
            this.maxOutputChars = b.maxOutputChars;
            this.excludedTools = Set.copyOf(b.excludedTools);
        }

        public static PruneConfig defaults() {
            return new PruneBuilder().build();
        }

        /** 近期工具输出中绝不裁剪的 token 预算（默认 40,000）。 */
        public int getProtectTokens() {
            return protectTokens;
        }

        /** 真正执行裁剪前，可裁剪部分必须达到的最小 token 总量（默认 20,000）。 */
        public int getMinimumTokens() {
            return minimumTokens;
        }

        /** 每个被裁剪工具结果的首尾预览保留的最大字符数（默认 2,000）。 */
        public int getMaxOutputChars() {
            return maxOutputChars;
        }

        /** 排除在裁剪之外的工具名称，例如 read_file 和记忆工具。 */
        public Set<String> getExcludedTools() {
            return excludedTools;
        }

        public static PruneBuilder builder() {
            return new PruneBuilder();
        }

        public static class PruneBuilder {

            private int protectTokens = 40_000;
            private int minimumTokens = 20_000;
            private int maxOutputChars = 2_000;
            private Set<String> excludedTools =
                    Set.of("read_file", "memory_search", "memory_get", "session_search");

            public PruneBuilder protectTokens(int protectTokens) {
                this.protectTokens = protectTokens;
                return this;
            }

            public PruneBuilder minimumTokens(int minimumTokens) {
                this.minimumTokens = minimumTokens;
                return this;
            }

            public PruneBuilder maxOutputChars(int maxOutputChars) {
                this.maxOutputChars = maxOutputChars;
                return this;
            }

            public PruneBuilder excludedTools(Set<String> excludedTools) {
                this.excludedTools = excludedTools;
                return this;
            }

            public PruneConfig build() {
                return new PruneConfig(this);
            }
        }
    }
}
