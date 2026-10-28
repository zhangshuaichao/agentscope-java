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

/**
 * AgentRun 沙箱的 NAS 挂载配置。
 *
 * <p>NAS 在创建沙箱时配置，提供跨沙箱重启保留的实例级持久存储。使用 NAS 挂载作为 {@code workspaceRoot} 的底层存储，即可无需管理 tar 快照而获得持久化。
 */
public class AgentRunNasMountConfig {

    private String serverAddr;
    private String mountDir;
    private String remotePath = "/";
    private boolean enableTLS = false;

    /**
     * 默认构造器。
     */
    public AgentRunNasMountConfig() {}

    /**
     * 返回 NAS 服务地址（例如 {@code 12345-abc.cn-hangzhou.nas.aliyuncs.com}）。
     *
     * @return NAS 服务地址
     */
    public String getServerAddr() {
        return serverAddr;
    }

    public AgentRunNasMountConfig setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
        return this;
    }

    /**
     * 返回沙箱内挂载目录（必须以 {@code /home}、{@code /mnt} 或 {@code /data} 开头）。
     *
     * @return 绝对挂载路径
     */
    public String getMountDir() {
        return mountDir;
    }

    public AgentRunNasMountConfig setMountDir(String mountDir) {
        this.mountDir = mountDir;
        return this;
    }

    /**
     * 返回要挂载的 NAS 文件系统远程路径。
     *
     * @return 远程路径，默认为 {@code "/"}
     */
    public String getRemotePath() {
        return remotePath;
    }

    public AgentRunNasMountConfig setRemotePath(String remotePath) {
        this.remotePath = remotePath != null ? remotePath : "/";
        return this;
    }

    /**
     * 返回 NAS 连接是否启用 TLS。
     *
     * @return 启用 TLS 时为 true
     */
    public boolean isEnableTLS() {
        return enableTLS;
    }

    public AgentRunNasMountConfig setEnableTLS(boolean enableTLS) {
        this.enableTLS = enableTLS;
        return this;
    }
}
