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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import java.io.InputStream;

/**
 * 带隔离工作区的活动沙箱。
 *
 * <p>生命周期：
 * <ol>
 *   <li>通过 {@link SandboxClient#create}（新建）或 {@link SandboxClient#resume}（已有）获取
 *   <li>调用 {@link #start()}，初始化或恢复工作区
 *   <li>使用 {@link #exec} 执行命令，使用 {@link #persistWorkspace}/{@link #hydrateWorkspace} 操作归档
 *   <li>调用 {@link #stop()}，持久化快照（不销毁资源）
 *   <li>调用 {@link #shutdown()}，销毁后端资源（临时目录、容器）
 *   <li>或使用 {@link #close()}，依次调用 stop 和 shutdown
 * </ol>
 *
 * <p>必须区分 {@code stop()} 与 {@code shutdown()}：
 * <ul>
 *   <li>{@code stop()}：只持久化快照，适用于自行管理和用户管理的沙箱</li>
 *   <li>{@code shutdown()}：销毁后端资源，仅应对自行管理的沙箱调用</li>
 * </ul>
 *
 * <p>【学习提示】上述列表描述方法职责，不代表管理器对每种所有权都会调用这些方法。当前 SandboxManager.release 对用户管理的沙箱直接返回，不执行停止、快照或关闭；具体隔离强度也取决于后端配置。
 */
public interface Sandbox extends AutoCloseable {

    void start() throws Exception;

    void stop() throws Exception;

    default void shutdown() throws Exception {
        // 默认不执行操作
    }

    @Override
    void close() throws Exception;

    boolean isRunning();

    /**
     * 返回此沙箱当前可序列化状态。
     *
     * @return 状态（生命周期方法可能修改它）
     */
    SandboxState getState();

    /**
     * 在沙箱工作区运行 Shell 命令。
     *
     * @param runtimeContext 每次调用的智能体上下文（会话、用户、属性）；可为 {@code null}
     * @param command Shell 命令
     * @param timeoutSeconds 最大等待秒数；{@code null} 使用实现默认值。{@code 0} 的含义依实现而异：可能表示无截止时间（E2B），也可能表示立即超时（Docker）
     */
    ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
            throws Exception;

    InputStream persistWorkspace() throws Exception;

    void hydrateWorkspace(InputStream archive) throws Exception;
}
