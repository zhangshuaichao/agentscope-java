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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;

/**
 * 沙箱的可序列化状态，由 {@link SessionSandboxStateStore} 持久保存，使沙箱可跨调用恢复。
 *
 * <p>{@link #workspaceRootReady} 标记驱动四分支启动逻辑：记录上次停止时工作区是否已完全初始化。
 *
 * <p>具体子类型通过 {@link io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule} 为 Jackson 注册（扩展类型可另用 {@link com.fasterxml.jackson.databind.ObjectMapper#registerSubtypes}），而非在此类上使用 {@code @JsonSubTypes}。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public abstract class SandboxState {

    private String sessionId;

    @JsonProperty("manifest")
    private WorkspaceSpec workspaceSpec;

    private SandboxSnapshot snapshot;
    private String workspaceProjectionHash;
    private boolean workspaceRootReady = false;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public WorkspaceSpec getWorkspaceSpec() {
        return workspaceSpec;
    }

    public void setWorkspaceSpec(WorkspaceSpec workspaceSpec) {
        this.workspaceSpec = workspaceSpec;
    }

    public SandboxSnapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(SandboxSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public String getWorkspaceProjectionHash() {
        return workspaceProjectionHash;
    }

    public void setWorkspaceProjectionHash(String workspaceProjectionHash) {
        this.workspaceProjectionHash = workspaceProjectionHash;
    }

    public boolean isWorkspaceRootReady() {
        return workspaceRootReady;
    }

    public void setWorkspaceRootReady(boolean workspaceRootReady) {
        this.workspaceRootReady = workspaceRootReady;
    }
}
