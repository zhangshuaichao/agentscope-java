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

import java.io.InputStream;

/**
 * 由用户实现的接口，用于向远程存储（例如 S3、OSS、GCS 或自定义 Blob 存储）上传和下载快照归档。
 *
 * <p>实现此接口并传给 {@link RemoteSnapshotSpec} 即可启用远程快照存储。实现负责认证、重试逻辑和连接管理。
 */
public interface RemoteSnapshotClient {

    /**
     * 向远程存储上传快照归档。
     *
     * @param snapshotId 此快照的唯一标识符
     * @param data 要上传的工作区 tar 归档流
     * @throws Exception 上传失败时抛出
     */
    void upload(String snapshotId, InputStream data) throws Exception;

    /**
     * 从远程存储下载快照归档。
     *
     * @param snapshotId 要下载快照的唯一标识符
     * @return 下载所得 tar 归档的 {@link InputStream}
     * @throws Exception 下载失败或快照不存在时抛出
     */
    InputStream download(String snapshotId) throws Exception;

    /**
     * 检查远程存储中是否存在给定 ID 的快照。
     *
     * @param snapshotId 要检查的唯一标识符
     * @return 快照存在且可下载时为 {@code true}
     * @throws Exception 检查失败时抛出
     */
    boolean exists(String snapshotId) throws Exception;
}
