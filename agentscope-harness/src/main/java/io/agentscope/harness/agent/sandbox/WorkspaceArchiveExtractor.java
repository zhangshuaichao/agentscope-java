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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * 将 tar 归档安全解压到目标目录。
 *
 * <p>解压每个归档条目前执行严格路径遍历检查：
 * <ol>
 *   <li>拒绝绝对路径条目（以 {@code /} 或 {@code \} 开头）</li>
 *   <li>拒绝包含 {@code ..} 路径片段的条目</li>
 *   <li>验证解析后的目标路径以规范根路径开头</li>
 * </ol>
 *
 * <p>这些检查防御“Zip Slip”类路径遍历攻击。
 *
 * <p>【学习提示】canonicalRoot 变量实际来自 normalize().toAbsolutePath()，并未调用
 * toRealPath() 解析符号链接。词法路径检查不等于完整文件系统隔离，目标目录的既有链接和
 * 并发修改仍须由可信目录管理及运行环境约束。
 */
public class WorkspaceArchiveExtractor {

    private WorkspaceArchiveExtractor() {
        // 工具类，不创建实例
    }

    /**
     * 将 tar 归档流解压到给定目标目录。
     *
     * <p>目标目录必须已存在。同路径的现有文件会被替换。
     *
     * @param destRoot 解压目标目录，必须已存在
     * @param tarStream tar 归档输入流；本方法关闭其归档包装流，不应依赖调用后底层流仍可读取
     * @throws SandboxException.SandboxRuntimeException 检测到路径遍历尝试时抛出
     * @throws Exception 因其他原因解压失败时抛出
     */
    public static void extractTarArchive(Path destRoot, InputStream tarStream) throws Exception {
        Path canonicalRoot = destRoot.normalize().toAbsolutePath();

        try (TarArchiveInputStream tar = new TarArchiveInputStream(tarStream)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                validateEntryName(entry.getName(), canonicalRoot);

                Path dest = canonicalRoot.resolve(entry.getName()).normalize();

                // 最终词法检查：目标路径不能越出根目录；此检查不解析符号链接。
                if (!dest.startsWith(canonicalRoot)) {
                    throw new SandboxException.SandboxRuntimeException(
                            SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                            "Tar entry escapes destination root: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    Files.copy(tar, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void validateEntryName(String name, Path canonicalRoot) {
        if (name == null || name.isEmpty()) {
            return;
        }

        // 拒绝绝对路径
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "Tar entry has absolute path (rejected): " + name);
        }

        // 拒绝路径遍历序列
        if (name.contains("..")) {
            // 规范化并检查实际遍历片段
            for (String segment : name.split("[/\\\\]")) {
                if ("..".equals(segment)) {
                    throw new SandboxException.SandboxRuntimeException(
                            SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                            "Tar entry contains path traversal (rejected): " + name);
                }
            }
        }

        // 拒绝空字节
        if (name.contains("\0")) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "Tar entry name contains null byte (rejected): " + name);
        }
    }
}
