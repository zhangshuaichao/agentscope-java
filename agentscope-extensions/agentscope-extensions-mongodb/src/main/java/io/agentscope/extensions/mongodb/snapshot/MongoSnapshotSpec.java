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
package io.agentscope.extensions.mongodb.snapshot;

import com.mongodb.client.MongoClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;

/**
 * 用于 MongoDB 快照存储的便捷 {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec}。
 *
 * <p>将沙箱工作区 tar 归档保存到 MongoDB GridFS，默认按 255 KiB 分块，避免单 BSON 文档大小限制。容量约束见 MongoRemoteSnapshotClient 的说明。
 */
public class MongoSnapshotSpec extends RemoteSnapshotSpec {

    public MongoSnapshotSpec(MongoClient mongoClient, String databaseName) {
        super(new MongoRemoteSnapshotClient(mongoClient, databaseName, null));
    }

    public MongoSnapshotSpec(MongoClient mongoClient, String databaseName, String collectionName) {
        super(new MongoRemoteSnapshotClient(mongoClient, databaseName, collectionName));
    }
}
