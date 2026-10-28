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
package io.agentscope.spring.boot.admin.service;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * {@code session:compact} 操作策略。
 *
 * <p>接收当前对话缓冲区和已有滚动摘要，返回新摘要文本。清空／截断 {@code AgentState.context} 并持久化由调用方负责；此接口仅决定新摘要应包含什么。
 */
public interface SummarizationStrategy {

    /**
     * 生成新的滚动摘要。
     *
     * @param model 要使用的模型，不得为 null
     * @param existingSummary 旧摘要，不存在时为空字符串
     * @param messagesToFold 当前智能体上下文中要折叠的消息，通常不含调用方将原样保留的最后 {@code keepLastMessages} 条
     * @return 新摘要文本
     */
    Mono<String> summarize(Model model, String existingSummary, List<Msg> messagesToFold);
}
