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
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;

/**
 * 基于阿里云 OSS的 {@link DistributedStore}。
 *
 * <p>用法：
 * <pre>{@code
 * OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(OssDistributedStore.create(ossClient, "my-bucket", "agentscope/"))
 *     .build();
 * }</pre>
 */
public class OssDistributedStore implements DistributedStore {

    private final OSS ossClient;
    private final String bucketName;
    private final String keyPrefix;

    private OssDistributedStore(OSS ossClient, String bucketName, String keyPrefix) {
        this.ossClient = Objects.requireNonNull(ossClient, "ossClient");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        this.keyPrefix = keyPrefix != null ? keyPrefix : "agentscope/";
    }

    /**
     * 创建 OSS 分布式存储。
     * @param ossClient 已初始化的 OSS 客户端
     * @param bucketName 目标存储桶名称
     * @param keyPrefix 对象键前缀，例如 {@code "agentscope/"}
     * @return 新建的 OSS 分布式存储
     */
    public static OssDistributedStore create(OSS ossClient, String bucketName, String keyPrefix) {
        return new OssDistributedStore(ossClient, bucketName, keyPrefix);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return OssAgentStateStore.builder()
                .ossClient(ossClient)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "state/")
                .build();
    }

    @Override
    public BaseStore baseStore() {
        return OssBaseStore.builder()
                .ossClient(ossClient)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "store/")
                .build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new OssSnapshotSpec(ossClient, bucketName, keyPrefix + "snapshot/");
    }
}
