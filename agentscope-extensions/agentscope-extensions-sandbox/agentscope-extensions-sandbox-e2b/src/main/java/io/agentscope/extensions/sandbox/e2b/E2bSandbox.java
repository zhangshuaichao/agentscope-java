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
package io.agentscope.extensions.sandbox.e2b;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由 E2B 云沙箱支持的 {@link io.agentscope.harness.agent.sandbox.Sandbox}。
 *
 * <p>通过 HTTPS 上的 envd {@code process.Process/Start}（Connect+protobuf）执行。{@link E2bPersistenceMode#TAR} 模式将工作区 tar 写入临时文件，经 envd Filesystem REST API 传输；{@link E2bPersistenceMode#NATIVE_SNAPSHOT} 使用平台快照 API，并在 Harness 快照流中保存 {@link E2bSnapshotRefs} 标记字节。
 *
 * <p>【学习提示】区分三种寿命：运行中沙箱、原生快照模板、独立持久卷。原生快照保留软件环境但不复制挂载卷字节；TAR 只处理工作区并排除嵌套卷。关闭时先终止沙箱再清理本会话记录的快照，避免删除仍被运行实例锁定的模板；归档失败遗留的无引用云快照仍需独立运维核查。
 */
public class E2bSandbox extends AbstractBaseSandbox implements SandboxFileTransfer {

    private static final Logger log = LoggerFactory.getLogger(E2bSandbox.class);

    private static final int TAR_TIMEOUT_SECONDS = 300;

    private final E2bSandboxState e2bState;
    private final E2bSandboxClientOptions opt;
    private final E2bPlatformHttp platform;
    private volatile E2bEnvdProcessClient envd;

    /**
     * 本实例当前进行中的工作区持久化创建的快照 ID；无进行中操作时为 {@code null}。{@link #doPersistWorkspace()} 在 ID 加入 {@link E2bSandboxState#getSnapshotIds()} 前设置它，使 {@link #stop()} 在归档持久化失败时可精确移除此调用创建的 ID。
     */
    private String lastCreatedSnapshotId;

    /**
     * {@link #ensureSandbox()} 为本次 {@link #start()} 作出的恢复决策。每次调用开始时重置，且有意仅保存在实例内：它描述当前活跃沙箱由哪个来源构建，而非会话的持久化属性。
     */
    private NativeSnapshotPlan nativeSnapshotPlan = NativeSnapshotPlan.DEFAULT;

    public E2bSandbox(E2bSandboxState state, E2bSandboxClientOptions opt) {
        super(state);
        this.e2bState = state;
        this.opt = opt != null ? opt : new E2bSandboxClientOptions();
        this.platform = new E2bPlatformHttp(this.opt);
    }

    @Override
    public void start() throws Exception {
        if (WorkspaceMountSupport.hasBindMounts(e2bState.getWorkspaceSpec())) {
            log.warn(
                    "[sandbox-e2b] WorkspaceSpec contains bind_mount entries; "
                            + "E2B does not apply host bind mounts — paths are not mounted.");
        }
        nativeSnapshotPlan = NativeSnapshotPlan.DEFAULT;
        ensureSandbox();
        super.start();
    }

    /**
     * {@inheritDoc}
     *
     * <p>工作区持久化失败时，不得将其快照 ID 留在 {@link E2bSandboxState#getSnapshotIds()} 中：已保存归档仍引用前一个快照，若记录还含较新 ID，{@link #cleanupSnapshots()} 可能删掉归档恢复所需快照。因此按身份而非位置移除 ID；记录为共享状态，其他会话期间追加的 ID 不属于本次调用可移除的范围。异常原样重新抛出：{@code SandboxManager.release} 会吞掉它，随后调用方保存状态，因此修正后的记录仍能到达存储。
     */
    @Override
    public void stop() throws Exception {
        // 仅回滚当前进行中的持久化：先前持久化记录的 ID
        // （例如直接调用 persistWorkspace()）不能因本次后续失败被移除。
        lastCreatedSnapshotId = null;
        try {
            super.stop();
        } catch (Exception e) {
            String created = lastCreatedSnapshotId;
            if (created != null) {
                e2bState.getSnapshotIds().remove(created);
                log.debug(
                        "[sandbox-e2b] rolled back snapshot id {} after failed workspace persist",
                        created);
            }
            throw e;
        }
    }

    @Override
    public void shutdown() throws Exception {
        if (!e2bState.isSandboxOwned()) {
            return;
        }
        String id = e2bState.getSandboxId();
        if (id != null && !id.isBlank()) {
            platform.killSandbox(id);
            // 成功终止后使 ID 失效，避免恢复时重连已死沙箱。
            e2bState.setSandboxId(null);
        }
        // 只有 killSandbox 后 E2B 才解锁快照模板（由其恢复的沙箱仍运行时删除返回 400），
        // 因此在此清理，使快照数量收敛到保留策略。
        cleanupSnapshots();
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        return envd().runShell(e2bState, getWorkspaceRoot(), command, timeoutSeconds);
    }

    @Override
    protected boolean probeWorkspaceRootForPreservedResume() {
        if (e2bState.isWorkspaceOnVolume()) {
            // 文件位于持久卷中，目录跨沙箱重建保留，
            // 因此不探测就视为已保存。
            return true;
        }
        return super.probeWorkspaceRootForPreservedResume();
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        if (e2bState.getPersistenceMode() == E2bPersistenceMode.NATIVE_SNAPSHOT) {
            // 原生快照保留软件环境（安装的依赖、系统状态）；
            // 卷字节由卷自身持久保存，恢复时重新挂载。
            // 即使工作区位于卷上，也保留快照调用。
            // 已通过真实环境验证
            // （E2bVolumeMountLiveTest#nestedMountContentSurvivesNativeSnapshotRestoreUnchanged）：
            // 平台快照不捕获已挂载卷的字节，因此位于非卷工作区根目录下的
            // 嵌套挂载不会受快照恢复影响。
            JsonNode snap = platform.createSandboxSnapshot(e2bState.getSandboxId(), snapshotName());
            String id = snap.path("snapshotID").asText("");
            if (id.isBlank()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                        "E2B snapshot response missing snapshotID: " + snap);
            }
            lastCreatedSnapshotId = id;
            e2bState.getSnapshotIds().add(id);
            return new ByteArrayInputStream(E2bSnapshotRefs.encodeSnapshotId(id));
        }
        if (e2bState.isWorkspaceOnVolume()) {
            // 工作区字节由卷持久保存，无需归档。空流同时作为
            // doHydrateWorkspace 读取的无操作标记；会话在卷支持与普通持久化之间
            // 切换时，用此空归档替换真实归档，表示
            // “不恢复任何内容，卷已保存文件”。
            return InputStream.nullInputStream();
        }
        String root = e2bState.getWorkspaceRoot();
        String tarPath = newTempTarPath();
        String script = buildTarCommand(true, tarPath, root);
        try {
            envd().runShell(e2bState, root, script, TAR_TIMEOUT_SECONDS);
            byte[] tar = envd().downloadFile(e2bState, tarPath);
            return new ByteArrayInputStream(tar);
        } finally {
            removeTempFileBestEffort(tarPath);
        }
    }

    @Override
    protected Optional<InputStream> openSnapshotArchive(SandboxSnapshot snapshot) throws Exception {
        NativeSnapshotPlan plan = nativeSnapshotPlan;
        if (plan.isAbsent()) {
            return Optional.empty();
        }
        if (plan.isRestored()) {
            return Optional.of(new ByteArrayInputStream(plan.archive()));
        }
        return super.openSnapshotArchive(snapshot);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        byte[] all = archive.readAllBytes();
        if (all.length == 0) {
            // 卷支持的工作区不持久化需要恢复的内容（参见 doPersistWorkspace）；
            // 空归档是有意的无操作标记，而非错误，不应把先前
            // 非卷归档的内容覆盖写回。
            return;
        }
        String nativeId = E2bSnapshotRefs.decodeSnapshotIdIfPresent(all);
        if (nativeId != null && !nativeId.isBlank()) {
            restoreSandboxFromSnapshotTemplate(nativeId, all);
            return;
        }
        String tarPath = newTempTarPath();
        String root = e2bState.getWorkspaceRoot();
        String script = buildTarCommand(false, tarPath, root);
        try {
            envd().uploadFile(e2bState, tarPath, all);
            envd().runShell(e2bState, root, script, TAR_TIMEOUT_SECONDS);
        } finally {
            removeTempFileBestEffort(tarPath);
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        // mkdir 自身的工作目录必须已存在：新挂载卷上的工作区根目录
        // （例如 /mnt/data/ws）通常尚不存在，因此从 / 执行。
        // mkdir 目标是绝对路径，不受工作目录影响。
        envd().runShell(
                        e2bState,
                        "/",
                        "mkdir -p " + shellSingleQuote(e2bState.getWorkspaceRoot()),
                        30);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        // 契约规定不执行操作。E2B 工作区寿命由沙箱本身限定，因此
        // 销毁委托给沙箱拆除；提前执行 "rm -rf" 会
        // 沿嵌套卷挂载删除共享持久数据，
        // 却没有额外可观察收益（沙箱销毁时磁盘本就消失）。
        log.debug("[sandbox-e2b] workspace destruction is a no-op; sandbox teardown releases it");
    }

    @Override
    protected String getWorkspaceRoot() {
        return e2bState.getWorkspaceRoot();
    }

    @Override
    public boolean supportsFileTransfer(String absolutePath) {
        return workspaceTransferPath(absolutePath) != null;
    }

    /**
     * 通过 E2B Filesystem REST API 上传文件到沙箱。
     *
     * @param absolutePath 沙箱内绝对目标路径
     * @param content 文件内容
     */
    @Override
    public void uploadFile(String absolutePath, byte[] content) throws Exception {
        envd().uploadFile(e2bState, requireWorkspaceTransferPath(absolutePath), content);
    }

    /**
     * 通过 E2B Filesystem REST API 从沙箱下载文件。
     *
     * @param absolutePath 沙箱内绝对路径
     * @return 文件内容
     */
    @Override
    public byte[] downloadFile(String absolutePath) throws Exception {
        return envd().downloadFile(e2bState, requireWorkspaceTransferPath(absolutePath));
    }

    private void ensureSandbox() throws Exception {
        JsonNode sandbox;
        if (e2bState.getSandboxId() == null || e2bState.getSandboxId().isBlank()) {
            sandbox = createSandboxForRestore();
        } else {
            try {
                sandbox =
                        platform.connectSandbox(
                                e2bState.getSandboxId(), opt.getSandboxTimeoutSeconds());
                nativeSnapshotPlan = NativeSnapshotPlan.DEFAULT;
            } catch (Exception e) {
                log.warn("[sandbox-e2b] connect failed, recreating sandbox: {}", e.getMessage());
                sandbox = createSandboxForRestore();
                e2bState.setWorkspaceRootReady(false);
                e2bState.setWorkspaceProjectionHash(null);
            }
        }
        platform.applySandboxFields(e2bState, sandbox);
        applyDefaultDomain();
    }

    /**
     * 创建沙箱时解析配置的环境变量提供函数。每次创建都重新求值，使短期有效值保持新鲜；跳过 {@code null} 或空值。
     */
    Map<String, String> resolveEnvVars() {
        Map<String, Supplier<String>> configured = opt.getEnvs();
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        configured.forEach(
                (name, supplier) -> {
                    String value = supplier != null ? supplier.get() : null;
                    if (value != null && !value.isEmpty()) {
                        resolved.put(name, value);
                    }
                });
        return resolved;
    }

    /**
     * 从代价最低的可用来源创建沙箱：有可恢复原生快照时使用快照，否则使用配置的原始模板。
     *
     * <p>由模板创建的沙箱不可能已经包含本会话工作区，因此所有模板创建路径都会清除工作区就绪标记；只有原生快照创建的沙箱保留标记，使 {@code start()} 仅需应用临时条目。
     */
    private JsonNode createSandboxForRestore() throws IOException {
        NativeSnapshotPeek peek = peekNativeSnapshot();
        if (peek.missing()) {
            nativeSnapshotPlan = NativeSnapshotPlan.ABSENT;
            log.warn(
                    "[sandbox-e2b] snapshot record {} is not restorable; creating an empty"
                            + " workspace from the template",
                    e2bState.getSnapshot() != null ? e2bState.getSnapshot().getId() : "unknown");
            JsonNode created =
                    platform.createSandbox(
                            e2bState.getTemplateId(),
                            opt.getSandboxTimeoutSeconds(),
                            e2bState.getVolumeMounts(),
                            resolveEnvVars());
            markWorkspaceUnready();
            return created;
        }
        String snapshotId = peek.snapshotId();
        if (snapshotId != null) {
            try {
                return createFromSnapshot(snapshotId, peek.archive());
            } catch (E2bPlatformHttp.PlatformHttpException e) {
                if (e.statusCode() != 404) {
                    throw snapshotRestoreFailure(
                            "Failed to create sandbox from native snapshot " + snapshotId, e);
                }
                log.warn(
                        "[sandbox-e2b] native snapshot {} no longer exists (HTTP 404); creating an"
                                + " empty workspace from the template",
                        snapshotId);
                nativeSnapshotPlan = NativeSnapshotPlan.ABSENT;
                JsonNode created =
                        platform.createSandbox(
                                e2bState.getTemplateId(),
                                opt.getSandboxTimeoutSeconds(),
                                e2bState.getVolumeMounts(),
                                resolveEnvVars());
                markWorkspaceUnready();
                return created;
            } catch (SandboxException e) {
                throw e;
            } catch (Exception e) {
                throw snapshotRestoreFailure(
                        "Failed to create sandbox from native snapshot " + snapshotId, e);
            }
        }
        nativeSnapshotPlan = NativeSnapshotPlan.DEFAULT;
        JsonNode created =
                platform.createSandbox(
                        e2bState.getTemplateId(),
                        opt.getSandboxTimeoutSeconds(),
                        e2bState.getVolumeMounts(),
                        resolveEnvVars());
        markWorkspaceUnready();
        return created;
    }

    private SandboxException.SandboxRuntimeException snapshotRestoreFailure(
            String message, Exception cause) {
        return new SandboxException.SandboxRuntimeException(
                SandboxErrorCode.SNAPSHOT_RESTORE_ERROR,
                message + ": " + cause.getMessage(),
                cause);
    }

    private void markWorkspaceUnready() {
        e2bState.setWorkspaceRootReady(false);
        e2bState.setWorkspaceProjectionHash(null);
    }

    private JsonNode createFromSnapshot(String snapshotId, byte[] archive) throws IOException {
        JsonNode created =
                platform.createSandbox(
                        snapshotId,
                        opt.getSandboxTimeoutSeconds(),
                        e2bState.getVolumeMounts(),
                        resolveEnvVars());
        nativeSnapshotPlan = NativeSnapshotPlan.restored(snapshotId, archive);
        return created;
    }

    private NativeSnapshotPeek peekNativeSnapshot() {
        if (e2bState.getPersistenceMode() != E2bPersistenceMode.NATIVE_SNAPSHOT) {
            return NativeSnapshotPeek.NOT_NATIVE;
        }
        SandboxSnapshot snapshot = e2bState.getSnapshot();
        if (snapshot == null) {
            return NativeSnapshotPeek.NOT_NATIVE;
        }
        try {
            if (!snapshot.isRestorable()) {
                return NativeSnapshotPeek.MISSING;
            }
            try (InputStream in = snapshot.restore()) {
                E2bSnapshotRefs.SnapshotIdRef ref = E2bSnapshotRefs.peekSnapshotId(in);
                return new NativeSnapshotPeek(ref.snapshotId(), ref.archive(), false);
            }
        } catch (Exception e) {
            throw snapshotRestoreFailure("Failed to read native snapshot " + snapshot.getId(), e);
        }
    }

    private void applyDefaultDomain() {
        if (e2bState.getSandboxDomain() == null || e2bState.getSandboxDomain().isBlank()) {
            e2bState.setSandboxDomain(opt.getDomain());
        }
    }

    private void restoreSandboxFromSnapshotTemplate(String snapshotTemplateId, byte[] archive)
            throws Exception {
        if (nativeSnapshotPlan.isRestored()
                && snapshotTemplateId.equals(nativeSnapshotPlan.snapshotId())) {
            log.debug(
                    "[sandbox-e2b] sandbox already restored from snapshot {}, skipping rebuild",
                    snapshotTemplateId);
            return;
        }
        String oldId = e2bState.getSandboxId();
        JsonNode created = createFromSnapshot(snapshotTemplateId, archive);
        platform.applySandboxFields(e2bState, created);
        applyDefaultDomain();
        if (e2bState.isSandboxOwned()
                && oldId != null
                && !oldId.isBlank()
                && !oldId.equals(e2bState.getSandboxId())) {
            try {
                platform.killSandbox(oldId);
            } catch (Exception e) {
                log.debug(
                        "[sandbox-e2b] failed to delete old sandbox {}: {}", oldId, e.getMessage());
            }
        }
    }

    private void cleanupSnapshots() {
        int retention = opt.getSnapshotRetention();
        if (retention <= 0) {
            return;
        }
        // 无论先前残留如何，一次性修正：按插入顺序保留最后 retention 个 ID，
        // 最新在末尾，其余删除。E2B 仅在 killSandbox 后解锁
        // 模板，因此在关闭时执行。
        try {
            List<String> kept = platform.cleanupSnapshots(e2bState.getSnapshotIds(), retention);
            e2bState.setSnapshotIds(kept);
        } catch (Exception e) {
            log.warn("[sandbox-e2b] snapshot cleanup best-effort skipped: {}", e.getMessage());
        }
    }

    private enum NativeSnapshotPlanStatus {
        DEFAULT,
        RESTORED,
        ABSENT
    }

    private record NativeSnapshotPlan(
            NativeSnapshotPlanStatus status, String snapshotId, byte[] archive) {

        private static final NativeSnapshotPlan DEFAULT =
                new NativeSnapshotPlan(NativeSnapshotPlanStatus.DEFAULT, null, null);

        private static final NativeSnapshotPlan ABSENT =
                new NativeSnapshotPlan(NativeSnapshotPlanStatus.ABSENT, null, null);

        private static NativeSnapshotPlan restored(String snapshotId, byte[] archive) {
            return new NativeSnapshotPlan(NativeSnapshotPlanStatus.RESTORED, snapshotId, archive);
        }

        private boolean isRestored() {
            return status == NativeSnapshotPlanStatus.RESTORED;
        }

        private boolean isAbsent() {
            return status == NativeSnapshotPlanStatus.ABSENT;
        }
    }

    private record NativeSnapshotPeek(String snapshotId, byte[] archive, boolean missing) {

        private static final NativeSnapshotPeek NOT_NATIVE =
                new NativeSnapshotPeek(null, null, false);

        private static final NativeSnapshotPeek MISSING = new NativeSnapshotPeek(null, null, true);
    }

    /**
     * AgentScope 原生快照别名：{@code agentscope-<shortId>-<epochMillis>}，中间为本地生成的 8 位十六进制短 UUID，末尾为创建时间的纪元毫秒（便于阅读，保留策略不解析它）。保留策略按插入顺序保留 {@link E2bSandboxState#getSnapshotIds()} 最后 {@code snapshotRetention} 个 ID（最新在末尾），通过 {@link E2bPlatformHttp#cleanupSnapshots} 删除其余项，与 ID 格式无关。
     */
    private static String snapshotName() {
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "agentscope-" + shortId + "-" + System.currentTimeMillis();
    }

    /**
     * 为严格嵌套于工作区根目录下的卷挂载生成 {@code tar --exclude} 参数。
     * 覆盖根目录的挂载无需排除，此时持久化本身不执行操作。
     */
    private List<String> volumeTarExcludeArgs() {
        List<E2bVolumeMount> mounts = e2bState.getVolumeMounts();
        if (mounts.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>(mounts.size());
        for (E2bVolumeMount m : mounts) {
            paths.add(m.getPath());
        }
        return WorkspaceMountSupport.tarExcludeArgsForAbsolutePaths(
                e2bState.getWorkspaceRoot(), paths);
    }

    private E2bEnvdProcessClient envd() throws Exception {
        E2bEnvdProcessClient c = envd;
        if (c == null) {
            synchronized (this) {
                if (envd == null) {
                    envd = new E2bEnvdProcessClient(opt);
                }
                c = envd;
            }
        }
        return c;
    }

    private void removeTempFileBestEffort(String tarPath) {
        try {
            envd().runShell(e2bState, e2bState.getWorkspaceRoot(), "rm -f " + tarPath, 30);
        } catch (Exception e) {
            log.debug("[sandbox-e2b] remove temp tar best-effort: {}", e.getMessage());
        }
    }

    private static String newTempTarPath() {
        return "/tmp/agentscope-ws-" + UUID.randomUUID() + ".tar";
    }

    private String buildTarCommand(boolean create, String tarPath, String root) {
        StringBuilder script = new StringBuilder("tar ");
        for (String ex :
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(e2bState.getWorkspaceSpec())) {
            // 加引号：排除参数来自用户配置路径，可能含空格。
            script.append(shellSingleQuote(ex)).append(' ');
        }
        // 两个分支均应用卷挂载排除。创建分支不能重复
        // 将卷字节归档；解压分支不能让早于当前挂载配置的
        // 归档条目（例如卷挂到工作区根目录前写入的 NATIVE_SNAPSHOT
        // 归档）穿过挂载点写入
        // 共享卷。
        for (String ex : volumeTarExcludeArgs()) {
            script.append(shellSingleQuote(ex)).append(' ');
        }
        script.append(create ? "-cf " : "-xf ")
                .append(tarPath)
                .append(" -C ")
                .append(shellSingleQuote(root));
        if (create) {
            script.append(" .");
        }
        return script.toString();
    }

    private String requireWorkspaceTransferPath(String path) {
        String transferPath = workspaceTransferPath(path);
        if (transferPath == null) {
            throw new IllegalArgumentException(
                    "Path is outside the sandbox workspace or invalid: " + path);
        }
        return transferPath;
    }

    private String workspaceTransferPath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return null;
        }
        String root = e2bState.getWorkspaceRoot();
        if (root == null || root.isBlank() || !root.startsWith("/")) {
            return null;
        }
        String normalizedRoot = WorkspaceMountSupport.normalizeVirtualPath(root);
        String rootPrefix = "/".equals(normalizedRoot) ? "/" : normalizedRoot + "/";
        if (!path.startsWith(rootPrefix)) {
            return null;
        }
        String relative = path.substring(rootPrefix.length());
        if (relative.isEmpty()) {
            return null;
        }
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return null;
            }
        }
        return rootPrefix + relative;
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
