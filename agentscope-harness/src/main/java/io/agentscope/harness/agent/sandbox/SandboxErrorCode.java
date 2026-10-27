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

/**
 * 沙箱操作错误码，供 {@link SandboxException} 及其子类使用。
 */
public enum SandboxErrorCode {

    /**
 * 命令以非零退出码结束。
 */
    EXEC_NONZERO,

    /**
 * 命令执行超时。
 */
    EXEC_TIMEOUT,

    /**
 * 工作区存储启动失败。
 */
    WORKSPACE_START_ERROR,

    /**
 * 工作区存储停止／持久化失败。
 */
    WORKSPACE_STOP_ERROR,

    /**
 * 沙箱连接关闭后仍尝试执行操作。
 */
    SANDBOX_CONNECTION_CLOSED,

    /**
 * 读取或解析工作区 tar 归档失败。
 */
    WORKSPACE_ARCHIVE_READ_ERROR,

    /**
 * 创建工作区 tar 归档失败。
 */
    WORKSPACE_ARCHIVE_WRITE_ERROR,

    /**
 * 快照持久化失败。
 */
    SNAPSHOT_PERSIST_ERROR,

    /**
 * 快照恢复失败。
 */
    SNAPSHOT_RESTORE_ERROR,

    /**
 * 快照不存在或无法恢复。
 */
    SNAPSHOT_NOT_RESTORABLE,

    /**
 * 清单条目包含无效或不安全的路径。
 */
    INVALID_MANIFEST_PATH,

    /**
 * 沙箱配置无效或缺失。
 */
    CONFIGURATION_ERROR
}
