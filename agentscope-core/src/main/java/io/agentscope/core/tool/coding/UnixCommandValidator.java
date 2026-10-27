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
package io.agentscope.core.tool.coding;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unix 类系统（Linux、macOS）的命令验证器。
 *
 * <p><b>验证顺序：</b>
 * <ol>
 * <li>提取可执行程序（移除引号、提取首个词元、移除扩展名）</li>
 * <li>未配置白名单时允许（向后兼容）</li>
 * <li>检测到多命令分隔符时拒绝</li>
 * <li>以 {@code ./} 开头的相对路径逃逸当前目录时拒绝</li>
 * <li>不在白名单中时拒绝</li>
 * </ol>
 *
 * <p><b>多命令检测：</b>检测 Unix 特有分隔符 {@code &}、{@code &&}、{@code |}、{@code ||}、{@code ;} 和换行，
 * 忽略引号内或转义符 {@code \} 后的分隔符。
 *
 * <p><b>可执行程序提取：</b>
 * <ul>
 * <li>处理单引号或双引号包围的命令</li>
 * <li>提取空格或制表符前的首个词元</li>
 * <li>移除脚本扩展名：{@code .sh}、{@code .py}、{@code .rb}、{@code .pl}、{@code .bash}、{@code .zsh}</li>
 * </ul>
 */
public class UnixCommandValidator implements CommandValidator {

    private static final Logger logger = LoggerFactory.getLogger(UnixCommandValidator.class);

    @Override
    public ValidationResult validate(String command, Set<String> allowedCommands) {
        // 提取并检查可执行程序
        String executable = extractExecutable(command);

        // 未配置白名单时允许全部命令（向后兼容）
        if (allowedCommands == null || allowedCommands.isEmpty()) {
            return ValidationResult.allowed(executable);
        }

        // 检查是否包含多个命令
        if (containsMultipleCommands(command)) {
            logger.debug("Command contains multiple command separators: {}", command);
            return ValidationResult.rejected(
                    "Command contains multiple command separators (&, |, ;, newline)",
                    extractExecutable(command));
        }

        // 相对路径可执行程序仅在当前目录范围内才允许执行
        if (executable.startsWith("./")) {
            if (isPathWithinCurrentDirectory(executable)) {
                logger.debug(
                        "Command '{}' is safe relative path executable file execution", executable);
                return ValidationResult.allowed(executable);
            } else {
                logger.debug(
                        "Command '{}' is not safe relative path executable file execution",
                        executable);
                return ValidationResult.rejected(
                        "Command '" + executable + "' escapes current directory", executable);
            }
        }

        boolean inWhitelist = allowedCommands.contains(executable);
        if (inWhitelist) {
            logger.debug("Command '{}' is in whitelist", executable);
            return ValidationResult.allowed(executable);
        } else {
            logger.debug("Command '{}' is NOT in whitelist", executable);
            return ValidationResult.rejected(
                    "Command '" + executable + "' is not in the allowed whitelist", executable);
        }
    }

    @Override
    public String extractExecutable(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "";
        }

        try {
            String trimmed = command.trim();
            String executable;

            // 处理带引号命令：单引号或双引号包围
            if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
                char quote = trimmed.charAt(0);
                int endQuote = trimmed.indexOf(quote, 1);
                if (endQuote > 0) {
                    executable = trimmed.substring(1, endQuote);
                } else {
                    // 没有闭合引号，按无引号处理
                    executable = extractFirstToken(trimmed);
                }
            } else {
                // 提取首个词元
                executable = extractFirstToken(trimmed);
            }

            // 移除常见脚本扩展名（.sh、.py、.rb、.pl 等）
            // 保证与白名单匹配一致
            if (executable.endsWith(".sh")
                    || executable.endsWith(".py")
                    || executable.endsWith(".rb")
                    || executable.endsWith(".pl")
                    || executable.endsWith(".bash")
                    || executable.endsWith(".zsh")) {
                int dotIndex = executable.lastIndexOf('.');
                if (dotIndex > 0) {
                    executable = executable.substring(0, dotIndex);
                }
            }

            return executable;
        } catch (Exception e) {
            logger.warn("Failed to parse command '{}': {}", command, e.getMessage());
            return "";
        }
    }

    /**
     * 从命令字符串提取首个词元。
     *
     * @param command 命令字符串
     * @return 首个词元
     */
    private String extractFirstToken(String command) {
        int spaceIndex = command.indexOf(' ');
        int tabIndex = command.indexOf('\t');
        int splitIndex = -1;

        if (spaceIndex >= 0 && tabIndex >= 0) {
            splitIndex = Math.min(spaceIndex, tabIndex);
        } else if (spaceIndex >= 0) {
            splitIndex = spaceIndex;
        } else if (tabIndex >= 0) {
            splitIndex = tabIndex;
        }

        return splitIndex > 0 ? command.substring(0, splitIndex) : command;
    }

    /**
     * 检查引号外是否包含多命令分隔符。
     *
     * <p>检测 &amp;、|、; 和换行，忽略引号内或反斜杠转义后的分隔符。
     *
     * @param command 待检查命令
     * @return 检测到多命令时为 true，否则为 false
     */
    @Override
    public boolean containsMultipleCommands(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            // 处理转义序列
            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            // 跟踪引号状态
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            // 只检查引号外的分隔符
            if (!inSingleQuote && !inDoubleQuote) {
                // 检查命令分隔符
                if (c == '&' || c == '|' || c == ';' || c == '\n') {
                    return true;
                }
            }
        }

        return false;
    }
}
