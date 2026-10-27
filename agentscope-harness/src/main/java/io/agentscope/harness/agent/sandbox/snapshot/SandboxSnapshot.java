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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.InputStream;

/**
 * 表示沙箱工作区的持久化快照。
 *
 * <p>快照可跨会话停止保留工作区状态，并在后续会话启动时恢复。实现决定快照的存储位置和方式：本地磁盘（{@link LocalSandboxSnapshot}）、远程存储（{@link RemoteSandboxSnapshot}），或完全不保存（{@link NoopSandboxSnapshot}）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = NoopSandboxSnapshot.class, name = "noop"),
    @JsonSubTypes.Type(value = LocalSandboxSnapshot.class, name = "local"),
    @JsonSubTypes.Type(value = RemoteSandboxSnapshot.class, name = "remote"),
})
public interface SandboxSnapshot {

    /**
     * 将工作区归档持久保存到此快照。
     *
     * @param workspaceArchive 要持久保存的工作区 tar 流；调用方负责在调用后关闭流
     * @throws Exception 持久化操作失败时抛出
     */
    void persist(InputStream workspaceArchive) throws Exception;

    /**
     * 从此快照恢复工作区归档。
     *
     * @return 工作区 tar 流，调用方负责关闭
     * @throws Exception 恢复操作失败或快照不可恢复时抛出
     */
    InputStream restore() throws Exception;

    /**
     * 返回此快照当前是否可恢复。
     *
     * @return {@link #restore()} 能成功时为 true
     * @throws Exception 检查可恢复性失败时抛出
     */
    @JsonIgnore
    boolean isRestorable() throws Exception;

    /**
     * 返回此快照的唯一标识符。
     *
     * @return 快照 ID
     */
    String getId();

    /**
     * 返回 JSON 序列化使用的快照类型判别标记。
     *
     * @return 类型字符串（例如 "noop"、"local"、"remote"）
     */
    @JsonIgnore
    String getType();

    /**
     * 返回此快照是否实际持久保存数据。
     *
     * <p>为 {@code false} 时，{@link io.agentscope.harness.agent.sandbox.AbstractBaseSandbox#stop()} 完全跳过可能昂贵的工作区归档步骤。默认为 {@code true}。
     *
     * @return 仅丢弃全部归档数据的空操作实现返回 false
     */
    @JsonIgnore
    default boolean isPersistenceEnabled() {
        return true;
    }
}
