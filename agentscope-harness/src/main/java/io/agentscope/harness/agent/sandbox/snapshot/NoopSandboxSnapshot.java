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
package io.agentscope.harness.agent.sandbox.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 不持久保存任何内容的空操作快照。
 *
 * <p>使用 {@code NoopSandboxSnapshot} 时，不会通过快照跨会话停止保留工作区状态。每次全新启动会应用完整清单（启动逻辑的分支 D）。无需工作区持久性时使用。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NoopSandboxSnapshot implements SandboxSnapshot {

    private static final String ID = "noop";

    /**
 * 创建空操作快照。
 */
    public NoopSandboxSnapshot() {}

    /**
     * {@inheritDoc}
     *
     * <p>返回 {@code false}。使用此快照时会完全跳过工作区归档步骤，因此正常流程无需进行归档。
     */
    @Override
    public boolean isPersistenceEnabled() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>此实现完全丢弃归档流。
     */
    @Override
    public void persist(InputStream workspaceArchive) throws Exception {
        // 有意丢弃：空操作快照不持久保存任何内容
        if (workspaceArchive != null) {
            workspaceArchive.transferTo(OutputStream.nullOutputStream());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>始终抛出 {@link io.agentscope.harness.agent.sandbox.SandboxException.SnapshotException}，因为空操作快照永远不可恢复。
     */
    @Override
    public InputStream restore() throws Exception {
        throw new io.agentscope.harness.agent.sandbox.SandboxException.SnapshotException(ID);
    }

    /**
     * {@inheritDoc}
     *
     * @return 始终为 {@code false}
     */
    @Override
    public boolean isRestorable() {
        return false;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getType() {
        return "noop";
    }
}
