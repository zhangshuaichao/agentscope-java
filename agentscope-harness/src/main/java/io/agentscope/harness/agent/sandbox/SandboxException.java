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

import java.nio.file.Path;

/**
 * 所有沙箱操作的异常基类。
 *
 * <p>通过静态嵌套类提供常见失败模式的专用子类：
 * {@link SandboxConfigurationException}、{@link SandboxRuntimeException}、
 * {@link WorkspaceStartException}、{@link WorkspaceStopException}、
 * {@link ExecException}、{@link ExecTimeoutException}、{@link SnapshotException}。
 */
public class SandboxException extends RuntimeException {

    private final SandboxErrorCode errorCode;
    private final String op;

    /**
     * 创建带错误码和消息的沙箱异常。
     *
     * @param errorCode 对失败分类的错误码
     * @param message 人类可读的描述
     */
    public SandboxException(SandboxErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.op = null;
    }

    /**
     * 创建带错误码、消息和原因的沙箱异常。
     *
     * @param errorCode 对失败分类的错误码
     * @param message 人类可读的描述
     * @param cause 底层原因
     */
    public SandboxException(SandboxErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.op = null;
    }

    /**
     * 创建带错误码、操作名、消息和原因的沙箱异常。
     *
     * @param errorCode 对失败分类的错误码
     * @param op 失败的操作（例如 "start"、"exec"、"persist"）
     * @param message 人类可读的描述
     * @param cause 底层原因
     */
    public SandboxException(
            SandboxErrorCode errorCode, String op, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.op = op;
    }

    /**
     * 返回对此失败分类的错误码。
     *
     * @return 错误码
     */
    public SandboxErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 返回失败的操作名；未指定时为 {@code null}。
     *
     * @return 操作名，可为 null
     */
    public String getOp() {
        return op;
    }

    // ---- 专用子类 ----

    /**
     * 沙箱配置无效或缺失时抛出（例如未配置客户端）。
     */
    public static class SandboxConfigurationException extends SandboxException {

        /**
         * 创建配置异常。
         *
         * @param message 配置问题描述
         */
        public SandboxConfigurationException(String message) {
            super(SandboxErrorCode.CONFIGURATION_ERROR, message);
        }

        /**
         * 创建带原因的配置异常。
         *
         * @param message 配置问题描述
         * @param cause 底层原因
         */
        public SandboxConfigurationException(String message, Throwable cause) {
            super(SandboxErrorCode.CONFIGURATION_ERROR, message, cause);
        }
    }

    /**
     * 没有更具体子类覆盖的一般沙箱运行时失败。
     */
    public static class SandboxRuntimeException extends SandboxException {

        /**
         * 创建带错误码和消息的运行时异常。
         *
         * @param errorCode 对失败分类的错误码
         * @param message 人类可读的描述
         */
        public SandboxRuntimeException(SandboxErrorCode errorCode, String message) {
            super(errorCode, message);
        }

        /**
         * 创建带错误码、消息和原因的运行时异常。
         *
         * @param errorCode 对失败分类的错误码
         * @param message 人类可读的描述
         * @param cause 底层原因
         */
        public SandboxRuntimeException(
                SandboxErrorCode errorCode, String message, Throwable cause) {
            super(errorCode, message, cause);
        }

        /**
         * 创建带消息和原因的运行时异常。
         *
         * @param message 人类可读的描述
         * @param cause 底层原因
         */
        public SandboxRuntimeException(String message, Throwable cause) {
            super(SandboxErrorCode.WORKSPACE_START_ERROR, message, cause);
        }
    }

    /**
     * 工作区后端启动失败时抛出。
     */
    public static class WorkspaceStartException extends SandboxException {

        private final Path path;

        /**
         * 创建工作区启动异常。
         *
         * @param path 无法启动的工作区路径
         * @param cause 底层原因
         */
        public WorkspaceStartException(Path path, Throwable cause) {
            super(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "Failed to start workspace at: " + path,
                    cause);
            this.path = path;
        }

        /**
         * 返回启动失败的工作区路径。
         *
         * @return 工作区路径
         */
        public Path getPath() {
            return path;
        }
    }

    /**
     * 工作区后端停止失败时抛出。
     */
    public static class WorkspaceStopException extends SandboxException {

        private final Path path;

        /**
         * 创建工作区停止异常。
         *
         * @param path 无法停止的工作区路径
         * @param cause 底层原因
         */
        public WorkspaceStopException(Path path, Throwable cause) {
            super(
                    SandboxErrorCode.WORKSPACE_STOP_ERROR,
                    "Failed to stop workspace at: " + path,
                    cause);
            this.path = path;
        }

        /**
         * 返回停止失败的工作区路径。
         *
         * @return 工作区路径
         */
        public Path getPath() {
            return path;
        }
    }

    /**
     * 沙箱命令以非零退出码结束时抛出。
     */
    public static class ExecException extends SandboxException {

        private final int exitCode;
        private final String stdout;
        private final String stderr;

        /**
         * 创建执行异常。
         *
         * @param exitCode 进程退出码
         * @param stdout 捕获的标准输出
         * @param stderr 捕获的标准错误
         */
        public ExecException(int exitCode, String stdout, String stderr) {
            super(
                    SandboxErrorCode.EXEC_NONZERO,
                    "Command exited with code " + exitCode + ": " + stderr);
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        /**
         * 返回进程退出码。
         *
         * @return 退出码
         */
        public int getExitCode() {
            return exitCode;
        }

        /**
         * 返回捕获的标准输出。
         *
         * @return 标准输出
         */
        public String getStdout() {
            return stdout;
        }

        /**
         * 返回捕获的标准错误。
         *
         * @return 标准错误
         */
        public String getStderr() {
            return stderr;
        }
    }

    /**
     * 沙箱命令超时时抛出。
     *
     * <p>消息只携带超时时长，不包含命令文本：命令由模型生成，可能内嵌秘密信息，而调用方在自己的上下文中已持有命令。这与官方 E2B SDK 的固定超时措辞一致。
     */
    public static class ExecTimeoutException extends SandboxException {

        /**
         * 创建执行超时异常。
         *
         * @param command 超时的命令（为保持签名兼容而保留，不包含在消息中）
         * @param timeoutSeconds 被超过的超时时长
         */
        public ExecTimeoutException(String command, int timeoutSeconds) {
            super(SandboxErrorCode.EXEC_TIMEOUT, "Command timed out after " + timeoutSeconds + "s");
        }
    }

    /**
     * 快照操作（持久化或恢复）失败时抛出。
     */
    public static class SnapshotException extends SandboxException {

        private final String snapshotId;

        /**
         * 创建快照异常。
         *
         * @param snapshotId 快照标识符
         * @param message 人类可读的描述
         * @param cause 底层原因
         */
        public SnapshotException(String snapshotId, String message, Throwable cause) {
            super(SandboxErrorCode.SNAPSHOT_PERSIST_ERROR, message, cause);
            this.snapshotId = snapshotId;
        }

        /**
         * 为不可恢复的快照创建快照异常。
         *
         * @param snapshotId 快照标识符
         */
        public SnapshotException(String snapshotId) {
            super(
                    SandboxErrorCode.SNAPSHOT_NOT_RESTORABLE,
                    "Snapshot is not restorable: " + snapshotId);
            this.snapshotId = snapshotId;
        }

        /**
         * 返回与此失败关联的快照标识符。
         *
         * @return 快照 ID
         */
        public String getSnapshotId() {
            return snapshotId;
        }
    }
}
