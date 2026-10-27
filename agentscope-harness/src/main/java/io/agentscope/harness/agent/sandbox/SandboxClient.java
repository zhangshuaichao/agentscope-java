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

import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * 创建和恢复 {@link Sandbox} 实例的工厂。
 *
 * @param <O> 此实现的客户端选项类型
 */
public interface SandboxClient<O extends SandboxClientOptions> {

    /**
     * 使用给定工作区规范和快照规范创建新沙箱。
     *
     * <p>返回时尚未启动；使用前调用 {@link Sandbox#start()}。
     */
    Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec, O options);

    /**
     * 从先前序列化的 {@link SandboxState} 恢复沙箱。
     */
    Sandbox resume(SandboxState state);

    void delete(Sandbox sandbox);

    String serializeState(SandboxState state);

    SandboxState deserializeState(String json);

    default SandboxState deserializeState(String json, SandboxSnapshotSpec snapshotSpec) {
        return deserializeState(json);
    }
}
