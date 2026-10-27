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
package io.agentscope.extensions.cos;

import com.qcloud.cos.COSClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * 腾讯云 COS快照存储的便捷 {@link SandboxSnapshotSpec}。
 */
public class CosSnapshotSpec extends RemoteSnapshotSpec {

    /**
     * 从现有 COS 客户端创建快照配置。
     * @param cosClient 已初始化的 COS 客户端
     * @param bucketName 目标存储桶
     * @param keyPrefix 可选键前缀，可为 null 或空白
     */
    public CosSnapshotSpec(COSClient cosClient, String bucketName, String keyPrefix) {
        super(new CosRemoteSnapshotClient(cosClient, bucketName, keyPrefix));
    }
}
