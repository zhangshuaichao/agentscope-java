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
package io.agentscope.core.tool.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文本文件工具共用的辅助方法。
 */
public class FileToolUtils {

    private FileToolUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 验证给定文件路径位于基目录内，防止路径遍历和未授权文件访问。
     *
     * <p>【学习提示】这里使用 normalize() 和 startsWith() 检查词法路径边界，并未调用
     * toRealPath() 解析符号链接。因此不能把本方法单独视为完整文件系统沙箱；实际访问仍需
     * 结合权限层和运行环境隔离。学习时应区分路径字符串规范化与真实文件身份解析。
     *
     * <p>指定 baseDir 时：
     * <ul>
     * <li>相对路径以 baseDir 为基准解析</li>
     * <li>绝对路径必须位于 baseDir 内</li>
     * </ul>
     *
     * @param filePath 待验证文件路径
     * @param baseDir 限制访问的基目录（null 表示不限制）
     * @return 验证通过后的规范化绝对路径
     * @throws IOException 当路径无效或在基目录外时
     */
    static Path validatePath(String filePath, Path baseDir) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IOException("File path cannot be null or empty.");
        }

        Path inputPath = Paths.get(filePath);

        // 指定 baseDir 时，相对路径以其为基准解析
        Path path;
        if (baseDir != null && !inputPath.isAbsolute()) {
            // 相对路径：相对于 baseDir 解析
            path = baseDir.resolve(inputPath).normalize();
        } else {
            // 绝对路径或未指定 baseDir：转换为绝对路径
            path = inputPath.toAbsolutePath().normalize();
        }

        // 指定 baseDir 时，确保路径位于其中
        if (baseDir != null) {
            Path normalizedBaseDir = baseDir.toAbsolutePath().normalize();
            if (!path.startsWith(normalizedBaseDir)) {
                throw new IOException(
                        String.format(
                                "Access denied: The file path '%s' is outside the allowed base"
                                        + " directory '%s'.",
                                filePath, normalizedBaseDir));
            }
        }

        return path;
    }

    /**
     * 计算修改后展示文件内容的行范围。
     *
     * @param originalLineCount 原行数
     * @param newLineCount 修改后新行数
     * @param modifyStart 修改起始行
     * @param modifyEnd 修改结束行
     * @param extraViewLines 前后额外展示的行数
     * @return [viewStart, viewEnd] 数组
     */
    static int[] calculateViewRanges(
            int originalLineCount,
            int newLineCount,
            int modifyStart,
            int modifyEnd,
            int extraViewLines) {

        int viewStart = Math.max(1, modifyStart - extraViewLines);
        int viewEnd =
                Math.min(
                        newLineCount,
                        modifyStart
                                + (newLineCount - originalLineCount)
                                + (modifyEnd - modifyStart)
                                + extraViewLines);

        return new int[] {viewStart, viewEnd};
    }

    /**
     * 查看文本文件指定行范围。
     *
     * @param filePath 文件路径
     * @param startLine 起始行号（从 1 开始）
     * @param endLine 结束行号（从 1 开始，含端点）
     * @return 带行号的内容
     */
    static String viewTextFile(String filePath, int startLine, int endLine) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
            StringBuilder result = new StringBuilder();

            int start = Math.max(0, startLine - 1);
            int end = Math.min(lines.size(), endLine);

            for (int i = start; i < end; i++) {
                result.append(String.format("%d: %s\n", i + 1, lines.get(i)));
            }

            return result.toString();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * 将 "[1,5]" 或 "1,5" 等范围字符串解析为 [start, end] 数组。
     *
     * @param ranges 范围字符串
     * @return [start, end] 数组；无效时为 null
     */
    static int[] parseRanges(String ranges) {
        try {
            // 移除方括号和空格
            String cleaned = ranges.trim().replaceAll("^\\[", "").replaceAll("\\]$", "").trim();

            String[] parts = cleaned.split(",");
            if (parts.length != 2) {
                return null;
            }

            int start = Integer.parseInt(parts[0].trim());
            int end = Integer.parseInt(parts[1].trim());

            return new int[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
