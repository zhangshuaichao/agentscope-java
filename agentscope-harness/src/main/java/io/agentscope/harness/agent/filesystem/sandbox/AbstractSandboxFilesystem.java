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
package io.agentscope.harness.agent.filesystem.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;

/**
 * 增加 Shell 命令执行能力的文件系统抽象（面向沙箱或远程宿主）。
 *
 * <p>在 {@link AbstractFilesystem} 基础上增加 {@link #execute} 和 {@link #id()}。
 *
 * <p>【学习提示】这是能力接口，不是隔离机制本身。命令在哪个环境执行、能访问哪些资源，仍取决于具体后端及其权限和隔离配置。
 */
public interface AbstractSandboxFilesystem extends AbstractFilesystem {

    /**
     * 此文件系统／沙箱实例的唯一标识符。
     *
     * @return ID 字符串
     */
    String id();

    /**
     * 在此文件系统所依托的环境中执行 Shell 命令。
     *
     * @param runtimeContext 每次调用的智能体上下文；不可用时可为 {@code null}
     * @param command 要执行的完整 Shell 命令字符串
     * @param timeoutSeconds 等待命令完成的最长秒数；{@code null} 表示使用文件系统默认超时
     * @return 包含合并输出、退出码和截断标记的 ExecuteResponse；不成功的响应（参见 {@link ExecuteResponse#isSuccess()}）表示命令未成功完成，此时不得将 {@code output} 当作正常命令输出解析
     */
    ExecuteResponse execute(RuntimeContext runtimeContext, String command, Integer timeoutSeconds);
}
