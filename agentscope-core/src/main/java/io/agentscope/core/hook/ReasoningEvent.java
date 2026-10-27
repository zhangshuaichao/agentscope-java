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
package io.agentscope.core.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.GenerateOptions;
import java.util.Objects;

/**
 * 推理相关事件的基类。
 *
 * <p>此密封类为所有推理事件提供公共上下文：
 * <ul>
 *   <li>{@link #getModelName()}：模型名称（例如 "qwen-plus"、"gpt-4"）</li>
 *   <li>{@link #getGenerateOptions()}：生成选项（temperature 等）</li>
 * </ul>
 *
 * <p>子类表示推理流程的不同阶段：
 * <ul>
 *   <li>{@link PreReasoningEvent}：LLM 调用前</li>
 *   <li>{@link PostReasoningEvent}：LLM 调用后</li>
 *   <li>{@link ReasoningChunkEvent}：流式输出期间</li>
 * </ul>
 *
 * @see PreReasoningEvent
 * @see PostReasoningEvent
 * @see ReasoningChunkEvent
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public abstract sealed class ReasoningEvent extends HookEvent
        permits PreReasoningEvent, PostReasoningEvent, ReasoningChunkEvent {

    private final String modelName;
    private final GenerateOptions generateOptions;

    /**
     * ReasoningEvent 的构造器。
     *
     * @param type 事件类型（不可为 null）
     * @param agent 智能体实例（不可为 null）
     * @param modelName 模型名称（不可为 null）
     * @param generateOptions 生成选项（使用模型默认值时可为 null）
     * @throws NullPointerException 当 type、agent 或 modelName 为 null 时抛出
     */
    protected ReasoningEvent(
            HookEventType type, Agent agent, String modelName, GenerateOptions generateOptions) {
        super(type, agent);
        this.modelName = Objects.requireNonNull(modelName, "modelName cannot be null");
        this.generateOptions = generateOptions;
    }

    /**
     * 获取模型名称。
     *
     * @return 模型名称（例如 "qwen-plus"、"gpt-4"）
     */
    public final String getModelName() {
        return modelName;
    }

    /**
     * 获取生成选项。
     *
     * @return 生成选项（temperature、maxTokens 等）；使用模型
     *     默认值时为 null
     */
    public final GenerateOptions getGenerateOptions() {
        return generateOptions;
    }
}
