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

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;

/**
 * {@link E2bSandboxClient} 的选项。
 */
public class E2bSandboxClientOptions extends SandboxClientOptions {

    private OkHttpClient httpClient;
    private String apiKey;
    private String apiBaseUrl = "https://api.e2b.app";
    private String domain = "e2b.app";

    /**
     * 固定数据平面 URL，用于替换逐沙箱的 {@code <port>-<sandboxId>.<domain>} envd 地址，等价于官方 SDK 的 {@code E2B_SANDBOX_URL}。后方网关必须按每次 envd 请求都发送的 {@code E2b-Sandbox-Id}/{@code E2b-Sandbox-Port} 请求头路由。设置后数据平面忽略 {@code domain}；控制平面仍使用 {@link #getApiBaseUrl()}。
     */
    private String envdSandboxUrl;

    /**
     * E2B 模板 ID（从快照创建时为快照 ID）。
     */
    private String templateId = "base";

    /**
     * 沙箱内工作区根目录的绝对路径。
     */
    private String workspaceRoot = "/home/user";

    private int sandboxTimeoutSeconds = 300;
    private String runUser = "user";
    private E2bPersistenceMode persistenceMode = E2bPersistenceMode.TAR;
    private E2bCodec codec = E2bCodec.PROTO;

    /**
     * 创建沙箱时要挂载的既有 E2B 卷。
     */
    private List<E2bVolumeMount> volumeMounts = new ArrayList<>();

    private int connectTimeoutSeconds = 30;
    private int readTimeoutSeconds = 120;
    private int maxRetries = 3;

    /**
     * 关闭时保留的本会话创建快照的最大数量；{@code <= 0} 禁用裁剪。默认为 {@code 0}（禁用），与历史不裁剪行为一致。
     *
     * <p>在 {@link E2bSandbox#shutdown} 中终止沙箱后执行一次裁剪（E2B 在由快照恢复的沙箱运行期间锁定快照模板，提前删除返回 HTTP 400）。会话按创建顺序将每个快照 ID 记录到 {@link E2bSandboxState#getSnapshotIds()}（最新在末尾）；清理保留最后 {@code snapshotRetention} 个记录 ID，通过 {@code DELETE /templates/{snapshotId}} 删除其余项（404 视为已删除）。未记录在此列表的快照，例如其他会话创建或状态从未保存的运行所创建的快照，绝不触碰。计数前丢弃空白 ID 并折叠重复项（首次出现优先）。删除失败的快照留在记录中，供后续关闭重试，因此记录可能暂时超过 {@code snapshotRetention}。失败的 stop 会移除其自身持久化记录的 ID，使记录与归档保持一致，避免无条件清理删除引用中的快照（其他会话并发向同一状态写入的 ID 不动）；代价是持久化失败会遗留刚创建但无引用的云快照，且不会重试处理它。
     *
     * <p>与 {@link #setMaxRetries(int)} 及超时字段一样，可由传给 {@link E2bSandboxClient#create} 的调用级选项覆盖，但仅限正数值；保留默认值的调用不会重置客户端默认值，单次调用也不能禁用客户端级保留策略，需在客户端上禁用。
     */
    private int snapshotRetention = 0;

    /**
     * 创建沙箱时注入的环境变量。每次创建时通过提供函数解析值，而非构造时只解析一次，因此短期令牌等动态值保持新鲜。跳过 {@code null}。仅对新建沙箱生效；{@code connect} 既有沙箱会保留其原始环境。
     */
    private Map<String, Supplier<String>> envs = new LinkedHashMap<>();

    @Override
    public String getType() {
        return "e2b";
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new E2bSandboxClient(this, null);
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getDomain() {
        return domain;
    }

    /**
     * 设置未配置 {@link #getEnvdSandboxUrl()} 时构建逐沙箱数据平面 URL（如 {@code https://<port>-<sandboxId>.<domain>}）的回退 envd 域名。不影响控制平面基础 URL，后者仍为 {@link #getApiBaseUrl()}。
     */
    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getEnvdSandboxUrl() {
        return envdSandboxUrl;
    }

    /**
     * 设置固定数据平面网关 URL，等价于官方 SDK 的 {@code E2B_SANDBOX_URL}。设置后，envd 请求发往此网关，通过 {@code E2b-Sandbox-Id}/{@code E2b-Sandbox-Port} 请求头路由，而非根据 {@link #getDomain()} 组合逐沙箱主机；控制平面仍使用 {@link #getApiBaseUrl()}。
     */
    public void setEnvdSandboxUrl(String envdSandboxUrl) {
        this.envdSandboxUrl = envdSandboxUrl;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public int getSandboxTimeoutSeconds() {
        return sandboxTimeoutSeconds;
    }

    public void setSandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        this.sandboxTimeoutSeconds = sandboxTimeoutSeconds;
    }

    public String getRunUser() {
        return runUser;
    }

    public void setRunUser(String runUser) {
        this.runUser = runUser;
    }

    public E2bPersistenceMode getPersistenceMode() {
        return persistenceMode;
    }

    public void setPersistenceMode(E2bPersistenceMode persistenceMode) {
        this.persistenceMode = persistenceMode != null ? persistenceMode : E2bPersistenceMode.TAR;
    }

    public E2bCodec getCodec() {
        return codec;
    }

    public void setCodec(E2bCodec codec) {
        this.codec = codec != null ? codec : E2bCodec.PROTO;
    }

    /**
     * 返回创建沙箱时附加的卷挂载。
     *
     * @return 配置挂载的防御性副本（永不为 null）
     */
    public List<E2bVolumeMount> getVolumeMounts() {
        if (volumeMounts == null) {
            return new ArrayList<>();
        }
        List<E2bVolumeMount> out = new ArrayList<>(volumeMounts.size());
        for (E2bVolumeMount m : volumeMounts) {
            if (m == null) {
                continue;
            }
            out.add(new E2bVolumeMount(m.getName(), m.getPath()));
        }
        return out;
    }

    public void setVolumeMounts(List<E2bVolumeMount> volumeMounts) {
        List<E2bVolumeMount> checked = new ArrayList<>();
        if (volumeMounts != null) {
            for (E2bVolumeMount m : volumeMounts) {
                if (m == null) {
                    continue;
                }
                checked.add(new E2bVolumeMount(m.getName(), m.getPath()));
            }
        }
        E2bVolumeMount.requireDistinctMountPaths(checked);
        this.volumeMounts = checked;
    }

    public E2bSandboxClientOptions addVolumeMount(String name, String path) {
        List<E2bVolumeMount> next = getVolumeMounts();
        next.add(new E2bVolumeMount(name, path));
        setVolumeMounts(next);
        return this;
    }

    public E2bSandboxClientOptions addVolumeMount(E2bVolumeMount mount) {
        if (mount == null) {
            return this;
        }
        return addVolumeMount(mount.getName(), mount.getPath());
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getSnapshotRetention() {
        return snapshotRetention;
    }

    public void setSnapshotRetention(int snapshotRetention) {
        this.snapshotRetention = snapshotRetention;
    }

    public Map<String, Supplier<String>> getEnvs() {
        return envs;
    }

    public void setEnvs(Map<String, Supplier<String>> envs) {
        this.envs = envs != null ? new LinkedHashMap<>(envs) : new LinkedHashMap<>();
    }

    /**
     * 注册创建沙箱时使用的固定值环境变量。
     */
    public void env(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("env name must not be blank");
        }
        envs.put(name, () -> value);
    }

    /**
     * 注册每次创建沙箱时重新解析值的环境变量。
     */
    public void env(String name, Supplier<String> valueSupplier) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("env name must not be blank");
        }
        envs.put(name, valueSupplier);
    }
}
