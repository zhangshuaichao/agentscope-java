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

/**
 * 创建 {@link NoopSandboxSnapshot} 实例的快照规范。
 *
 * <p>使用此规范意味着不会通过快照跨会话停止持久保存工作区状态；全新工作区由完整清单初始化。
 *
 * <p>【学习提示】禁用快照不等于立即清空仍存活的工作区。启动时是否沿用现有目录，仍由 AbstractBaseSandbox 的 workspaceRootReady 标记及目录探测分支决定。
 */
public class NoopSnapshotSpec implements SandboxSnapshotSpec {

    /**
 * 创建空操作快照规范。
 */
    public NoopSnapshotSpec() {}

    /**
     * {@inheritDoc}
     *
     * @return 新 {@link NoopSandboxSnapshot}（忽略 {@code snapshotId}）
     */
    @Override
    public SandboxSnapshot build(String snapshotId) {
        return new NoopSandboxSnapshot();
    }
}
