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
package io.agentscope.core.tool;

import java.util.List;

/**
 * 工具自检使用的危险路径和危险命令列表默认值。
 *
 * <p>保护敏感配置文件（Shell rc 文件、SSH 配置、凭据）并防范破坏性 Shell 命令。工具覆写 {@link ToolBase} 字段时会整体替换默认值，而不是追加。
 */
public final class ToolDangerousPathConstants {

    private ToolDangerousPathConstants() {}

    /**
     * 绝不应自动编辑的敏感文件。
     *
     * <p>包括 Shell rc 文件、SSH 配置、凭据存储和常存密钥的 {@code .env} 变体，与 Python {@code tool/_constants.py} 对齐。
     */
    public static final List<String> DEFAULT_DANGEROUS_FILES =
            List.of(
                    ".gitconfig",
                    ".gitmodules",
                    ".bashrc",
                    ".bash_profile",
                    ".zshrc",
                    ".zprofile",
                    ".profile",
                    ".ssh/config",
                    ".ssh/authorized_keys",
                    ".netrc",
                    ".npmrc",
                    ".pypirc",
                    ".env",
                    ".envrc",
                    ".env.local",
                    ".env.development",
                    ".env.development.local",
                    ".env.test",
                    ".env.test.local",
                    ".env.staging",
                    ".env.production",
                    ".env.production.local");

    /**
     * 路径任意位置出现即标记为敏感的目录名。
     */
    public static final List<String> DEFAULT_DANGEROUS_DIRECTORIES =
            List.of(".git", ".vscode", ".idea", ".ssh");

    /**
     * 始终需要用户明确批准的 Shell 命令片段。
     */
    public static final List<String> DANGEROUS_COMMANDS =
            List.of(
                    "rm -rf",
                    "sudo rm",
                    "dd",
                    "mkfs",
                    "fdisk",
                    "format",
                    "chmod 777",
                    "chmod -R 777",
                    "chown -R",
                    "kill -9",
                    "> /dev/");
}
