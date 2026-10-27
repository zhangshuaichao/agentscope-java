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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.PinnedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 围绕每次智能体调用管理沙箱会话生命周期的中间件。
 *
 * <h2>{@code next.apply} 之前</h2>
 * <ol>
 *   <li>读取 {@link SandboxContext}，来源为当前 {@link RuntimeContext}</li>
 *   <li>通过 {@link SandboxManager} 获取会话</li>
 *   <li>启动会话（工作区初始化分为四种情况）</li>
 *   <li>将活动会话绑定到每次调用的 {@link RuntimeContext}，
 *       供 {@link SandboxBackedFilesystem} 代理解析</li>
 * </ol>
 *
 * <h2>doFinally</h2>
 * <ol>
 *   <li>通过 {@link SandboxManager} 释放会话（停止，以及可选的关闭）</li>
 *   <li>通过 {@link SandboxManager} 与
 *       {@link io.agentscope.harness.agent.sandbox.SessionSandboxStateStore} 持久化沙箱会话状态</li>
 *   <li>使本次调用在各个 {@link RuntimeContext} 副本中的会话绑定失效</li>
 * </ol>
 *
 * <p>调用后持久化、释放等失败只记录日志而不传播，
 * 确保即使沙箱清理失败，调用方仍能收到智能体结果。
 *
 * <p>沙箱按<em>调用</em>绑定在该次 {@link RuntimeContext} 上，而非共享智能体级槽位。
 * 不同 {@code (userId, sessionId)} 会话可在同一个智能体 bean 上并行运行；
 * 共享槽位会让并发调用破坏彼此的绑定（issue #2490）。
 *
 * <p>【学习提示】沙箱资源的拥有者是当前调用。释放时必须取回同一次调用建立的绑定，
 * 不能简单清空某个共享字段，否则可能关闭另一个仍在执行的会话。
 * 当前实现通过 acquireForCall/releaseForCall 显式接入生命周期；activePoints() 为空，
 * 不应误以为它自动靠普通 onAgent 拦截点完成这些操作。
 */
public class SandboxLifecycleMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleMiddleware.class);

    private final SandboxManager sandboxManager;
    private final SandboxBackedFilesystem filesystemProxy;
    private volatile Consumer<RuntimeContext> beforeStartCallback;

    public SandboxLifecycleMiddleware(
            SandboxManager sandboxManager, SandboxBackedFilesystem filesystemProxy) {
        this.sandboxManager = sandboxManager;
        this.filesystemProxy = filesystemProxy;
    }

    /**
     * 会话生命周期由 HarnessAgent 显式驱动，不参与任何扩展点。
     * 子类若重写钩子，必须重新声明参与点。
     */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.noneOf(ExtensionPoint.class);
    }

    /**
     * 注册在沙箱会话已获取、但 {@link io.agentscope.harness.agent.sandbox.Sandbox#start()}
     * 应用工作区投影之前执行的回调。调用方可以先在宿主机工作区物化资源
     * （例如 {@code .skills-cache/}），让本次调用的投影一并包含它们。
     *
     * @param callback 接收每次调用的 {@link RuntimeContext}；可为 {@code null} 以清除
     */
    public void setBeforeStartCallback(Consumer<RuntimeContext> callback) {
        this.beforeStartCallback = callback;
    }

    /**
     * 为当前调用获取沙箱。由 {@code ReActAgent.beforeAgentExecution()} 调用，
     * 确保 {@code call()} 和 {@code streamEvents()} 两条路径都能使用沙箱。
     *
     * @param ctx 每次调用的 RuntimeContext（不可为 null）
     */
    public void acquireForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        if (sandboxContext == null) {
            return;
        }
        try {
            Consumer<RuntimeContext> cb = beforeStartCallback;
            if (cb != null) {
                try {
                    cb.accept(ctx);
                } catch (Exception e) {
                    log.warn(
                            "[sandbox-mw] beforeStartCallback failed; proceeding with sandbox"
                                    + " start: {}",
                            e.getMessage(),
                            e);
                }
            }
            SandboxAcquireResult result = sandboxManager.acquire(sandboxContext, ctx);
            Sandbox sandbox = result.getSandbox();
            PinnedSandboxFilesystem.markSandboxAcquired(sandbox);
            try {
                sandbox.start();
                // 将获取的沙箱按调用绑定到本次 RuntimeContext，
                // 而不是只放入共享智能体级槽位。不同 (userId, sessionId) 会话
                // 可在同一智能体 bean 上并行运行，共享槽位会让并发调用
                // 破坏彼此的绑定（issue #2490）。文件系统代理优先从
                // 当前上下文解析沙箱；下方字段仅作为无调用上下文的调用方
                // （例如 WorkspaceMessageBus）的尽力回退。
                ctx.put(SandboxAcquireResult.class, result);
                filesystemProxy.setSandbox(sandbox);
                log.debug(
                        "[sandbox-mw] Acquired sandbox {}",
                        sandbox.getState() != null ? sandbox.getState().getSessionId() : "?");
            } catch (Exception e) {
                ctx.put(SandboxAcquireResult.class, null);
                filesystemProxy.clearSandboxIfCurrent(sandbox);
                try {
                    markMirrorSandboxReleased(result);
                    sandboxManager.release(result);
                } catch (Exception releaseErr) {
                    log.warn(
                            "[sandbox-mw] Failed to release session after pre-call failure: {}",
                            releaseErr.getMessage(),
                            releaseErr);
                }
                result.getLease().close();
                throw e;
            }
        } catch (Exception e) {
            log.error("[sandbox-mw] Failed to acquire/start sandbox", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 在当前调用后释放沙箱。由 {@code ReActAgent.afterAgentExecution()} 调用，
     * 确保两条路径都执行清理。
     *
     * @param ctx 每次调用的 RuntimeContext（在获取时捕获）
     */
    public void releaseForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        // 读取本次调用在 acquireForCall 中建立的同一个绑定，使调用只会
        // 清理自己的沙箱，而不会清理其他并发会话的沙箱（issue #2490）。
        SandboxAcquireResult result = ctx.get(SandboxAcquireResult.class);
        if (result == null) {
            return;
        }
        // 保留已失效的绑定，防止此上下文回退到兄弟调用。
        if (!result.beginRelease()) {
            return;
        }
        // 比较后再清除回退字段，避免释放中的调用把并发兄弟调用的绑定
        // 置空（issue #2490）；仅当字段仍指向当前绑定时才清除。
        filesystemProxy.clearSandboxIfCurrent(result.getSandbox());
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        // 先执行释放（停止／持久化工作区），使停止期间产生的状态变化，
        // 例如 workspaceRootReady 或会话快照记录，可被下方持久化捕获。
        try {
            markMirrorSandboxReleased(result);
            sandboxManager.release(result);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to release sandbox session: {}", e.getMessage(), e);
        }
        try {
            sandboxManager.persistState(result, sandboxContext, ctx);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to persist sandbox state: {}", e.getMessage(), e);
        }
        result.getLease().close();
    }

    private static void markMirrorSandboxReleased(SandboxAcquireResult result) {
        if (result.isSelfManaged()) {
            PinnedSandboxFilesystem.markSandboxReleased(result.getSandbox());
        }
    }
}
