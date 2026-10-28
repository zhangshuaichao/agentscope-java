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
 * AgentRun 沙箱的 OSS 实例级挂载配置。
 *
 * <p>OSS 挂载使用 ossfs FUSE，按沙箱实例生效。每个沙箱最多支持五个挂载。目标目录必须位于 {@code /home}、{@code /mnt} 或 {@code /data} 下，存储桶必须使用标准存储并与沙箱同地域。
 */
public class AgentRunOssMountConfig {

    private String bucketName;
    private String bucketPath = "/";
    private String endpoint;
    private String mountDir;
    private boolean readOnly = false;

    /**
     * 默认构造器。
     */
    public AgentRunOssMountConfig() {}

    /**
     * 返回要挂载的 OSS 存储桶名。
     *
     * @return 存储桶名
     */
    public String getBucketName() {
        return bucketName;
    }

    public AgentRunOssMountConfig setBucketName(String bucketName) {
        this.bucketName = bucketName;
        return this;
    }

    /**
     * 返回要挂载的 OSS 存储桶子路径。
     *
     * @return 存储桶路径，默认为 {@code "/"}
     */
    public String getBucketPath() {
        return bucketPath;
    }

    public AgentRunOssMountConfig setBucketPath(String bucketPath) {
        this.bucketPath = bucketPath != null ? bucketPath : "/";
        return this;
    }

    /**
     * 返回 OSS 端点（例如 {@code oss-cn-hangzhou-internal.aliyuncs.com}）。
     *
     * @return OSS 端点
     */
    public String getEndpoint() {
        return endpoint;
    }

    public AgentRunOssMountConfig setEndpoint(String endpoint) {
        this.endpoint = endpoint;
        return this;
    }

    /**
     * 返回沙箱内挂载目录。
     *
     * @return 绝对挂载路径（必须以 {@code /home}、{@code /mnt} 或 {@code /data} 开头）
     */
    public String getMountDir() {
        return mountDir;
    }

    public AgentRunOssMountConfig setMountDir(String mountDir) {
        this.mountDir = mountDir;
        return this;
    }

    /**
     * 返回挂载是否只读。
     *
     * @return 只读时为 true
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    public AgentRunOssMountConfig setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }
}
