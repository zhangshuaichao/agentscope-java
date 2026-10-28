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

package io.agentscope.spring.boot.a2a.runner;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.executor.runner.BaseReActAgentRunner;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 基于 {@link ReActAgent} 的默认 {@link AgentRunner} 实现。
 *
 * <p>直接使用 {@link ReActAgent} 处理 A2A 客户端请求。此实现通过提供方取得 {@link ReActAgent}，并按请求缓存，以便请求停止时进行中断。
 *
 * <p>{@link ReActAgent} 应从 {@link org.springframework.beans.factory.ObjectProvider} 获取；提供方对应
 * {@code io.agentscope.spring.boot.AgentscopeAutoConfiguration#agentscopeReActAgent(Model, Memory, Toolkit,
 * AgentscopeProperties)}。
 */
public class ReActAgentWithStarterRunner extends BaseReActAgentRunner implements AgentRunner {

    private final ObjectProvider<ReActAgent> agentBuilder;

    private ReActAgentWithStarterRunner(ObjectProvider<ReActAgent> agentBuilder) {
        super();
        this.agentBuilder = agentBuilder;
    }

    @Override
    protected ReActAgent buildReActAgent() {
        // 【学习提示】ObjectProvider 按实际 bean 作用域取对象，不保证每次新建；默认核心配置是单例。
        // 若需要请求级独立实例，应由应用明确提供 prototype bean，并结合基类的缓存／关闭行为管理生命周期。
        return agentBuilder.getObject();
    }

    /**
     * 由 {@link org.springframework.beans.factory.ObjectProvider} 构建新的 {@link ReActAgentWithStarterRunner} 实例。
     *
     * @param agentBuilder {@link ReActAgent} 的 ObjectProvider
     * @return 新的 {@link ReActAgentWithStarterRunner} 实例
     */
    public static ReActAgentWithStarterRunner newInstance(ObjectProvider<ReActAgent> agentBuilder) {
        return new ReActAgentWithStarterRunner(agentBuilder);
    }
}
