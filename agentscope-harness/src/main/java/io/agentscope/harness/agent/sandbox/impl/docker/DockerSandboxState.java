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
package io.agentscope.harness.agent.sandbox.impl.docker;

import io.agentscope.harness.agent.sandbox.SandboxState;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker 支撑的 {@link io.agentscope.harness.agent.sandbox.Sandbox} 的可序列化状态。
 *
 * <p>每次调用后持久保存，以便透明恢复容器。恢复时，若 {@link #containerId} 标识的容器仍存活，则重新连接；已停止则重启；已移除则创建新容器，并从快照恢复工作区。
 */
public class DockerSandboxState extends SandboxState {

    /**
 * 底层 Docker 容器 ID。
 */
    private String containerId;

    /**
 * 人类可读的容器名称（例如 {@code agentscope-sandbox-<sessionId>}）。
 */
    private String containerName;

    /**
 * 创建此容器使用的 Docker 镜像。
 */
    private String image;

    /**
 * 容器内工作区根路径。
 */
    private String workspaceRoot;

    /**
     * SDK 是否拥有容器生命周期（创建／停止／移除）。为 {@code false} 时，容器由开发者注入，不会被移除。
     */
    private boolean containerOwned = true;

    /**
 * 为恢复时重建容器保存的可选内存字节数限制。
 */
    private Long memorySizeBytes;

    /**
 * 为恢复时重建容器保存的可选 CPU 数量限制。
 */
    private Long cpuCount;

    /**
 * 为恢复时重建容器保存的暴露端口号。
 */
    private int[] exposedPorts = {};

    /**
 * 传给 {@code docker run --network} 的 Docker 网络模式或名称。
 */
    private String network;

    /**
 * 在镜像名称之前追加到 {@code docker run} 的额外原始参数。
 */
    private List<String> additionalRunArgs = new ArrayList<>();

    /**
     * 返回 Docker 容器 ID。
     *
     * @return 容器 ID；尚未创建容器时为 {@code null}
     */
    public String getContainerId() {
        return containerId;
    }

    /**
     * 设置 Docker 容器 ID。
     *
     * @param containerId Docker 容器 ID
     */
    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    /**
     * 返回容器名称。
     *
     * @return 容器名称
     */
    public String getContainerName() {
        return containerName;
    }

    /**
     * 设置容器名称。
     *
     * @param containerName 容器名称
     */
    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    /**
     * 返回此容器使用的 Docker 镜像。
     *
     * @return Docker 镜像
     */
    public String getImage() {
        return image;
    }

    /**
     * 设置 Docker 镜像。
     *
     * @param image Docker 镜像
     */
    public void setImage(String image) {
        this.image = image;
    }

    /**
     * 返回容器内工作区根路径。
     *
     * @return 工作区根路径
     */
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * 设置容器内工作区根路径。
     *
     * @param workspaceRoot 容器内绝对路径
     */
    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * 返回 SDK 是否拥有容器生命周期。
     *
     * @return SDK 管理容器创建与移除时为 {@code true}
     */
    public boolean isContainerOwned() {
        return containerOwned;
    }

    /**
     * 设置 SDK 是否拥有容器生命周期。
     *
     * @param containerOwned SDK 应在关闭时停止并移除容器则为 {@code true}
     */
    public void setContainerOwned(boolean containerOwned) {
        this.containerOwned = containerOwned;
    }

    /**
     * 返回可选内存字节数限制。
     *
     * @return 内存限制或 {@code null}
     */
    public Long getMemorySizeBytes() {
        return memorySizeBytes;
    }

    /**
     * 设置内存字节数限制。
     *
     * @param memorySizeBytes 内存限制
     */
    public void setMemorySizeBytes(Long memorySizeBytes) {
        this.memorySizeBytes = memorySizeBytes;
    }

    /**
     * 返回可选 CPU 数量限制。
     *
     * @return CPU 数量或 {@code null}
     */
    public Long getCpuCount() {
        return cpuCount;
    }

    /**
     * 设置 CPU 数量限制。
     *
     * @param cpuCount CPU 数量
     */
    public void setCpuCount(Long cpuCount) {
        this.cpuCount = cpuCount;
    }

    /**
     * 返回暴露的端口号。
     *
     * @return 端口号数组
     */
    public int[] getExposedPorts() {
        return exposedPorts;
    }

    /**
     * 设置暴露的端口号。
     *
     * @param exposedPorts 端口号
     */
    public void setExposedPorts(int[] exposedPorts) {
        this.exposedPorts = exposedPorts != null ? exposedPorts : new int[0];
    }

    /**
     * 返回 Docker 网络模式或网络名称。
     *
     * @return Docker 网络值；未设置时为 {@code null}
     */
    public String getNetwork() {
        return network;
    }

    /**
     * 设置 Docker 网络模式或网络名称。
     *
     * @param network Docker 网络值
     */
    public void setNetwork(String network) {
        if (network == null) {
            this.network = null;
            return;
        }
        String trimmed = network.trim();
        this.network = trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 返回追加到 {@code docker run} 的额外原始参数。
     *
     * @return 额外 docker run 参数
     */
    public List<String> getAdditionalRunArgs() {
        return additionalRunArgs;
    }

    /**
     * 设置追加到 {@code docker run} 的额外原始参数。
     *
     * @param additionalRunArgs 额外 docker run 参数
     */
    public void setAdditionalRunArgs(List<String> additionalRunArgs) {
        this.additionalRunArgs = new ArrayList<>();
        if (additionalRunArgs == null) {
            return;
        }
        for (String additionalRunArg : additionalRunArgs) {
            if (additionalRunArg != null && !additionalRunArg.isBlank()) {
                this.additionalRunArgs.add(additionalRunArg);
            }
        }
    }
}
