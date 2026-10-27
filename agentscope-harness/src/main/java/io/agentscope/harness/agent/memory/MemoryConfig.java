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
package io.agentscope.harness.agent.memory;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.time.Duration;
import java.util.Objects;

/**
 * 长期记忆流水线（提取写入、整合、维护）的统一配置。
 * 配合 {@link CompactionConfig} 配置上下文内的摘要流水线。
 *
 * <p>Harness 有三种由 LLM 驱动的记忆操作，各有独立的提示词和触发规则：
 * <ol>
 * <li><b>Flush（提取写入）</b>：从对话窗口提取长期记忆，写入当天的每日记忆账本
 * （{@code memory/YYYY-MM-DD.md}）。提示词由 {@link #flushPrompt()} 提供，
 * 默认使用 {@link MemoryFlushManager#DEFAULT_FLUSH_PROMPT}；触发策略为 {@link #flushTrigger()}。</li>
 * <li><b>Consolidation（整合）</b>：定期将每日账本合并到整理后的 {@code MEMORY.md}。
 * 提示词由 {@link #consolidationPrompt()} 提供，默认使用
 * {@link MemoryConsolidator#DEFAULT_CONSOLIDATION_PROMPT}；执行间隔由 {@link #consolidationMinGap()} 控制。</li>
 * <li><b>Compaction summary（压缩摘要）</b>：在推理前将对话前缀提炼为一条摘要消息。
 * 提示词位于 {@link CompactionConfig#getSummaryPrompt()}，应通过
 * {@code .compaction(CompactionConfig...)} 配置，而非此处。</li>
 * </ol>
 *
 * <p>提取写入和整合使用独立的节流窗口。节流策略允许首次符合条件的调用立即执行，
 * 最小间隔只约束后续运行之间的距离，不是首次延迟。{@link FlushTrigger#never()} 仅禁用调用级提取写入。
 *
 * <p>所有字段都有合理默认值；{@link #defaults()} 等价于 Harness 的历史行为，采用此配置不会改变默认行为。
 *
 * <p>【学习提示】对话压缩服务于当前模型窗口；每日账本和整理后的长期记忆服务于后续检索与复用。
 * 三者不能互当完整、无损的会话审计记录。
 */
public final class MemoryConfig {

    /** 默认的 {@code consolidationMaxTokens}。 */
    public static final int DEFAULT_CONSOLIDATION_MAX_TOKENS = 4_000;

    /** 默认的 {@code consolidationMinGap}，与 {@code MemoryMaintenanceMiddleware} 一致。 */
    public static final Duration DEFAULT_CONSOLIDATION_MIN_GAP = Duration.ofMinutes(30);

    /** 每日账本归档前的默认保留时间。 */
    public static final int DEFAULT_DAILY_FILE_RETENTION_DAYS = 90;

    /** 调用级提取写入钩子的策略，参见 {@link FlushTrigger}。 */
    public enum FlushMode {
        /** 每次智能体调用结束后提取并写入记忆。 */
        ALWAYS,
        /** 禁用调用级记忆提取（原生会话日志仍会运行）。 */
        NEVER,
        /** 每个 {@link FlushTrigger#minGap()} 间隔内最多提取写入一次。 */
        THROTTLED
    }

    /**
     * {@link io.agentscope.harness.agent.middleware.MemoryFlushMiddleware} 的触发策略。
     *
     * <p>节流触发器允许首次符合条件的调用立即执行。最小间隔在后续调用级提取写入之间计量，
     * 与整合维护相互独立。{@link #never()} 仅禁用这条调用级提取写入路径。
     *
     * <p>{@code throttled(Duration.ZERO)} 会规范化为 {@link #always()}，调用方不必为退化情况另写分支。
     */
    public static final class FlushTrigger {

        private static final FlushTrigger ALWAYS_INSTANCE =
                new FlushTrigger(FlushMode.ALWAYS, Duration.ZERO);
        private static final FlushTrigger NEVER_INSTANCE =
                new FlushTrigger(FlushMode.NEVER, Duration.ZERO);

        private final FlushMode mode;
        private final Duration minGap;

        private FlushTrigger(FlushMode mode, Duration minGap) {
            this.mode = mode;
            this.minGap = minGap;
        }

        public static FlushTrigger always() {
            return ALWAYS_INSTANCE;
        }

        public static FlushTrigger never() {
            return NEVER_INSTANCE;
        }

        public static FlushTrigger throttled(Duration minGap) {
            if (minGap == null) {
                throw new IllegalArgumentException("minGap must not be null");
            }
            if (minGap.isNegative()) {
                throw new IllegalArgumentException("minGap must not be negative");
            }
            if (minGap.isZero()) {
                return ALWAYS_INSTANCE;
            }
            return new FlushTrigger(FlushMode.THROTTLED, minGap);
        }

        public FlushMode mode() {
            return mode;
        }

        public Duration minGap() {
            return minGap;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FlushTrigger other)) return false;
            return mode == other.mode && Objects.equals(minGap, other.minGap);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mode, minGap);
        }

        @Override
        public String toString() {
            return mode == FlushMode.THROTTLED
                    ? "FlushTrigger{THROTTLED, minGap=" + minGap + "}"
                    : "FlushTrigger{" + mode + "}";
        }
    }

    private final Model model;
    private final String flushPrompt;
    private final String consolidationPrompt;
    private final int consolidationMaxTokens;
    private final Duration consolidationMinGap;
    private final int dailyFileRetentionDays;
    private final FlushTrigger flushTrigger;

    private MemoryConfig(Builder b) {
        this.model = b.model;
        this.flushPrompt = b.flushPrompt;
        this.consolidationPrompt = b.consolidationPrompt;
        this.consolidationMaxTokens = b.consolidationMaxTokens;
        this.consolidationMinGap = b.consolidationMinGap;
        this.dailyFileRetentionDays = b.dailyFileRetentionDays;
        this.flushTrigger = b.flushTrigger;
    }

    /**
     * 记忆操作（提取写入与整合）可选的专用模型。
     * {@code null} 表示使用智能体的主模型。
     */
    public Model model() {
        return model;
    }

    /**
     * 覆盖提取写入提示词。{@code null} 表示使用
     * {@link MemoryFlushManager#DEFAULT_FLUSH_PROMPT}。
     */
    public String flushPrompt() {
        return flushPrompt;
    }

    /**
     * 覆盖整合提示词。{@code null} 表示使用
     * {@link MemoryConsolidator#DEFAULT_CONSOLIDATION_PROMPT}。
     *
     * <p>自定义提示词必须恰好包含两个 {@code %d} 占位符，依次表示最大 token 数和最大字符数。
     * 构建器在创建配置时强制检查此约束。
     */
    public String consolidationPrompt() {
        return consolidationPrompt;
    }

    public int consolidationMaxTokens() {
        return consolidationMaxTokens;
    }

    /**
     * 整合/维护运行之间的最小间隔。首次符合条件的调用立即运行；
     * 此时长不是首次延迟，且独立于 {@link #flushTrigger()}。
     */
    public Duration consolidationMinGap() {
        return consolidationMinGap;
    }

    public int dailyFileRetentionDays() {
        return dailyFileRetentionDays;
    }

    public FlushTrigger flushTrigger() {
        return flushTrigger;
    }

    /** 返回与 Harness 历史默认行为等价的配置。 */
    public static MemoryConfig defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Model model = null;
        private String flushPrompt = null;
        private String consolidationPrompt = null;
        private int consolidationMaxTokens = DEFAULT_CONSOLIDATION_MAX_TOKENS;
        private Duration consolidationMinGap = DEFAULT_CONSOLIDATION_MIN_GAP;
        private int dailyFileRetentionDays = DEFAULT_DAILY_FILE_RETENTION_DAYS;
        private FlushTrigger flushTrigger = FlushTrigger.always();

        /**
         * 为记忆操作（提取写入与整合）设置专用模型，可选用比智能体主推理模型更轻量、成本更低的模型。
         * 未设置时使用智能体的主模型。
         */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * 通过模型 ID 字符串设置记忆操作的专用模型，例如 {@code "openai:gpt-4.1-mini"}。
         *
         * @see ModelRegistry#resolve(String)
         */
        public Builder model(String modelId) {
            this.model = ModelRegistry.resolve(modelId);
            return this;
        }

        /**
         * 覆盖 {@link MemoryFlushManager#flushMemories} 使用的提示词。
         * {@code null} 恢复默认值。文本原样作为 SYSTEM 消息传递，不需要任何占位符。
         */
        public Builder flushPrompt(String flushPrompt) {
            this.flushPrompt = flushPrompt;
            return this;
        }

        /**
         * 覆盖 {@link MemoryConsolidator#consolidate} 使用的提示词。
         * 提示词通过 {@link String#format} 传入两个 {@code int} 参数（最大 token 数、最大字符数）渲染，
         * 因此自定义提示词必须恰好包含两个 {@code %d} 占位符。{@code null} 恢复默认值。
         *
         * @throws IllegalArgumentException 提示词未恰好包含两个 {@code %d} 占位符时抛出
         */
        public Builder consolidationPrompt(String consolidationPrompt) {
            if (consolidationPrompt != null) {
                int count = countOccurrences(consolidationPrompt, "%d");
                if (count != 2) {
                    throw new IllegalArgumentException(
                            "consolidationPrompt must contain exactly two %d placeholders "
                                    + "(max-tokens, max-chars), found "
                                    + count);
                }
            }
            this.consolidationPrompt = consolidationPrompt;
            return this;
        }

        /** 传给整合提示词的 token 预算，必须为正数。 */
        public Builder consolidationMaxTokens(int consolidationMaxTokens) {
            if (consolidationMaxTokens <= 0) {
                throw new IllegalArgumentException(
                        "consolidationMaxTokens must be positive, got " + consolidationMaxTokens);
            }
            this.consolidationMaxTokens = consolidationMaxTokens;
            return this;
        }

        /**
         * 整合/维护运行之间的最小间隔。首次符合条件的调用立即运行；
         * 此时长不是首次延迟，且独立于 {@link MemoryConfig#flushTrigger()}。不得为 null。
         */
        public Builder consolidationMinGap(Duration consolidationMinGap) {
            if (consolidationMinGap == null) {
                throw new IllegalArgumentException("consolidationMinGap must not be null");
            }
            if (consolidationMinGap.isNegative()) {
                throw new IllegalArgumentException("consolidationMinGap must not be negative");
            }
            this.consolidationMinGap = consolidationMinGap;
            return this;
        }

        /** 每日账本移至 {@code memory/archive/} 前的保留天数。 */
        public Builder dailyFileRetentionDays(int dailyFileRetentionDays) {
            if (dailyFileRetentionDays <= 0) {
                throw new IllegalArgumentException(
                        "dailyFileRetentionDays must be positive, got " + dailyFileRetentionDays);
            }
            this.dailyFileRetentionDays = dailyFileRetentionDays;
            return this;
        }

        /** 调用级提取写入钩子的触发策略，不得为 null。 */
        public Builder flushTrigger(FlushTrigger flushTrigger) {
            if (flushTrigger == null) {
                throw new IllegalArgumentException("flushTrigger must not be null");
            }
            this.flushTrigger = flushTrigger;
            return this;
        }

        public MemoryConfig build() {
            return new MemoryConfig(this);
        }

        private static int countOccurrences(String haystack, String needle) {
            int count = 0;
            int idx = 0;
            while ((idx = haystack.indexOf(needle, idx)) != -1) {
                count++;
                idx += needle.length();
            }
            return count;
        }
    }
}
