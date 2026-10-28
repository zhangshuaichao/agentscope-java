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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实现工作区四分支启动逻辑的 {@link Sandbox} 抽象基类。
 *
 * <h2>四分支启动逻辑</h2>
 * <pre>
 * Branch A: workspaceRootReady=true  &amp; workspace dir exists   → apply ephemeral-only entries
 * Branch B: workspaceRootReady=true  &amp; workspace dir missing  → restore from snapshot + ephemeral entries
 * Branch C: workspaceRootReady=false &amp; snapshot is restorable → hydrate from snapshot + all entries
 * Branch D: workspaceRootReady=false &amp; no restorable snapshot → fresh init from full workspace spec
 * </pre>
 *
 * <p>分支图中文说明：A，工作区已就绪且目录存在，只应用临时条目；B，已就绪但目录丢失，从快照恢复并应用临时条目；C，尚未就绪且快照可恢复，用快照填充后应用全部条目；D，尚未就绪且无可用快照，按完整工作区配置从头初始化。
 *
 * <p>子类实现后端专用操作：
 * <ul>
 *   <li>{@link #doExec(RuntimeContext, String, int)}：在工作区执行 Shell 命令</li>
 *   <li>{@link #doPersistWorkspace()}：创建工作区 tar 归档</li>
 *   <li>{@link #doHydrateWorkspace(InputStream)}：将 tar 归档解压到工作区</li>
 *   <li>{@link #doSetupWorkspace()}：创建工作区根目录</li>
 *   <li>{@link #doDestroyWorkspace()}：关闭时删除工作区根目录</li>
 *   <li>{@link #getWorkspaceRoot()}：返回工作区根路径字符串</li>
 * </ul>
 */
public abstract class AbstractBaseSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(AbstractBaseSandbox.class);

    /**
 * 工作区探测命令的默认超时秒数。
 */
    private static final int PROBE_TIMEOUT_SECONDS = 10;

    private final SandboxState state;
    private final WorkspaceSpecApplier workspaceSpecApplier;
    private final AtomicBoolean running = new AtomicBoolean(false);

    protected AbstractBaseSandbox(SandboxState state) {
        this.state = state;
        this.workspaceSpecApplier = new WorkspaceSpecApplier(state.getWorkspaceSpec().getRoot());
    }

    /**
     * 执行工作区四分支启动逻辑。
     *
     * @throws Exception 工作区启动失败时抛出
     */
    @Override
    public void start() throws Exception {
        WorkspaceSpec spec = state.getWorkspaceSpec();
        SandboxSnapshot snapshot = state.getSnapshot();

        try {
            if (state.isWorkspaceRootReady()) {
                // 工作区上次停止时已就绪：检查是否仍然存在
                boolean stillExists = probeWorkspaceRootForPreservedResume();
                if (stillExists) {
                    // 分支 A：工作区保留，只应用临时条目
                    log.debug(
                            "[sandbox] Branch A: workspace preserved, applying ephemeral entries");
                    workspaceSpecApplier.applyWorkspaceSpec(spec, true);
                } else {
                    // 分支 B：工作区丢失，从快照恢复并应用临时条目
                    log.debug("[sandbox] Branch B: workspace lost, restoring from snapshot");
                    Optional<InputStream> archive = openSnapshotArchive(snapshot);
                    if (archive.isPresent()) {
                        doSetupWorkspace();
                        try (InputStream stream = archive.get()) {
                            doHydrateWorkspace(stream);
                        }
                        workspaceSpecApplier.applyWorkspaceSpec(spec, true);
                    } else {
                        // 降级到分支 D：没有可用快照
                        log.warn("[sandbox] Branch B degraded to D: snapshot not restorable");
                        doSetupWorkspace();
                        workspaceSpecApplier.applyWorkspaceSpec(spec, false);
                    }
                }
            } else {
                // 工作区上次停止时未就绪
                Optional<InputStream> archive = openSnapshotArchive(snapshot);
                if (archive.isPresent()) {
                    // 分支 C：从快照恢复并应用全部规范条目
                    log.debug("[sandbox] Branch C: restoring from snapshot");
                    doSetupWorkspace();
                    try (InputStream stream = archive.get()) {
                        doHydrateWorkspace(stream);
                    }
                    workspaceSpecApplier.applyWorkspaceSpec(spec, false);
                } else {
                    // 分支 D：根据完整工作区规范重新初始化
                    log.debug("[sandbox] Branch D: fresh workspace initialization");
                    doSetupWorkspace();
                    workspaceSpecApplier.applyWorkspaceSpec(spec, false);
                }
            }
            applyWorkspaceProjectionIfChanged(spec);
            state.setWorkspaceRootReady(true);
            running.set(true);
        } catch (Exception e) {
            state.setWorkspaceRootReady(false);
            throw new SandboxException.WorkspaceStartException(
                    java.nio.file.Path.of(state.getWorkspaceSpec().getRoot()), e);
        }
    }

    /**
     * 持久保存工作区快照，并将工作区根目录标记为就绪。
     *
     * @throws Exception 快照持久化失败时抛出
     */
    @Override
    public void stop() throws Exception {
        SandboxSnapshot snapshot = state.getSnapshot();
        if (snapshot != null && snapshot.isPersistenceEnabled()) {
            try (InputStream archive = doPersistWorkspace()) {
                snapshot.persist(archive);
            }
        }
        state.setWorkspaceRootReady(true);
        running.set(false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>先调用 {@link #stop()}，再调用 {@link #shutdown()}。停止失败会记录日志，但不会阻止关闭。
     */
    @Override
    public void close() throws Exception {
        try {
            stop();
        } catch (Exception e) {
            log.warn("[sandbox] Failed to stop sandbox during close, continuing shutdown", e);
        }
        shutdown();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public SandboxState getState() {
        return state;
    }

    /**
     * 使用回退超时委托给 {@link #doExec(RuntimeContext, String, int)}。
     */
    @Override
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
            throws Exception {
        int timeout = timeoutSeconds != null ? timeoutSeconds : getDefaultExecTimeoutSeconds();
        return doExec(runtimeContext, command, timeout);
    }

    @Override
    public InputStream persistWorkspace() throws Exception {
        return doPersistWorkspace();
    }

    /**
     * 打开本次启动使用的快照归档；无快照时返回空。后端若在 {@link #start()} 前已选择恢复来源，可以覆盖此方法复用决定，避免再次打开持久化快照。
     */
    protected Optional<InputStream> openSnapshotArchive(SandboxSnapshot snapshot) throws Exception {
        if (snapshot == null || !snapshot.isRestorable()) {
            return Optional.empty();
        }
        return Optional.of(snapshot.restore());
    }

    @Override
    public void hydrateWorkspace(InputStream archive) throws Exception {
        doHydrateWorkspace(archive);
    }

    /**
     * 通过后端命令探测工作区根目录是否仍然存在。
     *
     * <p>使用 {@code test -d {workspaceRoot}}，超时为 {@value #PROBE_TIMEOUT_SECONDS} 秒。命令退出码为 0 时返回 {@code true}。
     *
     * @return 工作区根目录存在时为 true
     */
    protected boolean probeWorkspaceRootForPreservedResume() {
        try {
            ExecResult result =
                    doExec(null, "test -d " + getWorkspaceRoot(), PROBE_TIMEOUT_SECONDS);
            return result.ok();
        } catch (Exception e) {
            log.warn(
                    "[sandbox] Probe for workspace root failed, assuming lost: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 返回默认命令执行超时秒数。
     *
     * @return 默认超时（120 秒）
     */
    protected int getDefaultExecTimeoutSeconds() {
        return 120;
    }

    /**
     * 在工作区内执行 Shell 命令。
     *
     * @param runtimeContext 每次调用的上下文；内部探测时可为 {@code null}
     * @param command Shell 命令字符串
     * @param timeoutSeconds 最大执行时间
     * @return 执行结果
     * @throws Exception 执行失败时抛出
     */
    protected abstract ExecResult doExec(
            RuntimeContext runtimeContext, String command, int timeoutSeconds) throws Exception;

    /**
     * 为当前工作区内容创建 tar 归档。
     *
     * @return tar 流的 {@link InputStream}，调用方必须关闭
     * @throws Exception 归档失败时抛出
     */
    protected abstract InputStream doPersistWorkspace() throws Exception;

    /**
     * 将 tar 归档解压到工作区。
     *
     * @param archive 要解压的 tar 归档流
     * @throws Exception 解压失败时抛出
     */
    protected abstract void doHydrateWorkspace(InputStream archive) throws Exception;

    /**
     * 创建工作区根目录。
     *
     * @throws Exception 目录创建失败时抛出
     */
    protected abstract void doSetupWorkspace() throws Exception;

    /**
     * 销毁工作区根目录及后端资源。
     *
     * @throws Exception 清理失败时抛出
     */
    protected abstract void doDestroyWorkspace() throws Exception;

    /**
     * 返回工作区根目录的绝对路径。
     *
     * @return 工作区根路径字符串
     */
    protected abstract String getWorkspaceRoot();

    private void applyWorkspaceProjectionIfChanged(WorkspaceSpec spec) throws Exception {
        WorkspaceProjectionApplier.ProjectionPayload payload =
                WorkspaceProjectionApplier.build(spec);
        if (payload == null) {
            return;
        }
        if (Objects.equals(payload.hash(), state.getWorkspaceProjectionHash())) {
            log.debug("[sandbox] Workspace projection unchanged, skipping");
            return;
        }
        if (payload.fileCount() > 0) {
            try (InputStream archive = new ByteArrayInputStream(payload.tarBytes())) {
                doHydrateWorkspace(archive);
            }
        }
        state.setWorkspaceProjectionHash(payload.hash());
        log.debug(
                "[sandbox] Workspace projection applied: files={}, hash={}",
                payload.fileCount(),
                payload.hash());
    }
}
