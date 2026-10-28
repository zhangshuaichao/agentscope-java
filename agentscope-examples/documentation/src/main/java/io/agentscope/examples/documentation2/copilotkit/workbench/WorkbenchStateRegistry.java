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
package io.agentscope.examples.documentation2.copilotkit.workbench;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 为每个 AG-UI 线程保留一个 {@link WorkbenchState}。适配器将 {@code RuntimeContext.sessionId} 设为线程 ID，工具与中间件可直接解析相应状态。
 */
@Component
public class WorkbenchStateRegistry {

    private static final String FALLBACK_THREAD_ID = "default-thread";

    private final Map<String, WorkbenchState> states = new ConcurrentHashMap<>();

    public WorkbenchState forThread(String threadId) {
        String key = (threadId == null || threadId.isBlank()) ? FALLBACK_THREAD_ID : threadId;
        return states.computeIfAbsent(key, WorkbenchState::new);
    }

    public WorkbenchState forContext(RuntimeContext ctx) {
        return forThread(ctx == null ? null : ctx.getSessionId());
    }

    /**
     * 解析工具调用的状态。{@code AgentState} 按会话 ID 索引，AG-UI 将其设为线程 ID，因此注入状态即可找到正确工作台。
     */
    public WorkbenchState forState(AgentState state) {
        return forThread(state == null ? null : state.getSessionId());
    }

    public void remove(String threadId) {
        if (threadId != null) {
            states.remove(threadId);
        }
    }
}
