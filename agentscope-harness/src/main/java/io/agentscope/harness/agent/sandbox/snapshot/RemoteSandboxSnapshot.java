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
package io.agentscope.harness.agent.sandbox.snapshot;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.InputStream;

/**
 * 由 {@link RemoteSnapshotClient} 支撑的快照（例如 S3、OSS、GCS）。
 *
 * <p>此类将所有操作委托给提供的客户端，由客户端负责认证、重试逻辑和网络错误处理。
 *
 * <p>注意：{@code RemoteSandboxSnapshot} 不能直接序列化为 JSON，因为 {@link RemoteSnapshotClient} 不可序列化。持久保存会话状态时只需 {@code id}，恢复时从构建器重新注入客户端。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteSandboxSnapshot implements SandboxSnapshot {

    @JsonIgnore private final RemoteSnapshotClient client;
    private final String id;

    @JsonCreator
    public RemoteSandboxSnapshot(@JsonProperty("id") String id) {
        this(null, id);
    }

    /**
     * 创建远程快照。
     *
     * @param client 用于委托操作的远程存储客户端
     * @param id 此快照的唯一标识符
     */
    public RemoteSandboxSnapshot(RemoteSnapshotClient client, String id) {
        this.client = client;
        this.id = id;
    }

    /**
     * {@inheritDoc}
     *
     * <p>通过 {@link RemoteSnapshotClient#upload} 上传归档。
     */
    @Override
    public void persist(InputStream workspaceArchive) throws Exception {
        try {
            requireClient().upload(id, workspaceArchive);
        } catch (Exception e) {
            throw new SandboxException.SnapshotException(id, "Remote upload failed", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>通过 {@link RemoteSnapshotClient#download} 下载归档。
     */
    @Override
    public InputStream restore() throws Exception {
        try {
            return requireClient().download(id);
        } catch (Exception e) {
            throw new SandboxException.SnapshotException(id, "Remote download failed", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>通过 {@link RemoteSnapshotClient#exists} 检查存在性。
     */
    @Override
    public boolean isRestorable() throws Exception {
        try {
            return requireClient().exists(id);
        } catch (Exception e) {
            throw new SandboxException.SnapshotException(id, "Remote exists check failed", e);
        }
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return "remote";
    }

    private RemoteSnapshotClient requireClient() {
        if (client == null) {
            throw new IllegalStateException(
                    "RemoteSnapshotClient is not bound to snapshot id: " + id);
        }
        return client;
    }
}
