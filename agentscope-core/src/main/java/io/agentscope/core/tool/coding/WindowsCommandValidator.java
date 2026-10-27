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
 * Windows 系统的命令验证器。
 *
 * <p><b>验证顺序：</b>
 * <ol>
 * <li>提取可执行程序（移除引号、处理带空格路径、移除目录与扩展名、转换为小写）</li>
 * <li>未配置白名单时允许（向后兼容）</li>
 * <li>检测到多命令分隔符时拒绝</li>
 * <li>以 {@code .\} 或 {@code ./} 开头的相对路径逃逸当前目录时拒绝</li>
 * <li>忽略大小写检查白名单，不匹配时拒绝</li>
 * </ol>
 *
 * <p><b>多命令检测：</b>检测 Windows 分隔符 {@code &}、{@code &&}、{@code |}、{@code ||} 和换行，
 * 忽略引号内或转义符 {@code ^} 后的分隔符。
 * <br>注意：分号 {@code ;} 在 Windows cmd.exe 中不是分隔符。
 *
 * <p><b>可执行程序提取：</b>
 * <ul>
 * <li>处理双引号包围的命令</li>
 * <li>处理带空格路径，例如 {@code C:\Program Files\app.exe}</li>
 * <li>提取不含目录的命令名，同时处理 {@code \} 和 {@code /}</li>
 * <li>忽略大小写移除 {@code .exe}、{@code .bat}、{@code .cmd} 扩展名</li>
 * <li>将结果转为小写以保持匹配一致</li>
 * </ul>
 */
public class WindowsCommandValidator implements CommandValidator {

    private static final Logger logger = LoggerFactory.getLogger(WindowsCommandValidator.class);

    @Override
    public ValidationResult validate(String command, Set<String> allowedCommands) {
        // 提取并检查可执行程序（Windows 忽略大小写）
        String executable = extractExecutable(command);

        // 未配置白名单时允许全部命令（向后兼容）
        if (allowedCommands == null || allowedCommands.isEmpty()) {
            return ValidationResult.allowed(executable);
        }

        // 检查是否包含多个命令
        if (containsMultipleCommands(command)) {
            logger.debug("Command contains multiple command separators: {}", command);
            return ValidationResult.rejected(
                    "Command contains multiple command separators (&, |, newline)",
                    extractExecutable(command));
        }

        if (executable.startsWith(".\\") || executable.startsWith("./")) {
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

        // 检查是否匹配任意白名单条目（忽略大小写）
        boolean inWhitelist =
                allowedCommands.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(executable));
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

            // 处理带引号命令（Windows 通常使用双引号）
            if (trimmed.startsWith("\"")) {
                int endQuote = trimmed.indexOf('"', 1);
                if (endQuote > 0) {
                    executable = trimmed.substring(1, endQuote);
                } else {
                    // 没有闭合引号，按无引号处理
                    executable = extractFirstToken(trimmed);
                }
            } else {
                // 检查是否像路径，即含反斜杠或正斜杠
                if (trimmed.contains("\\") || trimmed.contains("/")) {
                    // 属于路径，需要提取参数前的完整路径
                    executable = extractPathFromCommand(trimmed);
                } else {
                    // 无路径的简单命令，提取首个词元
                    executable = extractFirstToken(trimmed);
                }
            }

            // 忽略大小写移除 .exe、.bat、.cmd 扩展名
            String lowerExecutable = executable.toLowerCase();
            if (lowerExecutable.endsWith(".exe")
                    || lowerExecutable.endsWith(".bat")
                    || lowerExecutable.endsWith(".cmd")) {
                int dotIndex = lowerExecutable.lastIndexOf('.');
                if (dotIndex > 0) {
                    executable = lowerExecutable.substring(0, dotIndex);
                }
            }

            return executable;
        } catch (Exception e) {
            logger.warn("Failed to parse command '{}': {}", command, e.getMessage());
            return "";
        }
    }

    /**
     * 从命令提取首个词元，即空格或制表符前的单词。
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
     * 从包含路径分隔符的命令提取路径，支持 "C:\Program Files\app.exe arg1" 等带空格路径。
     */
    private String extractPathFromCommand(String command) {
        // 查找常见可执行扩展名以确定路径结束位置
        String lowerCommand = command.toLowerCase();
        int exePos = lowerCommand.indexOf(".exe");
        int batPos = lowerCommand.indexOf(".bat");
        int cmdPos = lowerCommand.indexOf(".cmd");

        // 查找任意扩展名首次出现的位置
        int extPos = -1;
        if (exePos >= 0) extPos = exePos;
        if (batPos >= 0 && (extPos < 0 || batPos < extPos)) extPos = batPos;
        if (cmdPos >= 0 && (extPos < 0 || cmdPos < extPos)) extPos = cmdPos;

        if (extPos >= 0) {
            // 找到扩展名，提取至扩展名末尾
            int endPos = extPos + 4; // .exe、.bat、.cmd 均为 4 个字符
            // 检查扩展名后是否为空格或字符串结尾
            if (endPos >= command.length()
                    || command.charAt(endPos) == ' '
                    || command.charAt(endPos) == '\t') {
                return command.substring(0, endPos);
            }
        }

        // 未找到扩展名，或扩展名后不是空格
        // 回退到提取首个词元
        return extractFirstToken(command);
    }

    /**
     * 检查引号外是否存在多命令分隔符。
     *
     * <p>检测 &amp;、| 和换行，忽略引号内或 ^ 转义后的分隔符。
     * <p>注意：分号在 Windows cmd.exe 中不是分隔符。
     *
     * @param command 待检查命令
     * @return 检测到多命令时为 true，否则为 false
     */
    @Override
    public boolean containsMultipleCommands(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            // 处理转义序列（cmd.exe 的转义符是 ^）
            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '^') {
                escaped = true;
                continue;
            }

            // 跟踪引号状态（Windows cmd.exe 主要使用双引号）
            if (c == '"') {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            // 只检查引号外的分隔符
            if (!inDoubleQuote) {
                // 检查命令分隔符（&、|、换行）
                // 注意：分号在 Windows cmd.exe 中不是分隔符
                if (c == '&' || c == '|' || c == '\n') {
                    return true;
                }
            }
        }

        return false;
    }
}
