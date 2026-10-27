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
 * LLM 推理之前触发的事件。
 *
 * <p><b>可修改：</b>是，通过 {@link #setInputMessages(List)}
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：智能体记忆</li>
 *   <li>{@link #getModelName()}：模型名称（例如 "qwen-plus"）</li>
 *   <li>{@link #getGenerateOptions()}：生成选项（temperature 等）</li>
 *   <li>{@link #getInputMessages()}：要发送给 LLM 的消息（可修改）</li>
 * </ul>
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>向提示词注入提示或额外上下文</li>
 *   <li>过滤或修改已有消息</li>
 *   <li>动态添加系统指令</li>
 *   <li>记录推理输入</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class PreReasoningEvent extends ReasoningEvent {

    private List<Msg> inputMessages;
    private GenerateOptions overriddenGenerateOptions;

    /**
     * PreReasoningEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param modelName 模型名称（不可为 null）
     * @param generateOptions 生成选项（可为 null）
     * @param inputMessages 要发送给 LLM 的消息（不可为 null）
     * @throws NullPointerException 当 agent、modelName 或 inputMessages 为 null 时抛出
     */
    public PreReasoningEvent(
            Agent agent,
            String modelName,
            GenerateOptions generateOptions,
            List<Msg> inputMessages) {
        super(HookEventType.PRE_REASONING, agent, modelName, generateOptions);
        this.inputMessages =
                new ArrayList<>(
                        Objects.requireNonNull(inputMessages, "inputMessages cannot be null"));
    }

    /**
     * 获取将发送给 LLM 进行推理的消息。
     *
     * @return 输入消息
     */
    public List<Msg> getInputMessages() {
        return inputMessages;
    }

    /**
     * 修改要发送给 LLM 的消息。
     *
     * @param inputMessages 新消息列表（不可为 null）
     * @throws NullPointerException 当 inputMessages 为 null 时抛出
     */
    public void setInputMessages(List<Msg> inputMessages) {
        this.inputMessages = Objects.requireNonNull(inputMessages, "inputMessages cannot be null");
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
     * 为本次推理调用设置自定义生成选项。
     *
     * <p>这允许钩子覆盖默认生成选项，例如设置
     * 用于结构化输出的特定 tool_choice。
     *
     * @param generateOptions 自定义生成选项
     */
    public void setGenerateOptions(GenerateOptions generateOptions) {
        this.overriddenGenerateOptions = generateOptions;
    }
}
