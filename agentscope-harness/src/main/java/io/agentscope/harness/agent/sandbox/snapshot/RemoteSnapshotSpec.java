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

/**
 * 创建由 {@link RemoteSnapshotClient} 支撑的 {@link RemoteSandboxSnapshot} 实例的快照规范。
 *
 * <p>由此规范创建的所有会话共享同一个客户端实例。实现 {@link RemoteSnapshotClient} 可连接所用远程存储（例如 AWS S3、阿里云 OSS、Google GCS）。
 */
public class RemoteSnapshotSpec implements SandboxSnapshotSpec {

    private final RemoteSnapshotClient client;

    /**
     * 创建远程快照规范。
     *
     * @param client 要使用的远程存储客户端实现
     */
    public RemoteSnapshotSpec(RemoteSnapshotClient client) {
        this.client = client;
    }

    public RemoteSnapshotClient getClient() {
        return client;
    }

    /**
     * {@inheritDoc}
     *
     * @return 使用此规范客户端的新 {@link RemoteSandboxSnapshot}
     */
    @Override
    public SandboxSnapshot build(String snapshotId) {
        return new RemoteSandboxSnapshot(client, snapshotId);
    }
}
