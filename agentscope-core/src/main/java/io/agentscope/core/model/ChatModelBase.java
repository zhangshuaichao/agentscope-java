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

package io.agentscope.core.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.tracing.TracerRegistry;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * AgentScope 框架中所有模型的抽象基类。
 *
 * <p>此类提供模型通用功能，包括基本模型调用和追踪。
 */
public abstract class ChatModelBase implements Model {

    private int contextWindowSize;

    private Boolean nativeStructuredOutput;

    private Boolean nativeStructuredOutputWithTools;

    private Boolean supportsToolChoiceSpecific;

    @Override
    public int getContextWindowSize() {
        return contextWindowSize;
    }

    protected void setContextWindowSize(int contextWindowSize) {
        this.contextWindowSize = contextWindowSize;
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return nativeStructuredOutput != null ? nativeStructuredOutput : false;
    }

    protected void setNativeStructuredOutput(boolean nativeStructuredOutput) {
        this.nativeStructuredOutput = nativeStructuredOutput;
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return nativeStructuredOutputWithTools != null
                ? nativeStructuredOutputWithTools
                : supportsNativeStructuredOutput();
    }

    protected void setNativeStructuredOutputWithTools(boolean nativeStructuredOutputWithTools) {
        this.nativeStructuredOutputWithTools = nativeStructuredOutputWithTools;
    }

    @Override
    public boolean supportsToolChoiceSpecific() {
        return Boolean.TRUE.equals(supportsToolChoiceSpecific);
    }

    protected void setSupportsToolChoiceSpecific(boolean supportsToolChoiceSpecific) {
        this.supportsToolChoiceSpecific = supportsToolChoiceSpecific;
    }

    /**
     * 流式生成聊天补全响应。
     * 模型在内部使用配置的格式化器转换消息格式。
     *
     * <p>启用遥测后将采集追踪数据。
     *
     * @param messages 要发送给模型的 AgentScope 消息
     * @param tools 可选的工具模式列表（无工具时为 null 或空列表）
     * @param options 可选的生成选项（null 表示使用默认值）
     * @return 聊天响应组成的 Flux 流
     */
    @Override
    public final Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return TracerRegistry.get()
                .callModel(
                        this, messages, tools, options, () -> doStream(messages, tools, options));
    }

    /**
     * 聊天补全流式响应的内部实现。
     * 子类必须在这里实现各自的具体逻辑。
     *
     * @param messages 要发送给模型的 AgentScope 消息
     * @param tools 可选的工具模式列表（无工具时为 null 或空列表）
     * @param options 可选的生成选项（null 表示使用默认值）
     * @return 聊天响应组成的 Flux 流
     */
    protected abstract Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options);
}
