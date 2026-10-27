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
package io.agentscope.extensions.mongodb;

/**
 * MongoDB 分布式存储组件的默认数据库和集合名称。
 *
 * <p>集中定义，使四个组件类共享同一事实来源，避免不同构造路径引用同一集合名时发生偏差。
 */
public final class MongoConstants {

    private MongoConstants() {}

    /**
 * 未显式提供名称时使用的默认数据库名。
 */
    public static final String DEFAULT_DATABASE = "agentscope";

    /**
 * 保存智能体会话状态文档的集合（{@code MongoAgentStateStore}）。
 */
    public static final String SESSIONS_COLLECTION = "agentscope_sessions";

    /**
 * 保存工作区文件系统键值条目的集合（{@code MongoBaseStore}）。
 */
    public static final String BASE_STORE_COLLECTION = "agentscope_base";

    /**
     * 保存沙箱快照的 GridFS 存储桶及旧版单文档集合（{@code MongoRemoteSnapshotClient}）。
     */
    public static final String SNAPSHOTS_COLLECTION = "agentscope_snapshots";

    /**
 * 保存沙箱执行租约锁的集合（{@code MongoSandboxExecutionGuard}）。
 */
    public static final String SANDBOX_LOCKS_COLLECTION = "agentscope_sandbox_locks";
}
