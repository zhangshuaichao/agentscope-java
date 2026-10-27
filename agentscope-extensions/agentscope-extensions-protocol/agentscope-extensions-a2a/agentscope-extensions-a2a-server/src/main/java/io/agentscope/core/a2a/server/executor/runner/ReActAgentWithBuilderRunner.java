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

package io.agentscope.core.a2a.server.executor.runner;

import io.agentscope.core.ReActAgent;

/**
 * 基于 {@link ReActAgent} 的默认 {@link AgentRunner} 实现。
 *
 * <p>直接使用 {@link ReActAgent} 处理 A2A 客户端请求。
 * 为每个请求创建并缓存 {@link ReActAgent}，以便请求停止时中断。
 *
 * <p>{@link ReActAgent} 从开发者输入并配置的 {@link ReActAgent.Builder} 创建。
 */
public class ReActAgentWithBuilderRunner extends BaseReActAgentRunner implements AgentRunner {

    private final ReActAgent.Builder agentBuilder;

    private ReActAgentWithBuilderRunner(ReActAgent.Builder agentBuilder) {
        super();
        this.agentBuilder = agentBuilder;
    }

    @Override
    protected ReActAgent buildReActAgent() {
        return agentBuilder.build();
    }

    /**
     * 根据 {@link ReActAgent.Builder} 构建新的 {@link ReActAgentWithBuilderRunner} 实例。
     *
     * @param agentBuilder {@link ReActAgent} 构建器
     * @return 新的 {@link ReActAgentWithBuilderRunner} 实例
     */
    public static ReActAgentWithBuilderRunner newInstance(ReActAgent.Builder agentBuilder) {
        return new ReActAgentWithBuilderRunner(agentBuilder);
    }
}
