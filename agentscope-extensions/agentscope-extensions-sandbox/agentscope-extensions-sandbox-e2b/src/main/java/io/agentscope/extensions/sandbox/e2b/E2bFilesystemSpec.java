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
package io.agentscope.extensions.sandbox.e2b;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.List;
import java.util.function.Supplier;

/**
 * E2B 云沙箱的 {@link SandboxFilesystemSpec}。
 */
public class E2bFilesystemSpec extends SandboxFilesystemSpec {

    private SandboxClient<?> client;
    private final E2bSandboxClientOptions options = new E2bSandboxClientOptions();
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = new WorkspaceSpec();

    public E2bFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    public E2bFilesystemSpec apiKey(String apiKey) {
        options.setApiKey(apiKey);
        return this;
    }

    public E2bFilesystemSpec apiBaseUrl(String apiBaseUrl) {
        options.setApiBaseUrl(apiBaseUrl);
        return this;
    }

    public E2bFilesystemSpec domain(String domain) {
        options.setDomain(domain);
        return this;
    }

    /**
     * 设置固定 envd 网关 URL，等价于官方 SDK 的 {@code E2B_SANDBOX_URL}。配置后，所有沙箱的 envd 流量均发送到此网关，通过 {@code E2b-Sandbox-Id}/{@code E2b-Sandbox-Port} 请求头路由，而非使用根据 {@code domain} 派生的通配 DNS 主机。
     *
     * @param envdSandboxUrl envd 请求的固定数据平面网关 URL
     * @return 当前规格
     */
    public E2bFilesystemSpec envdSandboxUrl(String envdSandboxUrl) {
        options.setEnvdSandboxUrl(envdSandboxUrl);
        return this;
    }

    public E2bFilesystemSpec templateId(String templateId) {
        options.setTemplateId(templateId);
        return this;
    }

    public E2bFilesystemSpec workspaceRoot(String workspaceRoot) {
        options.setWorkspaceRoot(workspaceRoot);
        return this;
    }

    public E2bFilesystemSpec sandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        options.setSandboxTimeoutSeconds(sandboxTimeoutSeconds);
        return this;
    }

    public E2bFilesystemSpec runUser(String runUser) {
        options.setRunUser(runUser);
        return this;
    }

    /**
     * 注册创建沙箱时使用的固定值环境变量。
     */
    public E2bFilesystemSpec env(String name, String value) {
        options.env(name, value);
        return this;
    }

    /**
     * 注册每次创建沙箱时重新解析值的环境变量，适用于访问令牌等短期有效值。
     */
    public E2bFilesystemSpec env(String name, Supplier<String> valueSupplier) {
        options.env(name, valueSupplier);
        return this;
    }

    public E2bFilesystemSpec persistenceMode(E2bPersistenceMode persistenceMode) {
        options.setPersistenceMode(persistenceMode);
        return this;
    }

    public E2bFilesystemSpec codec(E2bCodec codec) {
        options.setCodec(codec);
        return this;
    }

    public E2bFilesystemSpec volumeMount(String name, String path) {
        options.addVolumeMount(name, path);
        return this;
    }

    public E2bFilesystemSpec volumeMount(E2bVolumeMount mount) {
        options.addVolumeMount(mount);
        return this;
    }

    /**
     * 替换全部配置的卷挂载。
     *
     * <p>null 或空列表会清除此规格的挂载，但 {@link E2bSandboxClient} 合并时将空的调用级列表视为“未设置”（与 AgentRun OSS 挂载约定相同），因此只有这些选项同时作为客户端默认值时，清除才真正生效。
     *
     * @param mounts 替换挂载（null 或空列表清除先前添加的挂载）
     */
    public E2bFilesystemSpec volumeMounts(List<E2bVolumeMount> mounts) {
        options.setVolumeMounts(mounts);
        return this;
    }

    public E2bFilesystemSpec connectTimeoutSeconds(int connectTimeoutSeconds) {
        options.setConnectTimeoutSeconds(connectTimeoutSeconds);
        return this;
    }

    public E2bFilesystemSpec readTimeoutSeconds(int readTimeoutSeconds) {
        options.setReadTimeoutSeconds(readTimeoutSeconds);
        return this;
    }

    public E2bFilesystemSpec maxRetries(int maxRetries) {
        options.setMaxRetries(maxRetries);
        return this;
    }

    /**
     * 关闭时保留的本会话创建快照的最大数量；{@code <= 0} 禁用裁剪。默认为 {@code 0}（禁用），与历史上不裁剪的行为一致。完整契约参见 {@link E2bSandboxClientOptions#setSnapshotRetention(int)}。
     */
    public E2bFilesystemSpec snapshotRetention(int snapshotRetention) {
        options.setSnapshotRetention(snapshotRetention);
        return this;
    }

    public E2bFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec;
        return this;
    }

    public E2bFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
        this.defaultWorkspaceSpec = workspaceSpec;
        return this;
    }

    @Override
    protected SandboxClient<?> createClient() {
        return client != null ? client : options.createClient();
    }

    @Override
    protected SandboxClientOptions clientOptions() {
        return options;
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return snapshotSpec;
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        return defaultWorkspaceSpec;
    }
}
