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
package io.agentscope.harness.agent.filesystem.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.Sandbox;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在实例整个生命周期内持有固定 {@link Sandbox} 引用的 {@link SandboxBackedFilesystem}。
 *
 * <p>用于异步会话镜像，使智能体调用释放共享智能体代理上的每次调用绑定后，上传仍可完成。
 * 与智能体级 {@link SandboxBackedFilesystem} 不同，{@link #clearSandboxIfCurrent} 不执行任何操作：清理调用代理不得解除此镜像文件系统的固定绑定。
 *
 * <p>对于 DataAgent 风格、在获取／释放之间仍存活的<em>用户管理</em>沙箱，这样使用是安全的。
 * 自行管理的沙箱在释放时拒绝新上传，并限时等待活动上传；等待到期后仍在途的上传可能在关闭期间失败。
 */
public final class PinnedSandboxFilesystem extends SandboxBackedFilesystem {

    private static final Logger log = LoggerFactory.getLogger(PinnedSandboxFilesystem.class);

    /**
     * 控制每次释放时同步等待毫秒数的 JVM 属性（默认 1000）。
     * 零表示不等待。每次连续释放各有独立预算，不是整个关闭过程的截止时间。
     * 无效值或负值回退到默认值。
     */
    public static final String RELEASE_GATE_TIMEOUT_PROPERTY =
            "agentscope.sandbox.mirror.release-timeout-millis";

    private static final long DEFAULT_RELEASE_GATE_TIMEOUT_MILLIS = 1_000;
    private static final ReferenceQueue<Sandbox> STALE_SANDBOXES = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference, MirrorGate> MIRROR_GATES = new HashMap<>();

    private final Sandbox pinnedSandbox;
    private final MirrorGate mirrorGate;

    public PinnedSandboxFilesystem(Sandbox sandbox) {
        this.pinnedSandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.mirrorGate = gateFor(sandbox);
        super.setSandbox(sandbox);
    }

    /**
     * 为新获取的沙箱启动新一代镜像门控。
     *
     * <p>管理器可能在后续调用中复用同一个 {@link Sandbox} 对象。
     * 替换旧门控而不是重新打开它，可使上次调用的镜像永久保持已释放状态，且无法附着到新调用的生命周期。
     *
     * <p>管理器必须对每个自行管理的沙箱对象串行执行获取／释放：每个实例只支持一个活动代次。
     * 并发调用必须使用不同实例。
     */
    public static void markSandboxAcquired(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        synchronized (MIRROR_GATES) {
            expungeStaleGates();
            MIRROR_GATES.put(new IdentityWeakReference(sandbox, STALE_SANDBOXES), new MirrorGate());
        }
    }

    /**
     * 阻止新的镜像上传，并在关闭前短暂等待在途上传。
     *
     * <p>每次释放都从 {@link #RELEASE_GATE_TIMEOUT_PROPERTY} 读取预算。
     * 上传在整个远程传输期间持有读锁，因此正常但缓慢的上传也可能耗尽预算。
     * 连续释放每次都有独立等待；这不是全局超时，也不是上传超时。预算到期后继续关闭。
     */
    public static void markSandboxReleased(Sandbox sandbox) {
        if (sandbox != null) {
            gateFor(sandbox).markReleased(releaseGateTimeoutMillis());
        }
    }

    /**
     * 为异步镜像固定绑定的沙箱是否仍在运行。
     *
     * <p>自行管理的沙箱会在调用释放时停止。此时会话镜像本就是尽力而为，不得尝试向已释放资源传输。
     */
    public boolean isSandboxRunning() {
        mirrorGate.lock.readLock().lock();
        try {
            return !mirrorGate.released && pinnedSandbox.isRunning();
        } finally {
            mirrorGate.lock.readLock().unlock();
        }
    }

    /**
     * 返回此固定绑定文件系统所属调用代次是否已释放。
     *
     * <p>volatile 闩锁在等待释放之前发布，使此查询无需排在写锁请求或停滞的上传之后，就能观察到释放状态。
     */
    public boolean isSandboxReleased() {
        return mirrorGate.released;
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        mirrorGate.lock.readLock().lock();
        try {
            if (mirrorGate.released || !pinnedSandbox.isRunning()) {
                List<FileUploadResponse> failed = new ArrayList<>(files.size());
                for (Map.Entry<String, byte[]> file : files) {
                    failed.add(FileUploadResponse.fail(file.getKey(), "Sandbox has been released"));
                }
                return failed;
            }
            return super.uploadFiles(runtimeContext, files);
        } finally {
            mirrorGate.lock.readLock().unlock();
        }
    }

    @Override
    public synchronized void clearSandboxIfCurrent(Sandbox expected) {
        // 保留固定绑定，以便调用结束后执行镜像上传。
    }

    static long releaseGateTimeoutMillis() {
        String configured = System.getProperty(RELEASE_GATE_TIMEOUT_PROPERTY);
        if (configured != null) {
            try {
                long timeout = Long.parseLong(configured);
                if (timeout >= 0) {
                    return timeout;
                }
            } catch (NumberFormatException ignored) {
                // 无效配置不得中断沙箱清理。
            }
            log.warn(
                    "Invalid {} value '{}'; using {} ms",
                    RELEASE_GATE_TIMEOUT_PROPERTY,
                    configured,
                    DEFAULT_RELEASE_GATE_TIMEOUT_MILLIS);
        }
        return DEFAULT_RELEASE_GATE_TIMEOUT_MILLIS;
    }

    private static MirrorGate gateFor(Sandbox sandbox) {
        synchronized (MIRROR_GATES) {
            expungeStaleGates();
            IdentityWeakReference lookup = new IdentityWeakReference(sandbox);
            MirrorGate gate = MIRROR_GATES.get(lookup);
            if (gate == null) {
                gate = new MirrorGate();
                MIRROR_GATES.put(new IdentityWeakReference(sandbox, STALE_SANDBOXES), gate);
            }
            return gate;
        }
    }

    private static void expungeStaleGates() {
        IdentityWeakReference stale;
        while ((stale = (IdentityWeakReference) STALE_SANDBOXES.poll()) != null) {
            MIRROR_GATES.remove(stale);
        }
    }

    private static final class MirrorGate {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private volatile boolean released;

        private void markReleased(long timeoutMillis) {
            // 在等待之前发布闩锁，即使现有远程传输卡住，
            // 也能拒绝新上传。镜像是尽力而为的，因此释放仍保持有界。
            released = true;
            boolean acquired = false;
            try {
                acquired = lock.writeLock().tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    log.warn(
                            "Mirror upload still in flight after {} ms; releasing sandbox",
                            timeoutMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for an in-flight sandbox mirror upload");
            } finally {
                if (acquired) {
                    lock.writeLock().unlock();
                }
            }
        }
    }

    /**
     * 按对象身份而非 {@code equals/hashCode} 判断相等的弱键。
     */
    private static final class IdentityWeakReference extends WeakReference<Sandbox> {
        private final int identityHash;

        private IdentityWeakReference(Sandbox sandbox) {
            super(sandbox);
            this.identityHash = System.identityHashCode(sandbox);
        }

        private IdentityWeakReference(
                Sandbox sandbox, ReferenceQueue<? super Sandbox> referenceQueue) {
            super(sandbox, referenceQueue);
            this.identityHash = System.identityHashCode(sandbox);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityWeakReference that)) {
                return false;
            }
            Sandbox sandbox = get();
            return sandbox != null && sandbox == that.get();
        }
    }
}
