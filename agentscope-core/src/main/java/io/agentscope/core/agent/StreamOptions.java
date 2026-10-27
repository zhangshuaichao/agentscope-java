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

package io.agentscope.core.agent;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * {@link Agent#stream} API 的配置选项。
 *
 * <p>控制接收哪些事件类型以及如何传递流式内容。
 *
 * <p><b>推理过滤（Issue #265）：</b>某些流式存储会同时发出：
 * <ul>
 * <li><b>推理片段</b>：推理过程中的增量内容</li>
 * <li><b>推理结果</b>：最终汇总的推理输出</li>
 * </ul>
 *
 * <p>启用 {@link EventType#REASONING} 时，可通过 {@link #isIncludeReasoningChunk()}
 * 和 {@link #isIncludeReasoningResult()} 过滤这些推理相关事件。
 *
 * <p><b>用法示例：</b>
 *
 * <pre>{@code
 * // 仅推理事件，使用增量模式
 * StreamOptions options = StreamOptions.builder()
 *     .eventTypes(EventType.REASONING)
 *     .incremental(true)
 *     .build();
 *
 * // 推理事件，但隐藏中间增量，仅保留最终推理结果
 * StreamOptions options = StreamOptions.builder()
 *     .eventTypes(EventType.REASONING)
 *     .includeReasoningChunk(false)
 *     .includeReasoningResult(true)
 *     .incremental(true)
 *     .build();
 *
 * // 多个指定类型
 * StreamOptions options = StreamOptions.builder()
 *     .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
 *     .incremental(true)
 *     .build();
 * }</pre>
 */
public class StreamOptions {

    private final Set<EventType> eventTypes;
    private final boolean incremental;

    /**
     * 流式处理时是否包含推理过程的增量片段。
     * <p>为 false 时，流实现应过滤掉中间推理片段。
     */
    private final boolean includeReasoningChunk;

    /**
     * 回复中是否包含最终汇总的推理输出。
     * <p>为 false 时，流实现应过滤掉最终推理结果。
     */
    private final boolean includeReasoningResult;

    /**
     * 流式处理时是否包含工具执行的增量片段。
     * <p>为 false 时，流实现应过滤掉中间行动片段。
     */
    private final boolean includeActingChunk;

    /**
     * 流式处理时是否包含摘要生成的增量片段。
     * <p>为 false 时，流实现应过滤掉中间摘要片段。
     */
    private final boolean includeSummaryChunk;

    /**
     * 回复中是否包含最终汇总的摘要输出。
     * <p>为 false 时，流实现应过滤掉最终摘要结果。
     */
    private final boolean includeSummaryResult;

    /**
     * 由构建器调用的私有构造方法。
     *
     * @param builder 包含配置值的构建器
     */
    private StreamOptions(Builder builder) {
        this.eventTypes = builder.eventTypes;
        this.incremental = builder.incremental;
        this.includeReasoningChunk = builder.includeReasoningChunk;
        this.includeReasoningResult = builder.includeReasoningResult;
        this.includeActingChunk = builder.includeActingChunk;
        this.includeSummaryChunk = builder.includeSummaryChunk;
        this.includeSummaryResult = builder.includeSummaryResult;
    }

    /**
     * 默认选项：所有事件类型、增量模式，同时包含推理片段与推理结果。
     *
     * @return 使用默认配置的 StreamOptions
     */
    public static StreamOptions defaults() {
        return builder().build();
    }

    /**
     * 创建新的 StreamOptions 构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取应流式发送的事件类型集合。
     *
     * <p>集合包含 {@link EventType#ALL} 时，将流式发送所有事件类型。
     *
     * @return 要流式发送的事件类型集合
     */
    public Set<EventType> getEventTypes() {
        return eventTypes;
    }

    /**
     * 检查是否启用增量模式。
     *
     * <p>增量模式下，每次仅发送新增内容；累积模式下，每次发送全部已累积内容。
     *
     * @return 启用增量模式时为 true
     */
    public boolean isIncremental() {
        return incremental;
    }

    /**
     * 是否包含推理“片段”事件。
     *
     * <p>推理片段是流式推理过程中的增量内容。</p>
     *
     * @return 应包含推理片段时为 true
     */
    public boolean isIncludeReasoningChunk() {
        return includeReasoningChunk;
    }

    /**
     * 是否包含最终推理结果。
     *
     * <p>推理结果是回复中最终汇总的推理输出。</p>
     *
     * @return 应包含最终推理结果时为 true
     */
    public boolean isIncludeReasoningResult() {
        return includeReasoningResult;
    }

    /**
     * 是否包含行动（工具执行）片段事件。
     *
     * <p>行动片段是工具执行过程中通过 ToolEmitter 发出的增量输出。</p>
     *
     * @return 应包含行动片段时为 true
     */
    public boolean isIncludeActingChunk() {
        return includeActingChunk;
    }

    /**
     * 是否包含摘要片段事件。
     *
     * <p>摘要片段是达到最大迭代次数后生成摘要时的增量输出。</p>
     *
     * @return 应包含摘要片段时为 true
     */
    public boolean isIncludeSummaryChunk() {
        return includeSummaryChunk;
    }

    /**
     * 是否包含最终摘要结果。
     *
     * <p>摘要结果是达到最大迭代次数后最终汇总的摘要输出。</p>
     *
     * @return 应包含最终摘要结果时为 true
     */
    public boolean isIncludeSummaryResult() {
        return includeSummaryResult;
    }

    /**
     * 检查是否应流式发送指定事件类型。
     *
     * @param type 要检查的事件类型
     * @return 应发送此类型时为 true
     */
    public boolean shouldStream(EventType type) {
        return eventTypes.contains(EventType.ALL) || eventTypes.contains(type);
    }

    /**
     * 供流实现判断是否发出某种推理子类型的便捷方法。
     *
     * <p><b>TODO（Issue #265）：</b>将这些标志传递到流事件映射层，
     * 即推理事件转换为 Flux 发射项的位置（例如区分片段与结果时）。
     *
     * @param isChunk 推理事件为增量片段时为 true，为最终结果时为 false
     * @return 应包含此推理事件时为 true
     */
    public boolean shouldIncludeReasoningEmission(boolean isChunk) {
        return isChunk ? includeReasoningChunk : includeReasoningResult;
    }

    /**
     * 供流实现判断是否发出某种摘要子类型的便捷方法。
     *
     * @param isChunk 摘要事件为增量片段时为 true，为最终结果时为 false
     * @return 应包含此摘要事件时为 true
     */
    public boolean shouldIncludeSummaryEmission(boolean isChunk) {
        return isChunk ? includeSummaryChunk : includeSummaryResult;
    }

    /**
     * {@link StreamOptions} 的构建器。
     */
    public static class Builder {
        private Set<EventType> eventTypes = EnumSet.of(EventType.ALL);
        private boolean incremental = true;

        // 默认值为 "true"，以保持现有行为。
        private boolean includeReasoningChunk = true;
        private boolean includeReasoningResult = true;
        private boolean includeActingChunk = true;
        private boolean includeSummaryChunk = true;
        private boolean includeSummaryResult = true;

        /**
         * 设置要流式发送的事件类型。
         *
         * <p>Flux 只会发出匹配这些类型的事件。使用 {@link EventType#ALL} 接收全部类型。
         *
         * @param types 一个或多个事件类型
         * @return 当前构建器
         */
        public Builder eventTypes(EventType... types) {
            this.eventTypes = EnumSet.copyOf(Arrays.asList(types));
            return this;
        }

        /**
         * 设置流式内容是否使用增量模式。
         *
         * <p>控制流式内容的发送方式：
         * <ul>
         * <li>true（增量）：每次仅发送新增内容</li>
         * <li>false（累积）：每次发送全部已累积内容</li>
         * </ul>
         *
         * @param incremental true 表示增量模式，false 表示累积模式
         * @return 当前构建器
         */
        public Builder incremental(boolean incremental) {
            this.incremental = incremental;
            return this;
        }

        /**
         * 包含或排除增量推理片段事件。
         *
         * <p>启用 {@link EventType#REASONING} 时，某些提供方会在模型思考时发出推理增量（片段）。
         * 设为 false 可隐藏这些片段。</p>
         *
         * @param includeReasoningChunk true 表示包含片段事件，false 表示过滤掉它们
         * @return 当前构建器
         */
        public Builder includeReasoningChunk(boolean includeReasoningChunk) {
            this.includeReasoningChunk = includeReasoningChunk;
            return this;
        }

        /**
         * 包含或排除最终汇总的推理结果事件。
         *
         * <p>启用 {@link EventType#REASONING} 时，某些提供方会发出最终推理结果。设为 false 可隐藏它。</p>
         *
         * @param includeReasoningResult true 表示包含最终推理结果，false 表示过滤掉它
         * @return 当前构建器
         */
        public Builder includeReasoningResult(boolean includeReasoningResult) {
            this.includeReasoningResult = includeReasoningResult;
            return this;
        }

        /**
         * 包含或排除工具执行片段事件。
         *
         * <p>启用 {@link EventType#TOOL_RESULT} 时，工具可能通过 ToolEmitter 发出中间片段。
         * 设为 false 可隐藏这些片段，只接收最终工具结果。</p>
         *
         * @param includeActingChunk true 表示包含片段事件，false 表示过滤掉它们
         * @return 当前构建器
         */
        public Builder includeActingChunk(boolean includeActingChunk) {
            this.includeActingChunk = includeActingChunk;
            return this;
        }

        /**
         * 包含或排除摘要片段事件。
         *
         * <p>启用 {@link EventType#SUMMARY} 时，摘要生成可能发出中间片段。
         * 设为 false 可隐藏这些片段，只接收最终摘要结果。</p>
         *
         * @param includeSummaryChunk true 表示包含片段事件，false 表示过滤掉它们
         * @return 当前构建器
         */
        public Builder includeSummaryChunk(boolean includeSummaryChunk) {
            this.includeSummaryChunk = includeSummaryChunk;
            return this;
        }

        /**
         * 包含或排除最终汇总的摘要结果事件。
         *
         * <p>启用 {@link EventType#SUMMARY} 时，生成完成后会发出最终摘要结果。设为 false 可隐藏它。</p>
         *
         * @param includeSummaryResult true 表示包含最终摘要结果，false 表示过滤掉它
         * @return 当前构建器
         */
        public Builder includeSummaryResult(boolean includeSummaryResult) {
            this.includeSummaryResult = includeSummaryResult;
            return this;
        }

        public StreamOptions build() {
            return new StreamOptions(this);
        }
    }
}
