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
package io.agentscope.core.agui.processor;

import static io.agentscope.core.agui.AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 为请求处理协调 AG-UI 中断恢复约定的状态。
 *
 * <p>【学习提示】这两张表是处理器实例内的内存状态，键只有 threadId，不是跨进程分布式锁，也不是租户鉴权边界。共享处理器的调用方必须确保线程标识的隔离；重启、多副本路由及持久化恢复需由部署层另行设计，不能从 ConcurrentHashMap 推导出这些保证。
 *
 * <p>协调器有意限定在请求处理器层。适配器和转换器不保存未解决中断状态，而处理器可记住每个 AG-UI 线程最新的中断结果，并按照官方恢复约定验证下一次请求。
 */
final class AguiResumeCoordinator {

    static final String CONTRACT_ERROR_CODE = "AGUI_INTERRUPT_CONTRACT_ERROR";

    private final ConcurrentMap<String, Map<String, AguiEvent.Interrupt>>
            pendingInterruptsByThread = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeRunsByThread = new ConcurrentHashMap<>();

    /**
     * 验证输入是否满足当前支持的 AG-UI 恢复约定。
     *
     * @param input 要验证的运行输入
     * @return 描述能否继续处理的验证结果
     */
    ResumeContractResult validate(RunAgentInput input) {
        Map<String, AguiEvent.Interrupt> pending =
                pendingInterruptsByThread.get(input.getThreadId());
        if (pending == null || pending.isEmpty()) {
            if (!input.hasResume()) {
                return ResumeContractResult.proceed();
            }
            return ResumeContractResult.error(
                    "RunAgentInput.resume does not match any open interrupt");
        }

        if (!input.hasResume()) {
            return ResumeContractResult.error(
                    "Thread has unresolved interrupts; RunAgentInput.resume must address all of"
                            + " them");
        }

        ResumeContractResult statusResult = validateResumeStatuses(input.getResume());
        if (statusResult.isError()) {
            return statusResult;
        }

        Set<String> resumeIds = new LinkedHashSet<>();
        for (AguiResume resume : input.getResume()) {
            if (!resumeIds.add(resume.getInterruptId())) {
                return ResumeContractResult.error(
                        "RunAgentInput.resume contains duplicate interruptId: "
                                + resume.getInterruptId());
            }
        }

        Set<String> pendingIds = pending.keySet();
        if (!resumeIds.equals(pendingIds)) {
            Set<String> missingIds = new LinkedHashSet<>(pendingIds);
            missingIds.removeAll(resumeIds);
            Set<String> unknownIds = new LinkedHashSet<>(resumeIds);
            unknownIds.removeAll(pendingIds);
            return ResumeContractResult.error(
                    "RunAgentInput.resume must cover all open interrupts. missing="
                            + missingIds
                            + ", unknown="
                            + unknownIds);
        }

        return ResumeContractResult.proceed();
    }

    /**
     * 将运行标记为其线程的活动运行。
     *
     * <p>有意将 AG-UI 线程执行串行化，因为未解决中断状态和 AgentScope 会话状态均以 {@code threadId} 为作用域。允许同一线程多个活动运行会使最终中断状态取决于完成顺序。
     *
     * @param input 要启动的运行输入
     * @return 描述运行能否启动的验证结果
     */
    ResumeContractResult beginRun(RunAgentInput input) {
        ResumeContractResult resumeContract = validate(input);
        if (resumeContract.isError()) {
            return resumeContract;
        }
        String previousRunId =
                activeRunsByThread.putIfAbsent(input.getThreadId(), input.getRunId());
        if (previousRunId == null) {
            return ResumeContractResult.proceed();
        }
        return ResumeContractResult.error(
                "Thread already has an active run; wait for run "
                        + previousRunId
                        + " to finish before starting another run on the same thread");
    }

    /**
     * 若活动运行标记仍属于正在完成的运行，则清除它。
     *
     * @param threadId AG-UI 线程 ID
     * @param runId 正在完成的运行 ID
     */
    void finishRun(String threadId, String runId) {
        activeRunsByThread.remove(threadId, runId);
    }

    /**
     * 将已知原始中断加入运行上下文，以便转换恢复请求。
     *
     * @param input 含恢复条目的运行输入
     * @param runtimeContext 调用方运行上下文（若有）
     * @return 可用时包含 AG-UI 恢复中断的运行上下文
     */
    RuntimeContext addResumeInterrupts(RunAgentInput input, RuntimeContext runtimeContext) {
        if (!input.hasResume()) {
            return runtimeContext;
        }
        Map<String, AguiEvent.Interrupt> pending =
                pendingInterruptsByThread.get(input.getThreadId());
        if (pending == null || pending.isEmpty()) {
            return runtimeContext;
        }
        Map<String, AguiEvent.Interrupt> resumeInterrupts = new LinkedHashMap<>();
        for (AguiResume resume : input.getResume()) {
            AguiEvent.Interrupt interrupt = pending.get(resume.getInterruptId());
            if (interrupt == null) {
                continue;
            }
            if (shouldPassResumeInterrupt(interrupt)) {
                resumeInterrupts.put(resume.getInterruptId(), interrupt);
            }
        }
        if (resumeInterrupts.isEmpty()) {
            return runtimeContext;
        }
        return RuntimeContext.builder(runtimeContext)
                .put(
                        AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY,
                        Map.copyOf(resumeInterrupts))
                .build();
    }

    /**
     * 跟踪适配器发送的中断结果，供后续恢复请求使用。
     *
     * @param threadId AG-UI 线程 ID
     * @param runId 发送该事件的 AG-UI 运行 ID
     * @param event 待发出的 AG-UI 事件
     * @param runErrorSeen 此流是否已发送 {@code RUN_ERROR}
     */
    void trackPendingInterrupts(
            String threadId, String runId, AguiEvent event, boolean runErrorSeen) {
        if (!(event instanceof AguiEvent.RunFinished runFinished)) {
            return;
        }
        if (!runId.equals(activeRunsByThread.get(threadId))) {
            return;
        }
        if (runFinished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome interruptOutcome
                && !interruptOutcome.interrupts().isEmpty()) {
            Map<String, AguiEvent.Interrupt> interrupts = new ConcurrentHashMap<>();
            for (AguiEvent.Interrupt interrupt : interruptOutcome.interrupts()) {
                interrupts.put(interrupt.id(), interrupt);
            }
            pendingInterruptsByThread.put(threadId, Map.copyOf(interrupts));
        } else if (!runErrorSeen) {
            pendingInterruptsByThread.remove(threadId);
        }
    }

    /**
     * 构建违反恢复约定时使用的 AG-UI 错误生命周期。
     *
     * @param input 无效运行输入
     * @param message 验证错误消息
     * @param emitRunFinishedAfterError 为 true 时，为旧客户端在 {@code RUN_ERROR} 后追加 {@code RUN_FINISHED}
     * @return 协议错误生命周期事件
     */
    List<AguiEvent> contractErrorEvents(
            RunAgentInput input, String message, boolean emitRunFinishedAfterError) {
        List<AguiEvent> events = new ArrayList<>();
        events.add(new AguiEvent.RunStarted(input.getThreadId(), input.getRunId(), null, input));
        events.add(
                new AguiEvent.RunError(
                        input.getThreadId(),
                        input.getRunId(),
                        message,
                        CONTRACT_ERROR_CODE,
                        System.currentTimeMillis(),
                        null));
        if (emitRunFinishedAfterError) {
            events.add(new AguiEvent.RunFinished(input.getThreadId(), input.getRunId()));
        }
        return List.copyOf(events);
    }

    private ResumeContractResult validateResumeStatuses(List<AguiResume> resumes) {
        for (AguiResume resume : resumes) {
            if (!resume.isResolved() && !resume.isCancelled()) {
                return ResumeContractResult.error(
                        "RunAgentInput.resume contains unsupported status: " + resume.getStatus());
            }
        }
        return ResumeContractResult.proceed();
    }

    private boolean shouldPassResumeInterrupt(AguiEvent.Interrupt interrupt) {
        if (TOOL_CALL_INTERRUPT_REASON.equals(interrupt.reason())) {
            return interrupt.toolCallId() != null && !interrupt.toolCallId().isBlank();
        }
        return false;
    }

    record ResumeContractResult(boolean error, String message) {

        boolean isError() {
            return error;
        }

        static ResumeContractResult proceed() {
            return new ResumeContractResult(false, null);
        }

        static ResumeContractResult error(String message) {
            return new ResumeContractResult(true, message);
        }
    }
}
