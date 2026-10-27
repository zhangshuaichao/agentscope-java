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
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;

/**
 * 基于腾讯云 COS的 {@link DistributedStore}。
 *
 * <p>用法：
 * <pre>{@code
 * COSClient cosClient = new COSClient(cred, clientConfig);
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(CosDistributedStore.create(cosClient, "my-bucket", "agentscope/"))
 *     .build();
 * }</pre>
 */
public class CosDistributedStore implements DistributedStore {

    private final COSClient cosClient;
    private final String bucketName;
    private final String keyPrefix;

    private CosDistributedStore(COSClient cosClient, String bucketName, String keyPrefix) {
        this.cosClient = Objects.requireNonNull(cosClient, "cosClient");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        this.keyPrefix = keyPrefix != null ? keyPrefix : "agentscope/";
    }

    /**
     * 创建 COS 分布式存储。
     * @param cosClient 已初始化的 COS 客户端
     * @param bucketName 目标存储桶名称
     * @param keyPrefix 对象键前缀，例如 {@code "agentscope/"}
     * @return 新建的 COS 分布式存储
     */
    public static CosDistributedStore create(
            COSClient cosClient, String bucketName, String keyPrefix) {
        return new CosDistributedStore(cosClient, bucketName, keyPrefix);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return CosAgentStateStore.builder()
                .cosClient(cosClient)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "state/")
                .build();
    }

    @Override
    public BaseStore baseStore() {
        return CosBaseStore.builder()
                .cosClient(cosClient)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "store/")
                .build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new CosSnapshotSpec(cosClient, bucketName, keyPrefix + "snapshot/");
    }
}
