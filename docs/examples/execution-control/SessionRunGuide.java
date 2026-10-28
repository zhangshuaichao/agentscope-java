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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import java.util.List;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 会话指南的配套代码，智能体配置和用户认证由应用负责。 */
public final class SessionRunGuide {
    private SessionRunGuide() {}

    public static AgentSession session(HarnessAgent agent, String ownerId, String sessionId) {
        return agent.session(RuntimeContext.builder().userId(ownerId).sessionId(sessionId).build());
    }

    /** HTTP 接受请求：执行由框架持有，与响应订阅者的生命周期独立。 */
    public static Mono<AgentSession.Task> submit(AgentSession session, String requestKey, String input) {
        return Mono.fromCallable(() -> session.submit(requestKey, input))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 引导已知的活动任务，拒绝过期或空闲状态的操作。 */
    public static Mono<AgentSession.InputReceipt> steer(AgentSession session, AgentSession.Task task, String input) {
        return Mono.fromCallable(() -> session.steer(task, input))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 观察持久历史，取消此读取不会取消推理执行。 */
    public static Mono<List<SessionEvent>> events(AgentSession session, long after) {
        return Mono.fromCallable(() -> session.log().readAfter(after, 100))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 按运行 ID 防止迟到的停止请求误伤已经启动的另一次执行。 */
    public static Mono<Boolean> interrupt(AgentSession session, String runId) {
        return Mono.fromCallable(() -> session.interrupt(runId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 仅在上次执行已停止、待处理动作已获回答之后调用。 */
    public static Mono<AgentSession.Task> resume(AgentSession session, String turnId) {
        return Mono.fromCallable(() -> session.resume(turnId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
