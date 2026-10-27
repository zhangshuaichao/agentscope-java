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
package io.agentscope.core.agui.adapter;

import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.BaseEventPropertiesEnricher;
import io.agentscope.core.tool.ToolMergeMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * AG-UI 智能体适配器的配置。
 *
 * <p>提供工具合并、状态事件等适配器行为的配置项。
 */
public class AguiAdapterConfig {

    private final ToolMergeMode toolMergeMode;
    private final boolean emitStateEvents;
    // 前端/外部工具始终发送参数
    private final boolean emitToolCallArgs;
    private final boolean emitTokenUsage;
    private final boolean enableReasoning;
    private final boolean emitRunFinishedAfterError;
    private final Duration runTimeout;
    private final String defaultAgentId;
    private final List<AgentEventConverter> eventConverters;
    private final List<AguiEventEnricher> eventEnrichers;
    private final boolean baseEventPropertiesEnricherEnabled;
    private final boolean emitSubagentEventsAsNative;

    private AguiAdapterConfig(Builder builder) {
        this.toolMergeMode = builder.toolMergeMode;
        this.emitStateEvents = builder.emitStateEvents;
        this.emitToolCallArgs = builder.emitToolCallArgs;
        this.emitTokenUsage = builder.emitTokenUsage;
        this.enableReasoning = builder.enableReasoning;
        this.emitRunFinishedAfterError = builder.emitRunFinishedAfterError;
        this.runTimeout = builder.runTimeout;
        this.defaultAgentId = builder.defaultAgentId;
        this.eventConverters = List.copyOf(builder.eventConverters);
        this.eventEnrichers = buildEventEnrichers(builder);
        this.baseEventPropertiesEnricherEnabled = builder.baseEventPropertiesEnricherEnabled;
        this.emitSubagentEventsAsNative = builder.emitSubagentEventsAsNative;
    }

    /**
     * 获取工具合并模式。
     *
     * @return 工具合并模式
     */
    public ToolMergeMode getToolMergeMode() {
        return toolMergeMode;
    }

    /**
     * 检查是否发送状态事件。
     *
     * @return 应发送状态事件时为 true
     */
    public boolean isEmitStateEvents() {
        return emitStateEvents;
    }

    /**
     * 检查是否流式发送工具调用参数。
     *
     * @return 应发送工具调用参数事件时为 true
     */
    public boolean isEmitToolCallArgs() {
        return emitToolCallArgs;
    }

    /**
     * 检查是否发送模型 token 用量。
     *
     * <p>启用时累加 {@code ModelCallEndEvent} 的用量，并作为名为 {@code token_usage} 的 AG-UI CUSTOM 事件发送。默认为 false。
     *
     * @return 应发送 token 用量事件时为 true
     */
    public boolean isEmitTokenUsage() {
        return emitTokenUsage;
    }

    /**
     * 检查是否发送推理/思考内容。
     *
     * <p>启用时按照 AG-UI Reasoning 草案将 ThinkingBlock 内容转换为 REASONING_* 事件。禁用时（默认）忽略 ThinkingBlock，不发送推理事件。
     *
     * @return 应发送推理事件时为 true
     */
    public boolean isEnableReasoning() {
        return enableReasoning;
    }

    /**
     * 检查是否在 {@code RUN_ERROR} 后发送 {@code RUN_FINISHED}。
     *
     * <p>AG-UI 将 {@code RUN_ERROR} 和 {@code RUN_FINISHED} 视为互斥的终止事件。默认为 {@code false}（标准 AG-UI）。仅为仍要求错误后发送完成事件的旧客户端设置 {@code true}。
     *
     * @return 应在 {@code RUN_ERROR} 后发送 {@code RUN_FINISHED} 时为 true
     */
    public boolean isEmitRunFinishedAfterError() {
        return emitRunFinishedAfterError;
    }

    /**
     * 获取运行超时时长。
     *
     * @return 运行超时时长
     */
    public Duration getRunTimeout() {
        return runTimeout;
    }

    /**
     * 获取默认智能体 ID。
     *
     * @return 默认智能体 ID；未设置时为 null
     */
    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    /**
     * 获取自定义 AgentEvent 转换器。
     *
     * @return 自定义事件转换器
     */
    public List<AgentEventConverter> getEventConverters() {
        return eventConverters;
    }

    /**
     * 获取配置的 AG-UI 事件增强器，包括启用时的默认基础属性增强器。
     *
     * @return AG-UI 事件增强器
     */
    public List<AguiEventEnricher> getEventEnrichers() {
        return eventEnrichers;
    }

    /**
     * 检查是否启用默认基础事件属性增强器。
     *
     * @return 启用默认基础事件属性增强器时为 true
     */
    public boolean isBaseEventPropertiesEnricherEnabled() {
        return baseEventPropertiesEnricherEnabled;
    }

    /**
     * 为 {@code false}（默认）时，{@code source} 非空的 AgentEvent（子智能体事件）以 {@code subagent.*} 命名空间中的 AG-UI {@code CUSTOM} 事件发送，而非原生 {@code TEXT_MESSAGE_*} / 运行生命周期事件。
     *
     * @return 保留子智能体事件的旧版原生呈现方式时为 true
     */
    public boolean isEmitSubagentEventsAsNative() {
        return emitSubagentEventsAsNative;
    }

    /**
     * 创建 AguiAdapterConfig 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建默认配置。
     *
     * @return 默认配置
     */
    public static AguiAdapterConfig defaultConfig() {
        return builder().build();
    }

    private static List<AguiEventEnricher> buildEventEnrichers(Builder builder) {
        List<AguiEventEnricher> enrichers = new ArrayList<>();
        if (builder.baseEventPropertiesEnricherEnabled) {
            enrichers.add(new BaseEventPropertiesEnricher());
        }
        enrichers.addAll(builder.eventEnrichers);
        return List.copyOf(enrichers);
    }

    /**
     * AguiAdapterConfig 的构建器。
     */
    public static class Builder {

        private ToolMergeMode toolMergeMode = ToolMergeMode.MERGE_EXTERNAL_PRIORITY;
        private boolean emitStateEvents = true;
        private boolean emitToolCallArgs = true;
        private boolean emitTokenUsage = false;
        private boolean enableReasoning = false;
        private boolean emitRunFinishedAfterError = false;
        private Duration runTimeout = Duration.ofMinutes(10);
        private String defaultAgentId;
        private final List<AgentEventConverter> eventConverters = new ArrayList<>();
        private final List<AguiEventEnricher> eventEnrichers = new ArrayList<>();
        private boolean baseEventPropertiesEnricherEnabled = false;
        private boolean emitSubagentEventsAsNative = false;

        /**
         * 设置工具合并模式。
         *
         * @param toolMergeMode 工具合并模式
         * @return 当前构建器
         */
        public Builder toolMergeMode(ToolMergeMode toolMergeMode) {
            this.toolMergeMode = toolMergeMode;
            return this;
        }

        /**
         * 设置是否发送状态事件。
         *
         * @param emitStateEvents 为 true 时发送状态事件
         * @return 当前构建器
         */
        public Builder emitStateEvents(boolean emitStateEvents) {
            this.emitStateEvents = emitStateEvents;
            return this;
        }

        /**
         * 设置是否发送工具调用参数事件。
         *
         * @param emitToolCallArgs 为 true 时发送工具调用参数事件
         * @return 当前构建器
         */
        public Builder emitToolCallArgs(boolean emitToolCallArgs) {
            this.emitToolCallArgs = emitToolCallArgs;
            return this;
        }

        /**
         * 设置是否发送累计 token 用量事件。
         *
         * <p>启用时，每个包含用量的 {@code ModelCallEndEvent} 都会发送名为 {@code token_usage} 的 CUSTOM 事件。默认为 false。
         *
         * @param emitTokenUsage 为 true 时发送 token 用量事件
         * @return 当前构建器
         */
        public Builder emitTokenUsage(boolean emitTokenUsage) {
            this.emitTokenUsage = emitTokenUsage;
            return this;
        }

        /**
         * 设置是否启用推理/思考内容输出。
         *
         * <p>启用时按照 AG-UI Reasoning 草案将 ThinkingBlock 内容转换为 REASONING_* 事件。为保持向后兼容并考虑隐私合规，默认禁用。
         *
         * @param enableReasoning 为 true 时启用推理事件
         * @return 当前构建器
         */
        public Builder enableReasoning(boolean enableReasoning) {
            this.enableReasoning = enableReasoning;
            return this;
        }

        /**
         * 设置是否在 {@code RUN_ERROR} 后发送 {@code RUN_FINISHED}。
         *
         * <p>默认为 {@code false}，遵守 AG-UI 单个终止事件的不变式（仅 {@code RUN_ERROR}）。为即使失败仍要求完成事件的旧客户端设置 {@code true}。
         *
         * @param emitRunFinishedAfterError 为 true 时在 {@code RUN_ERROR} 后发送 {@code RUN_FINISHED}
         * @return 当前构建器
         */
        public Builder emitRunFinishedAfterError(boolean emitRunFinishedAfterError) {
            this.emitRunFinishedAfterError = emitRunFinishedAfterError;
            return this;
        }

        /**
         * 设置运行超时时长。
         *
         * @param runTimeout 超时时长
         * @return 当前构建器
         */
        public Builder runTimeout(Duration runTimeout) {
            this.runTimeout = runTimeout;
            return this;
        }

        /**
         * 设置默认智能体 ID。
         *
         * @param defaultAgentId 默认智能体 ID
         * @return 当前构建器
         */
        public Builder defaultAgentId(String defaultAgentId) {
            this.defaultAgentId = defaultAgentId;
            return this;
        }

        /**
         * 添加自定义 AgentEvent 转换器。同一 AgentEvent 类型的自定义转换器会覆盖内置转换器。
         *
         * @param eventConverter 要添加的转换器
         * @return 当前构建器
         */
        public Builder addEventConverter(AgentEventConverter eventConverter) {
            this.eventConverters.add(
                    Objects.requireNonNull(eventConverter, "eventConverter cannot be null"));
            return this;
        }

        /**
         * 替换自定义 AgentEvent 转换器。
         *
         * @param eventConverters 要使用的转换器
         * @return 当前构建器
         */
        public Builder eventConverters(List<AgentEventConverter> eventConverters) {
            this.eventConverters.clear();
            if (eventConverters != null) {
                eventConverters.forEach(this::addEventConverter);
            }
            return this;
        }

        /**
         * 添加 AG-UI 事件增强器。
         *
         * @param eventEnricher 要添加的增强器
         * @return 当前构建器
         */
        public Builder addEventEnricher(AguiEventEnricher eventEnricher) {
            this.eventEnrichers.add(
                    Objects.requireNonNull(eventEnricher, "eventEnricher cannot be null"));
            return this;
        }

        /**
         * 替换自定义 AG-UI 事件增强器。
         *
         * @param eventEnrichers 要使用的增强器
         * @return 当前构建器
         */
        public Builder eventEnrichers(List<AguiEventEnricher> eventEnrichers) {
            this.eventEnrichers.clear();
            if (eventEnrichers != null) {
                eventEnrichers.forEach(this::addEventEnricher);
            }
            return this;
        }

        /**
         * 设置是否启用默认基础事件属性增强器。
         *
         * @param baseEventPropertiesEnricherEnabled 为 true 时启用默认增强器
         * @return 当前构建器
         */
        public Builder baseEventPropertiesEnricherEnabled(
                boolean baseEventPropertiesEnricherEnabled) {
            this.baseEventPropertiesEnricherEnabled = baseEventPropertiesEnricherEnabled;
            return this;
        }

        /**
         * 设置子智能体来源事件是否使用原生 AG-UI 事件类型。
         *
         * <p>默认为 {@code false}：子智能体事件转换为名为 {@code subagent.*} 的 {@code CUSTOM} 事件。设置为 {@code true} 可恢复旧行为，即子智能体文本和生命周期事件映射为与父智能体相同的 AG-UI 类型。
         *
         * @param emitSubagentEventsAsNative 为 true 时采用旧版原生呈现方式
         * @return 当前构建器
         */
        public Builder emitSubagentEventsAsNative(boolean emitSubagentEventsAsNative) {
            this.emitSubagentEventsAsNative = emitSubagentEventsAsNative;
            return this;
        }

        /**
         * 构建配置。
         *
         * @return 构建完成的配置
         */
        public AguiAdapterConfig build() {
            return new AguiAdapterConfig(this);
        }
    }
}
