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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;

/**
 * 基于 {@link ReActAgent} 的 {@link AgentRunner} 抽象实现。
 *
 * <p>直接使用 {@link ReActAgent} 处理 A2A 客户端请求。
 * 此实现应为每个请求创建 {@link ReActAgent} 并缓存，以便请求停止时中断。
 */
public abstract class BaseReActAgentRunner implements AgentRunner {

    private final Map<String, ReActAgent> agentCache;

    protected BaseReActAgentRunner() {
        this.agentCache = new ConcurrentHashMap<>();
    }

    @Override
    public String getAgentName() {
        return buildReActAgent().getName();
    }

    @Override
    public String getAgentDescription() {
        return buildReActAgent().getDescription();
    }

    @Override
    public Flux<AgentEvent> streamEvents(List<Msg> requestMessages, AgentRequestOptions options) {
        if (agentCache.containsKey(options.getTaskId())) {
            throw new IllegalStateException(
                    "Agent already exists for taskId: " + options.getTaskId());
        }
        ReActAgent agent = buildReActAgent();
        agentCache.put(options.getTaskId(), agent);
        // 【学习提示】此默认运行器用 taskId 缓存独立实例以供停止；当前调用未将 options
        // 中的 userId/sessionId 构造成 RuntimeContext，不能仅凭请求选项字段推断已恢复跨请求会话。
        return agent.streamEvents(requestMessages)
                .doFinally(signal -> agentCache.remove(options.getTaskId()));
    }

    @Override
    public void stop(String taskId) {
        ReActAgent agent = agentCache.remove(taskId);
        if (null != agent) {
            agent.interrupt();
        }
    }

    /**
     * 构建 {@link ReActAgent} 运行新请求。
     *
     * @return {@link ReActAgent} 实例
     */
    protected abstract ReActAgent buildReActAgent();
}
