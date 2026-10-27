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

import java.nio.file.Path;

/**
 * 创建保存在本地目录中的 {@link LocalSandboxSnapshot} 实例的快照规范。
 *
 * <p>每个会话在 {@code {basePath}/{sessionId}.tar} 拥有独立快照文件。
 */
public class LocalSnapshotSpec implements SandboxSnapshotSpec {

    private final String basePath;

    /**
     * 创建本地快照规范。
     *
     * @param basePath 用于保存快照 tar 文件的目录
     */
    public LocalSnapshotSpec(Path basePath) {
        this.basePath = basePath.toString();
    }

    /**
     * 创建本地快照规范。
     *
     * @param basePath 用于保存快照 tar 文件的目录路径字符串
     */
    public LocalSnapshotSpec(String basePath) {
        this.basePath = basePath;
    }

    /**
     * {@inheritDoc}
     *
     * @return 保存于 {@code {basePath}/{snapshotId}.tar} 的新 {@link LocalSandboxSnapshot}
     */
    @Override
    public SandboxSnapshot build(String snapshotId) {
        return new LocalSandboxSnapshot(basePath, snapshotId);
    }

    /**
     * 返回用于快照文件的基目录。
     *
     * @return 基路径字符串
     */
    public String getBasePath() {
        return basePath;
    }
}
