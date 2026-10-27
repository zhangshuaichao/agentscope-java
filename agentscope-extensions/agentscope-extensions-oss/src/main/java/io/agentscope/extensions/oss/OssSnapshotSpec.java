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
package io.agentscope.extensions.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * 阿里云 OSS快照存储的便捷 {@link SandboxSnapshotSpec}。
 */
public class OssSnapshotSpec extends RemoteSnapshotSpec {

    /**
     * 从现有 OSS 客户端创建快照配置。
     * @param ossClient 已初始化的 OSS 客户端
     * @param bucketName 目标存储桶
     * @param keyPrefix 可选键前缀，可为 null 或空白
     */
    public OssSnapshotSpec(OSS ossClient, String bucketName, String keyPrefix) {
        super(new OssRemoteSnapshotClient(ossClient, bucketName, keyPrefix));
    }

    /**
     * 从端点与凭据配置创建 OSS 快照配置。
     * @param endpoint OSS 端点，例如 oss-cn-hangzhou.aliyuncs.com
     * @param accessKeyId 访问密钥 ID
     * @param accessKeySecret 访问密钥秘密值
     * @param bucketName 目标存储桶
     * @param keyPrefix 可选键前缀，可为 null 或空白
     */
    public OssSnapshotSpec(
            String endpoint,
            String accessKeyId,
            String accessKeySecret,
            String bucketName,
            String keyPrefix) {
        this(
                new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret),
                bucketName,
                keyPrefix);
    }
}
