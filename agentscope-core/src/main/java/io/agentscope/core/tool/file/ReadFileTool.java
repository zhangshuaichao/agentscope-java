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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 查看文本文件内容（可指定行范围）和列出目录内容的工具。
 *
 * <p>支持：
 * <ul>
 * <li>查看完整文件</li>
 * <li>查看指定行范围（例如 1–100 行）</li>
 * <li>通过负索引从末尾查看（例如末尾 100 行：[-100, -1]）</li>
 * <li>列出指定目录中的文件和目录及其完整路径</li>
 * </ul>
 *
 * <p>安全性：指定 baseDir 时，所有文件操作限制在该目录内，避免未授权访问。
 */
public class ReadFileTool {

    private static final Logger logger = LoggerFactory.getLogger(ReadFileTool.class);

    /**
     * 限制文件访问的基目录；为 null 时不限制。用于防范路径遍历和未授权访问。
     */
    private final Path baseDir;

    /**
     * 创建不限制基目录的 ReadFileTool。
     */
    public ReadFileTool() {
        this(null);
    }

    /**
     * 创建限制基目录的 ReadFileTool。
     *
     * @param baseDir 限制文件访问的基目录；为 null 时不限制
     */
    public ReadFileTool(String baseDir) {
        this.baseDir = baseDir != null ? Paths.get(baseDir).toAbsolutePath().normalize() : null;
        if (this.baseDir != null) {
            logger.info("ReadFileTool initialized with base directory: {}", this.baseDir);
        } else {
            logger.info("ReadFileTool initialized without base directory restriction");
        }
    }

    /**
     * 查看指定行范围的文件内容并附带行号。未提供 ranges 时返回整个文件。
     *
     * @param filePath 目标文件路径
     * @param ranges 待查看行范围，包含两端（例如第 1 至 100 行为 "1,100"）；未提供时返回整个文件。末尾 100 行使用 "-100,-1"。格式为 "start,end" 或 "[start,end]"
     * @return 带行号的文件内容或错误消息
     */
    @Tool(
            name = "view_text_file",
            description =
                    "View the file content in the specified range with line numbers. If ranges is"
                        + " not provided, the entire file will be returned. Use this to read and"
                        + " inspect file contents. Supports negative indices to view from the end"
                        + " (e.g., '-100,-1' for last 100 lines).")
    public Mono<ToolResultBlock> viewTextFile(
            @ToolParam(name = "file_path", description = "The target file path") String filePath,
            @ToolParam(
                            name = "ranges",
                            description =
                                    "The range of lines to be viewed (e.g., '1,100' or '[1,100]'),"
                                        + " inclusive. If not provided, the entire file will be"
                                        + " returned. To view the last 100 lines, use '-100,-1'.",
                            required = false)
                    String ranges) {

        logger.debug("view_text_file called: filePath='{}', ranges='{}'", filePath, ranges);

        return Mono.fromCallable(
                        () -> {
                            // 验证路径位于基目录内
                            Path path;
                            try {
                                path = FileToolUtils.validatePath(filePath, baseDir);
                            } catch (Exception e) {
                                logger.warn(
                                        "Path validation failed for '{}': {}",
                                        filePath,
                                        e.getMessage());
                                return ToolResultBlock.error(e.getMessage());
                            }

                            // 检查文件存在
                            if (!Files.exists(path)) {
                                logger.warn("File does not exist: {}", filePath);
                                return ToolResultBlock.error(
                                        String.format("The file %s does not exist.", filePath));
                            }

                            // 检查路径为文件
                            if (!Files.isRegularFile(path)) {
                                logger.warn("Path is not a regular file: {}", filePath);
                                return ToolResultBlock.error(
                                        String.format("The path %s is not a file.", filePath));
                            }

                            // 提供范围时进行解析
                            if (ranges == null || ranges.trim().isEmpty()) {
                                // 返回完整文件内容
                                ReadResult result = readLines(path, 1, Integer.MAX_VALUE);
                                logger.debug(
                                        "Returning entire file content ({} lines)",
                                        result.linesRead);
                                return ToolResultBlock.text(
                                        String.format(
                                                "The content of %s:\n```\n%s```",
                                                filePath, result.content));
                            }

                            // 解析范围
                            int[] rangeArray = FileToolUtils.parseRanges(ranges);
                            if (rangeArray == null) {
                                logger.warn("Invalid range format: {}", ranges);
                                return ToolResultBlock.error(
                                        String.format(
                                                "Invalid range format. Expected '[start,end]' or"
                                                        + " 'start,end', but got %s.",
                                                ranges));
                            }

                            int start = rangeArray[0];
                            int end = rangeArray[1];

                            // 处理负索引（从末尾计数）
                            int totalLines = -1;
                            if (start < 0 || end < 0) {
                                totalLines = countLines(path);
                                if (start < 0) {
                                    start = totalLines + start + 1;
                                }
                                if (end < 0) {
                                    end = totalLines + end + 1;
                                }
                            }

                            // 验证范围
                            if (start < 1) {
                                start = 1;
                            }
                            if (totalLines >= 0 && end > totalLines) {
                                end = totalLines;
                            }
                            if (start > end) {
                                logger.warn("Invalid range: start {} > end {}", start, end);
                                return ToolResultBlock.error(
                                        String.format(
                                                "Invalid range: start line %d is greater than end"
                                                        + " line %d.",
                                                start, end));
                            }

                            logger.debug("Viewing lines {}-{} from file: {}", start, end, filePath);

                            ReadResult result = readLines(path, start, end);
                            if (totalLines < 0 && result.linesRead < end) {
                                end = result.linesRead;
                            }
                            if (start > end) {
                                logger.warn(
                                        "Invalid range: start {} > end {} for file with {} lines",
                                        start,
                                        end,
                                        result.linesRead);
                                return ToolResultBlock.error(
                                        String.format(
                                                "Invalid range: start line %d is greater than end"
                                                        + " line %d.",
                                                start, end));
                            }

                            return ToolResultBlock.text(
                                    String.format(
                                            "The content of %s in lines [%d, %d]:\n```\n%s```",
                                            filePath, start, end, result.content));
                        })
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error viewing file '{}' with ranges '{}': {}",
                                    filePath,
                                    ranges,
                                    e.getMessage(),
                                    e);
                            return Mono.just(ToolResultBlock.error("Error: " + e.getMessage()));
                        });
    }

    /**
     * 列出指定目录内的文件和目录。
     *
     * @param dirPath 目标目录路径
     * @return 带完整路径的文件和目录列表
     */
    @Tool(
            name = "list_directory",
            description =
                    "List all files and directories in the specified directory. Returns the full"
                        + " paths of all files and folders in the directory. Use this to explore"
                        + " the file system structure.")
    public Mono<ToolResultBlock> listDirectory(
            @ToolParam(
                            name = "dir_path",
                            description = "The target directory path to list files and folders")
                    String dirPath) {

        logger.debug("list_directory called: dirPath='{}'", dirPath);

        return Mono.fromCallable(
                        () -> {
                            // 验证路径位于基目录内
                            Path path;
                            try {
                                path = FileToolUtils.validatePath(dirPath, baseDir);
                            } catch (Exception e) {
                                logger.warn(
                                        "Path validation failed for '{}': {}",
                                        dirPath,
                                        e.getMessage());
                                return ToolResultBlock.error(e.getMessage());
                            }

                            // 检查路径存在
                            if (!Files.exists(path)) {
                                logger.warn("Directory does not exist: {}", dirPath);
                                return ToolResultBlock.error(
                                        String.format("The directory %s does not exist.", dirPath));
                            }

                            // 检查路径为目录
                            if (!Files.isDirectory(path)) {
                                logger.warn("Path is not a directory: {}", dirPath);
                                return ToolResultBlock.error(
                                        String.format("The path %s is not a directory.", dirPath));
                            }

                            // 列出文件和目录
                            List<String> files = new ArrayList<>();
                            List<String> directories = new ArrayList<>();

                            try (Stream<Path> paths = Files.list(path)) {
                                paths.sorted(Comparator.comparing(Path::toString))
                                        .forEach(
                                                p -> {
                                                    String fullPath = p.toAbsolutePath().toString();
                                                    if (Files.isDirectory(p)) {
                                                        directories.add(fullPath);
                                                    } else {
                                                        files.add(fullPath);
                                                    }
                                                });
                            } catch (Exception e) {
                                logger.error(
                                        "Error listing directory '{}': {}",
                                        dirPath,
                                        e.getMessage(),
                                        e);
                                return ToolResultBlock.error(
                                        "Error listing directory: " + e.getMessage());
                            }

                            // 格式化输出
                            StringBuilder result = new StringBuilder();
                            result.append(String.format("Contents of directory %s:\n\n", dirPath));

                            if (!directories.isEmpty()) {
                                result.append("Directories:\n");
                                for (String dir : directories) {
                                    result.append("  ").append(dir).append("\n");
                                }
                                result.append("\n");
                            }

                            if (!files.isEmpty()) {
                                result.append("Files:\n");
                                for (String file : files) {
                                    result.append("  ").append(file).append("\n");
                                }
                                result.append("\n");
                            }

                            if (directories.isEmpty() && files.isEmpty()) {
                                result.append("(empty directory)\n");
                            } else {
                                result.append(
                                        String.format(
                                                "Total: %d directory(ies), %d file(s)",
                                                directories.size(), files.size()));
                            }

                            logger.debug(
                                    "Listed {} directories and {} files in: {}",
                                    directories.size(),
                                    files.size(),
                                    dirPath);

                            return ToolResultBlock.text(result.toString());
                        })
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error listing directory '{}': {}", dirPath, e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error("Error: " + e.getMessage()));
                        });
    }

    private ReadResult readLines(Path path, int start, int end) throws IOException {
        StringBuilder content = new StringBuilder();
        int lineNumber = 0;

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber >= start) {
                    content.append(lineNumber).append(": ").append(line).append('\n');
                }
                if (lineNumber >= end) {
                    break;
                }
            }
        }

        return new ReadResult(content.toString(), lineNumber);
    }

    private int countLines(Path path) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    private static final class ReadResult {
        private final String content;
        private final int linesRead;

        private ReadResult(String content, int linesRead) {
            this.content = content;
            this.linesRead = linesRead;
        }
    }
}
