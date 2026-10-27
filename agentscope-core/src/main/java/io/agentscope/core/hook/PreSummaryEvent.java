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
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 达到最大迭代次数时，在摘要生成之前触发的事件。
 *
 * <p><b>可修改：</b>是，通过 {@link #setInputMessages(List)}、{@link #setGenerateOptions(GenerateOptions)}
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：智能体记忆</li>
 *   <li>{@link #getModelName()}：模型名称（例如 "qwen-plus"）</li>
 *   <li>{@link #getGenerateOptions()}：生成选项（temperature 等）</li>
 *   <li>{@link #getInputMessages()}：发送给 LLM 生成摘要的消息（可修改）</li>
 *   <li>{@link #getMaxIterations()}：为智能体配置的最大迭代次数</li>
 *   <li>{@link #getCurrentIteration()}：触发摘要时的当前迭代次数</li>
 * </ul>
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>向摘要提示词注入额外上下文</li>
 *   <li>修改摘要系统指令</li>
 *   <li>修改摘要生成参数</li>
 *   <li>记录摘要生成输入</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class PreSummaryEvent extends SummaryEvent {

    private List<Msg> inputMessages;
    private GenerateOptions overriddenGenerateOptions;
    private final int maxIterations;
    private final int currentIteration;

    /**
     * PreSummaryEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param modelName 模型名称（不可为 null）
     * @param generateOptions 生成选项（可为 null）
     * @param inputMessages 发送给 LLM 生成摘要的消息（不可为 null）
     * @param maxIterations 为智能体配置的最大迭代次数
     * @param currentIteration 触发摘要时的当前迭代次数
     * @throws NullPointerException 当 agent、modelName 或 inputMessages 为 null 时抛出
     */
    public PreSummaryEvent(
            Agent agent,
            String modelName,
            GenerateOptions generateOptions,
            List<Msg> inputMessages,
            int maxIterations,
            int currentIteration) {
        super(HookEventType.PRE_SUMMARY, agent, modelName, generateOptions);
        this.inputMessages =
                new ArrayList<>(
                        Objects.requireNonNull(inputMessages, "inputMessages cannot be null"));
        this.maxIterations = maxIterations;
        this.currentIteration = currentIteration;
    }

    /**
     * 获取将发送给 LLM 生成摘要的消息。
     *
     * @return 输入消息
     */
    public List<Msg> getInputMessages() {
        return inputMessages;
    }

    /**
     * 修改要发送给 LLM 生成摘要的消息。
     *
     * @param inputMessages 新消息列表（不可为 null）
     * @throws NullPointerException 当 inputMessages 为 null 时抛出
     */
    public void setInputMessages(List<Msg> inputMessages) {
        this.inputMessages = Objects.requireNonNull(inputMessages, "inputMessages cannot be null");
    }

    /**
     * 获取为智能体配置的最大迭代次数。
     *
     * @return 最大迭代次数
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * 获取触发摘要时的当前迭代次数。
     *
     * @return 当前迭代次数
     */
    public int getCurrentIteration() {
        return currentIteration;
    }

    /**
     * 获取实际生效的生成选项。
     *
     * <p>如果通过 {@link #setGenerateOptions(GenerateOptions)} 设置过覆盖选项，则返回该选项；
     * 否则返回父类的原始选项。
     *
     * @return 实际生效的生成选项
     */
    public GenerateOptions getEffectiveGenerateOptions() {
        return overriddenGenerateOptions != null
                ? overriddenGenerateOptions
                : super.getGenerateOptions();
    }

    /**
     * 为本次摘要调用设置自定义生成选项。
     *
     * <p>这允许钩子覆盖默认生成选项。
     *
     * @param generateOptions 自定义生成选项
     */
    public void setGenerateOptions(GenerateOptions generateOptions) {
        this.overriddenGenerateOptions = generateOptions;
    }
}
