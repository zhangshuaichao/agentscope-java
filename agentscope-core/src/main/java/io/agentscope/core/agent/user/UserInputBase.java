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
package io.agentscope.core.agent.user;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 处理不同来源用户输入的策略接口。
 * 支持终端控制台、网页界面或程序化来源等可插拔输入实现。
 * 实现将原始用户输入转换为包含内容块及可选结构化数据的 UserInputData，保持不同输入通道的一致性。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public interface UserInputBase {

    /**
     * 处理用户输入并返回输入数据。
     *
     * @param agentId 智能体标识符
     * @param agentName 智能体名称
     * @param contextMessages 提示输入前可选展示的消息（例如助手回复）
     * @param structuredModel 定义结构化输入格式的可选类
     * @return 包含用户输入数据的 Mono
     */
    Mono<UserInputData> handleInput(
            String agentId, String agentName, List<Msg> contextMessages, Class<?> structuredModel);
}
