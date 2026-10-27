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
package io.agentscope.harness.agent.sandbox.layout;

/**
 * 声明绑定挂载到沙箱工作区的宿主目录（Kubernetes 下也可为文件），挂载位置为 {@link io.agentscope.harness.agent.sandbox.WorkspaceSpec#getRoot()} 下的映射键路径。
 *
 * <p>{@link io.agentscope.harness.agent.sandbox.WorkspaceSpec} 条目的<strong>键</strong>是工作区内相对路径（例如键 {@code cache} 对应 {@code <workspaceRoot>/cache}）。
 * {@link #hostPath} 是宿主机（Docker）或 Kubernetes 节点（HostPath 卷）上的绝对路径。
 *
 * <p><strong>后端：</strong>{@link io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox} 传入 {@code -v host:container}；{@code kubernetes} 使用 HostPath 卷和 volumeMount。
 * 云沙箱（Daytona、E2B）运行时忽略此条目（参见日志），应改用投影或供应商专用挂载。
 *
 * <p><strong>安全：</strong>绑定挂载会将宿主路径暴露给沙箱工作负载；仅挂载你信任模型和工具可读取或修改的路径。
 */
public class BindMountEntry extends WorkspaceEntry {

    private String hostPath;
    private boolean readOnly;

    public String getHostPath() {
        return hostPath;
    }

    public void setHostPath(String hostPath) {
        this.hostPath = hostPath;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
}
