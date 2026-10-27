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
package io.agentscope.core.middleware;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;

/**
 * {@link MiddlewareBase#onModelCall} 的输入上下文。
 *
 * @param messages 要发送给模型的消息
 * @param tools    非 null 的工具模式列表；没有可用工具时为空
 * @param options  生成选项
 * @param model    要调用的模型实例
 */
public record ModelCallInput(
        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options, Model model) {

    public ModelCallInput {
        tools = tools == null ? List.of() : tools;
    }
}
