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

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Docker 沙箱客户端配置选项。
 *
 * <p>描述创建或恢复 {@link io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox} 时使用的 Docker 容器配置。宿主系统的 {@code PATH} 中必须存在 Docker 命令行。
 */
public class DockerSandboxClientOptions extends SandboxClientOptions {

    /**
 * 要运行的 Docker 镜像，默认 {@code ubuntu:22.04}。
 */
    private String image = "ubuntu:22.04";

    /**
 * 容器内工作区根路径，默认 {@code /workspace}。
 */
    private String workspaceRoot = "/workspace";

    /**
 * 注入容器的环境变量。
 */
    private Map<String, String> environment = new LinkedHashMap<>();

    /**
 * 可选内存字节数限制（例如 {@code 512 * 1024 * 1024L} 表示 512 MB）。
 */
    private Long memorySizeBytes;

    /**
 * 可选 CPU 数量限制（例如 {@code 2L} 表示两个 CPU）。
 */
    private Long cpuCount;

    /**
 * 从容器暴露的宿主端口（{@code hostPort:containerPort} 映射）。
 */
    private int[] exposedPorts = {};

    /**
 * 在镜像名称之前追加到 {@code docker run} 的额外原始参数。
 */
    private List<String> additionalRunArgs = new ArrayList<>();

    /**
 * 传给 {@code docker run --network} 的 Docker 网络模式或网络名称。
 */
    private String network;

    @Override
    public String getType() {
        return "docker";
    }

    /**
     * 为这些选项创建 {@link DockerSandboxClient}。
     *
     * @return 新 Docker 沙箱客户端
     */
    @Override
    public SandboxClient<DockerSandboxClientOptions> createClient() {
        return new DockerSandboxClient();
    }

    /**
     * 返回 Docker 镜像名称。
     *
     * @return Docker 镜像
     */
    public String getImage() {
        return image;
    }

    /**
     * 设置 Docker 镜像名称。
     *
     * @param image Docker 镜像（例如 {@code python:3.12-slim}）
     * @return 当前选项实例
     */
    public DockerSandboxClientOptions image(String image) {
        this.image = image;
        return this;
    }

    /**
     * 设置 Docker 镜像名称。
     *
     * @param image Docker 镜像
     */
    public void setImage(String image) {
        this.image = image;
    }

    /**
     * 返回容器内工作区根路径。
     *
     * @return 工作区根目录
     */
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * 设置容器内工作区根路径。
     *
     * @param workspaceRoot 容器内绝对路径
     * @return 当前选项实例
     */
    public DockerSandboxClientOptions workspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        return this;
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
     * 返回容器环境变量。
     *
     * @return 环境变量映射
     */
    public Map<String, String> getEnvironment() {
        return environment;
    }

    /**
     * 设置容器环境变量。
     *
     * @param environment 键值对
     */
    public void setEnvironment(Map<String, String> environment) {
        this.environment = environment != null ? environment : new LinkedHashMap<>();
    }

    /**
     * 返回可选内存字节数限制。
     *
     * @return 内存限制；未设置时为 {@code null}
     */
    public Long getMemorySizeBytes() {
        return memorySizeBytes;
    }

    /**
     * 设置内存字节数限制。
     *
     * @param memorySizeBytes 内存限制（例如 {@code 512 * 1024 * 1024L}）
     * @return 当前选项实例
     */
    public DockerSandboxClientOptions memorySizeBytes(Long memorySizeBytes) {
        this.memorySizeBytes = memorySizeBytes;
        return this;
    }

    /**
     * 设置内存字节数限制。
     *
     * @param memorySizeBytes 内存字节数限制
     */
    public void setMemorySizeBytes(Long memorySizeBytes) {
        this.memorySizeBytes = memorySizeBytes;
    }

    /**
     * 返回可选 CPU 数量限制。
     *
     * @return CPU 数量；未设置时为 {@code null}
     */
    public Long getCpuCount() {
        return cpuCount;
    }

    /**
     * 设置 CPU 数量限制。
     *
     * @param cpuCount CPU 数量（例如 {@code 2L}）
     * @return 当前选项实例
     */
    public DockerSandboxClientOptions cpuCount(Long cpuCount) {
        this.cpuCount = cpuCount;
        return this;
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
     * 返回要暴露的宿主端口。
     *
     * @return 暴露端口数组
     */
    public int[] getExposedPorts() {
        return exposedPorts;
    }

    /**
     * 设置从容器暴露的宿主端口。
     *
     * @param exposedPorts 端口号
     * @return 当前选项实例
     */
    public DockerSandboxClientOptions exposedPorts(int... exposedPorts) {
        this.exposedPorts = exposedPorts;
        return this;
    }

    /**
     * 设置从容器暴露的宿主端口。
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
     * @return 当前选项实例
     */
    public DockerSandboxClientOptions network(String network) {
        this.network = normalizeNetwork(network);
        return this;
    }

    /**
     * 设置 Docker 网络模式或网络名称。
     *
     * @param network Docker 网络值
     */
    public void setNetwork(String network) {
        this.network = normalizeNetwork(network);
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
     * 在镜像名称之前向 {@code docker run} 追加额外原始参数。
     *
     * @param additionalRunArgs 额外 docker run 参数
     * @return 当前选项实例
     */
    public DockerSandboxClientOptions additionalRunArgs(String... additionalRunArgs) {
        if (additionalRunArgs == null) {
            return this;
        }
        for (String additionalRunArg : additionalRunArgs) {
            if (additionalRunArg != null && !additionalRunArg.isBlank()) {
                this.additionalRunArgs.add(additionalRunArg);
            }
        }
        return this;
    }

    /**
     * 设置追加到 {@code docker run} 的额外原始参数，替换先前配置的所有参数。
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

    private String normalizeNetwork(String network) {
        if (network == null) {
            return null;
        }
        String trimmed = network.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
