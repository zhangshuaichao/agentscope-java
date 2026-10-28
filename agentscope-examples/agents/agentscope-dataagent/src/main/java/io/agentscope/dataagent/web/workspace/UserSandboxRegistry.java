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
package io.agentscope.dataagent.web.workspace;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 拥有 DataAgent Web 层使用的逐 {@code (userId, agentId)} 活跃 {@link Sandbox} 实例。
 * <p>浏览器工作区控制器和智能体运行时（通过 {@link
 * io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox()}）都经由
 * {@link #borrow(String, String)} 返回的沙箱读写。浏览器请求与智能体轮次复用同一用户容器，
 * 避免旧 {@link io.agentscope.harness.agent.filesystem.CompositeFilesystem} 其他路由回落到
 * 共享 {@code LocalFilesystem} 而泄漏跨租户内容。
 * <p>生命周期：每个键首次 {@link #borrow} 时延迟创建并启动沙箱，后续借用保持存活，
 * 空闲达到 {@link #idleTtl} 后关闭。Bean 销毁时调用 {@link
 * #shutdownAll()}。市场贡献审批调用 {@link #invalidate(String, String)}，淘汰共享层已改变的沙箱。
 * <p>线程：{@link ConcurrentHashMap#compute} 将同键 {@link #borrow} 串行化，
 * 因此每个容器只调用一次 {@link Sandbox#start()}。不同键可独立并发借用。
 * <p>多副本：注册表仅在内存中。必须按 {@code userId} 使用粘性负载均衡，
 * 否则各 Pod 会为同一用户创建自己的容器，前端看到不确定状态。
 * <p>【学习提示】borrow 返回可复用实例，不是独占租约。空闲淘汰与共享层失效都会关闭容器，
 * 不应把注册表缓存当作永久文件持久化保证。
 */
public final class UserSandboxRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserSandboxRegistry.class);

    private static final List<String> DEFAULT_PROJECTION_ROOTS =
            List.of("AGENTS.md", "skills", "subagents", "knowledge");

    private final SandboxClient<DockerSandboxClientOptions> client;
    private final Path hostWorkspaceRoot;
    private final Duration idleTtl;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor;

    /**
     * @param client 用于 {@link SandboxClient#create create} 新沙箱的后端客户端
     * @param hostWorkspaceRoot 逐智能体共享种子所在目录，组织为
     *     {@code <hostWorkspaceRoot>/agents/<agentId>/{AGENTS.md, skills/, subagents/, knowledge/}}。
     *     每个容器仅投影自身 {@code agentId} 分区，不看到其他智能体共享层；可为 {@code null}，
     *     表示完全跳过投影（容器从空状态开始）。
     * @param idleTtl 沙箱最长未使用时长，超过后由 {@link #evictIdle()} 关闭
     * @param evictionPollInterval 后台调度器检查空闲沙箱的间隔
     */
    public UserSandboxRegistry(
            SandboxClient<DockerSandboxClientOptions> client,
            Path hostWorkspaceRoot,
            Duration idleTtl,
            Duration evictionPollInterval) {
        this.client = Objects.requireNonNull(client, "client");
        this.hostWorkspaceRoot = hostWorkspaceRoot;
        this.idleTtl = Objects.requireNonNull(idleTtl, "idleTtl");
        long pollMs =
                Math.max(
                        1_000L,
                        Objects.requireNonNull(evictionPollInterval, "evictionPollInterval")
                                .toMillis());
        this.evictor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "UserSandboxRegistry-evictor");
                            t.setDaemon(true);
                            return t;
                        });
        this.evictor.scheduleWithFixedDelay(
                this::evictIdleQuietly, pollMs, pollMs, TimeUnit.MILLISECONDS);
        log.info(
                "UserSandboxRegistry initialised: idleTtl={}, evictionPoll={},"
                        + " hostWorkspaceRoot={}",
                idleTtl,
                evictionPollInterval,
                hostWorkspaceRoot);
    }

    /** 返回 {@code (userId, agentId)} 的活跃 {@link Sandbox}，首次调用时创建并启动。{@link #idleTtl} 窗口内后续调用返回同一实例，并更新空闲计时。 */
    public Sandbox borrow(String userId, String agentId) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        Key key = new Key(userId, agentId);
        Entry entry =
                entries.compute(
                        key,
                        (k, existing) -> {
                            if (existing != null) {
                                existing.touch();
                                return existing;
                            }
                            return new Entry(createAndStart(k));
                        });
        return entry.sandbox;
    }

    /** 若 {@code (userId, agentId)} 已缓存，返回其活跃 {@link Sandbox}。不会创建新容器，适合在尚无操作时不应付出冷启动开销的调用方（例如刚打开界面的文件树）。 */
    public Optional<Sandbox> peek(String userId, String agentId) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        Entry e = entries.get(new Key(userId, agentId));
        if (e == null) {
            return Optional.empty();
        }
        e.touch();
        return Optional.of(e.sandbox);
    }

    /** 关闭并移除匹配 {@code (userId, agentId)} 的缓存沙箱。<ul><li>{@code userId} 非 null：仅淘汰该用户对应智能体的沙箱。<li>{@code userId} 为 null：淘汰该智能体所有用户沙箱，供贡献审批使用，使下次 {@link #borrow} 重建容器并读取新批准共享内容。</ul><p>可与其他 {@link #borrow} 调用并发执行；后续访问重新创建沙箱。 */
    public void invalidate(String userId, String agentId) {
        validateSegment("agentId", agentId);
        int removed = 0;
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Key k = e.getKey();
            if (!k.agentId().equals(agentId)) {
                continue;
            }
            if (userId != null && !userId.isBlank() && !k.userId().equals(userId)) {
                continue;
            }
            it.remove();
            closeQuietly(k, e.getValue().sandbox, "invalidate");
            removed++;
        }
        if (removed > 0) {
            log.info(
                    "[sandbox-registry] invalidated {} sandbox(es) for agentId={}, userId={}",
                    removed,
                    agentId,
                    userId == null ? "(all)" : userId);
        }
    }

    /**
     * 对测试可见。关闭最后访问时间超过 {@link
     * #idleTtl} 的所有沙箱。
     */
    void evictIdle() {
        long cutoff = System.currentTimeMillis() - idleTtl.toMillis();
        entries.entrySet()
                .removeIf(
                        e -> {
                            if (e.getValue().lastAccessMs >= cutoff) {
                                return false;
                            }
                            closeQuietly(e.getKey(), e.getValue().sandbox, "idle");
                            return true;
                        });
    }

    private void evictIdleQuietly() {
        try {
            evictIdle();
        } catch (RuntimeException ex) {
            log.warn("[sandbox-registry] eviction sweep failed: {}", ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void shutdownAll() {
        evictor.shutdownNow();
        for (Map.Entry<Key, Entry> e : entries.entrySet()) {
            closeQuietly(e.getKey(), e.getValue().sandbox, "shutdown");
        }
        entries.clear();
    }

    private Sandbox createAndStart(Key key) {
        DockerSandboxClientOptions options = new DockerSandboxClientOptions();
        WorkspaceSpec ws = buildWorkspaceSpec(key);
        Sandbox sandbox = client.create(ws, new NoopSnapshotSpec(), options);
        try {
            sandbox.start();
        } catch (Exception startErr) {
            try {
                sandbox.close();
            } catch (Exception closeErr) {
                log.warn(
                        "[sandbox-registry] failed to close half-started sandbox for {}: {}",
                        key,
                        closeErr.getMessage());
            }
            throw new IllegalStateException(
                    "Failed to start sandbox for " + key + ": " + startErr.getMessage(), startErr);
        }
        log.info("[sandbox-registry] started sandbox for {}", key);
        return sandbox;
    }

    /** 为 {@code key} 构建工作区投影规范：来源根为 {@link #hostWorkspaceRoot} 下的逐智能体分区，使容器只看到自身共享层。按需创建目录，避免分区不存在时 Docker 挂载失败。 */
    private WorkspaceSpec buildWorkspaceSpec(Key key) {
        WorkspaceSpec spec = new WorkspaceSpec();
        if (hostWorkspaceRoot == null) {
            return spec;
        }
        Path agentSlice =
                hostWorkspaceRoot
                        .resolve("agents")
                        .resolve(key.agentId())
                        .toAbsolutePath()
                        .normalize();
        try {
            Files.createDirectories(agentSlice);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to ensure per-agent shared dir " + agentSlice + ": " + e.getMessage(),
                    e);
        }
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(agentSlice.toString());
        projection.setIncludeRoots(DEFAULT_PROJECTION_ROOTS);
        Map<String, WorkspaceEntry> es = new LinkedHashMap<>();
        es.put("__workspace_projection__", projection);
        spec.setEntries(es);
        return spec;
    }

    private void closeQuietly(Key key, Sandbox sandbox, String reason) {
        try {
            sandbox.close();
            log.info("[sandbox-registry] closed sandbox for {} ({})", key, reason);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-registry] failed to close sandbox for {} ({}): {}",
                    key,
                    reason,
                    e.getMessage(),
                    e);
        }
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
    }

    /** 将沙箱限定到单个用户与智能体的复合键。 */
    public record Key(String userId, String agentId) {
        public Key {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(agentId, "agentId");
        }
    }

    private static final class Entry {
        final Sandbox sandbox;
        volatile long lastAccessMs;

        Entry(Sandbox sandbox) {
            this.sandbox = sandbox;
            this.lastAccessMs = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
