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

/**
 * 执行前验证 Shell 命令的接口。
 *
 * <p>【学习提示】这是基于命令文本和可执行程序白名单的检查，不是完整 Shell 语法解析器，
 * 也不是操作系统沙箱。尤其要注意白名单为空的兼容分支会直接放行，不能把“未配置”理解为“全部禁止”。
 * 执行权限、进程隔离和文件系统访问边界需要由应用及运行环境另行保证。
 *
 * <p><b>验证流程：</b>
 * <ol>
 * <li><b>提取可执行程序：</b>解析命令，提取可执行程序名</li>
 * <li><b>白名单检查：</b>白名单为空或 null 时全部允许（向后兼容）</li>
 * <li><b>多命令检测：</b>包含多个命令分隔符时拒绝</li>
 * <li><b>相对路径安全：</b>以 {@code ./} 或 {@code .\} 开头时，验证路径不逃逸当前目录</li>
 * <li><b>白名单验证：</b>可执行程序不在白名单中时拒绝</li>
 * </ol>
 *
 * <p>内置实现：
 * <ul>
 * <li>{@link UnixCommandValidator}：Unix/Linux/macOS 系统</li>
 * <li>{@link WindowsCommandValidator}：Windows 系统</li>
 * </ul>
 *
 * @see UnixCommandValidator
 * @see WindowsCommandValidator
 * @see ShellCommandTool
 */
public interface CommandValidator {

    /**
     * 验证命令是否允许执行。
     *
     * <p>依次进行以下检查：
     * <ol>
     * <li>提取可执行程序名</li>
     * <li>白名单为 null 或空时允许（向后兼容）</li>
     * <li>检测到多命令分隔符时拒绝</li>
     * <li>相对路径逃逸当前目录时拒绝</li>
     * <li>可执行程序不在白名单中时拒绝</li>
     * </ol>
     *
     * @param command 待验证命令字符串
     * @param allowedCommands 允许的可执行程序集合（null 或空表示全部允许）
     * @return 包含验证结果的 ValidationResult
     */
    ValidationResult validate(String command, Set<String> allowedCommands);

    /**
     * 从命令字符串提取可执行程序名。
     *
     * <p>提取过程：
     * <ul>
     * <li>移除外围引号（若存在）</li>
     * <li>提取空格或制表符前的首个词元</li>
     * <li>移除目录路径（平台相关）</li>
     * <li>移除文件扩展名（平台相关）</li>
     * </ul>
     *
     * @param command 命令字符串
     * @return 可执行程序名；提取失败时为空字符串
     */
    String extractExecutable(String command);

    /**
     * 检查命令是否包含多个命令分隔符。
     *
     * <p>采用平台特定检测：
     * <ul>
     * <li>Unix：{@code &}、{@code |}、{@code ;}、换行（转义符：{@code \}）</li>
     * <li>Windows：{@code &}、{@code |}、换行（转义符：{@code ^}）</li>
     * </ul>
     * <p>忽略引号内的分隔符。
     *
     * @param command 命令字符串
     * @return 检测到多命令时为 true，否则为 false
     */
    boolean containsMultipleCommands(String command);

    /**
     * 验证相对路径（以 ./ 或 .\ 开头）是否保持在当前目录内。
     *
     * <p><b>算法：</b>通过深度跟踪检测目录遍历：
     * <ol>
     * <li>规范化路径分隔符（{@code \} → {@code /}）</li>
     * <li>移除开头的 {@code ./}</li>
     * <li>按 {@code /} 拆分片段</li>
     * <li>跟踪深度：{@code ..} 减一，普通目录加一</li>
     * <li>任意时刻深度 &lt; 0，表示路径逃逸当前目录</li>
     * </ol>
     *
     * <p><b>示例：</b>
     * <ul>
     * <li>{@code ./script.sh}：允许，深度 0→1</li>
     * <li>{@code ./subdir/script.sh}：允许，深度 0→1→2</li>
     * <li>{@code ./a/b/../c/script.sh}：允许，深度 0→1→2→1→2</li>
     * <li>{@code ./../script.sh}：拒绝，深度 0→-1</li>
     * <li>{@code ./../../script.sh}：拒绝，深度 0→-1→-2</li>
     * </ul>
     *
     * <p>同时支持 Unix（{@code /}）和 Windows（{@code \}）路径分隔符。
     *
     * @param path 待验证路径
     * @return 保持在当前目录内为 true，逃逸则为 false
     */
    default boolean isPathWithinCurrentDirectory(String path) {
        // 规范化路径：将反斜杠替换为正斜杠
        String normalizedPath = path.replace('\\', '/');

        // 移除开头的 ./
        normalizedPath = normalizedPath.substring(2);

        // 按 / 拆分并处理每个片段
        String[] segments = normalizedPath.split("/");
        int depth = 0;

        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                // 跳过空片段和当前目录引用
                continue;
            } else if (segment.equals("..")) {
                // 向上一级
                depth--;
                // 深度变为负数，表示已逃逸当前目录
                if (depth < 0) {
                    return false;
                }
            } else {
                // 普通目录或文件名，向下一级
                depth++;
            }
        }

        // 最终深度 >= 0，仍位于当前目录内或当前目录本身
        return depth >= 0;
    }

    /**
     * 命令验证结果。
     */
    class ValidationResult {
        private final boolean allowed;
        private final String reason;
        private final String executable;

        public ValidationResult(boolean allowed, String reason, String executable) {
            this.allowed = allowed;
            this.reason = reason;
            this.executable = executable;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getReason() {
            return reason;
        }

        public String getExecutable() {
            return executable;
        }

        public static ValidationResult allowed(String executable) {
            return new ValidationResult(true, "Command is in whitelist", executable);
        }

        public static ValidationResult rejected(String reason, String executable) {
            return new ValidationResult(false, reason, executable);
        }
    }
}
