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
package io.agentscope.harness.agent.subagent.task;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 描述后台子智能体任务执行方式的判别联合类型。
 *
 * <p>{@link LocalTaskRunSpec} 在本地执行器运行 supplier。
 * {@link RemoteTaskRunSpec} 委托给 AgentScope 任务 HTTP API
 * （参见 {@code agentscope-extensions-agent-protocol}）。
 * {@link AdoptedTaskRunSpec} 包装已经运行的 {@link CompletableFuture}
 * （用于同步执行超时后提升为异步的情况）。
 */
public sealed interface TaskRunSpec {

    /**
     * 通过 {@link Supplier} 在进程内执行。
     */
    record LocalTaskRunSpec(Supplier<String> execution) implements TaskRunSpec {}

    /**
     * 远程 HTTP 任务执行。{@code taskId} 由客户端选择，并端到端用作远程任务键。
     * {@code context} 携带提交时元数据（流式偏好、传播的拒绝规则、父级身份）；
     * 对于早于此字段出现的调用方，默认为 {@link RemoteSubmitContext#empty()}。
     */
    record RemoteTaskRunSpec(
            String baseUrl,
            Map<String, String> headers,
            String agentId,
            String input,
            RemoteSubmitContext context)
            implements TaskRunSpec {

        public RemoteTaskRunSpec(
                String baseUrl, Map<String, String> headers, String agentId, String input) {
            this(baseUrl, headers, agentId, input, RemoteSubmitContext.empty());
        }
    }

    /**
     * 将已经运行的 {@link CompletableFuture} 接管为受跟踪的后台任务。
     * 用于同步执行超时后提升为异步：future 仍在进行，只需要连接状态跟踪回调，
     * 无需再次提交给执行器。
     */
    record AdoptedTaskRunSpec(CompletableFuture<String> future) implements TaskRunSpec {}
}
