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
package io.agentscope.extensions.sandbox.agentrun;

import io.agentscope.harness.agent.sandbox.SandboxState;

/**
 * AgentRun 沙箱的可序列化状态。
 */
public class AgentRunSandboxState extends SandboxState {

    /**
     * AgentRun 沙箱容器内的默认工作区根路径。
     */
    public static final String DEFAULT_WORKSPACE_ROOT = "/home/agentscope/workspace";

    private String sandboxId;
    private String workspaceRoot = DEFAULT_WORKSPACE_ROOT;
    private String templateName;
    private String accountId;
    private String region;
    private String mcpServerUrl;
    private boolean sandboxOwned = true;
    private boolean workspaceOnNas = false;

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot != null ? workspaceRoot : DEFAULT_WORKSPACE_ROOT;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getMcpServerUrl() {
        return mcpServerUrl;
    }

    public void setMcpServerUrl(String mcpServerUrl) {
        this.mcpServerUrl = mcpServerUrl;
    }

    public boolean isSandboxOwned() {
        return sandboxOwned;
    }

    public void setSandboxOwned(boolean sandboxOwned) {
        this.sandboxOwned = sandboxOwned;
    }

    /**
     * 返回 {@link #workspaceRoot} 是否位于 NAS 或 OSS 持久挂载上。
     *
     * <p>为 {@code true} 时，删除沙箱后工作区仍保留，适配器跳过 tar 持久化；“恢复”通过相同确定性 ID 和相同挂载配置重建沙箱实现。
     *
     * @return 工作区根目录由持久挂载支持时为 true
     */
    public boolean isWorkspaceOnNas() {
        return workspaceOnNas;
    }

    public void setWorkspaceOnNas(boolean workspaceOnNas) {
        this.workspaceOnNas = workspaceOnNas;
    }
}
