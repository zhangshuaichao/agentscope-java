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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.util.List;
import java.util.Map;

/**
 * 智能体使用的抽象文件系统 API：列出、读取、写入、编辑、grep、glob、上传与下载。
 *
 * <p>实现可面向本地磁盘、沙箱、键值存储或其他存储。以宿主机目录为根的
 * {@link LocalFilesystem} 和 {@link LocalFilesystemWithShell} 还提供接收工作区根路径
 * {@link String} 的构造方法（语义与 {@link java.nio.file.Path} 相同）。
 *
 * <p>每个操作都接收 {@link RuntimeContext}，以便存储将操作限定到当前会话、用户或沙箱。
 * 未处于带合并上下文的工具／智能体调用中的调用方，应传入 {@link RuntimeContext#empty()}。
 *
 * <p>【学习提示】统一文件 API 并不意味着统一的隔离保证：本地、沙箱和远程后端的
 * 路径解析、授权与执行边界不同，应继续阅读所选实现，而不是把抽象接口当作安全沙箱。
 */
public interface AbstractFilesystem {
    /**
     * 可选的持久化原子存储能力；不会静默回退为本地或无版本写入。
     */
    default io.agentscope.core.session.AtomicSessionStorage sessionStorage(RuntimeContext context) {
        throw new UnsupportedOperationException(
                "Filesystem does not support durable session storage: " + getClass().getName());
    }

    /**
     * 列出目录中的所有文件及元数据。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param path 要列出的目录绝对路径（必须以 '/' 开头）
     * @return 包含目录项或错误的 LsResult
     */
    LsResult ls(RuntimeContext runtimeContext, String path);

    /**
     * 读取文件内容，可按行分页。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param filePath 要读取的文件绝对路径（必须以 '/' 开头）
     * @param offset 起始行号，从 0 开始；默认 0
     * @param limit 最多读取的行数；默认 2000
     * @return 成功时含文件数据、失败时含错误的 ReadResult
     */
    ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit);

    /**
     * 将内容写入新文件；文件已存在时返回错误。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param filePath 要创建的文件绝对路径
     * @param content 要写入文件的字符串内容
     * @return 成功时含路径的 WriteResult；文件已存在或写入失败时含错误
     */
    WriteResult write(RuntimeContext runtimeContext, String filePath, String content);

    /**
     * 在现有文件中进行精确字符串替换。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param filePath 要编辑的文件绝对路径
     * @param oldString 要精确查找并替换的字符串
     * @param newString 用于替换 oldString 的字符串（必须与 oldString 不同）
     * @param replaceAll 为 true 时替换全部匹配；为 false 时 oldString 必须唯一
     * @return 成功时含路径和替换次数、失败时含错误的 EditResult
     */
    EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll);

    /**
     * 在文件中搜索字面文本模式。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param pattern 要搜索的字面字符串（不是正则表达式）
     * @param path 可选搜索目录路径（null 表示当前工作目录）
     * @param glob 可选 glob 模式，用于筛选搜索文件（例如 "*.java"）
     * @return 包含匹配项或错误的 GrepResult
     */
    GrepResult grep(RuntimeContext runtimeContext, String pattern, String path, String glob);

    /**
     * 查找符合 glob 模式的文件。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param pattern 带通配符的文件路径匹配模式
     * @param path 搜索起始目录（默认 "/"）
     * @return 包含匹配文件或错误的 GlobResult
     */
    GlobResult glob(RuntimeContext runtimeContext, String pattern, String path);

    /**
     * 上传多个文件。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param files 要上传的路径到内容映射列表
     * @return FileUploadResponse 列表，每个输入文件对应一项（顺序与输入一致）
     */
    List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files);

    /**
     * 下载多个文件。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param paths 要下载的文件路径列表
     * @return FileDownloadResponse 列表，每个输入路径对应一项（顺序与输入一致）
     */
    List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths);

    /**
     * 删除文件或目录（目录递归删除）。
     *
     * <p>操作幂等：删除不存在的路径也视为成功。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param path 要删除的文件或目录绝对路径
     * @return 已删除或原本不存在时返回成功的 WriteResult，I/O 错误时返回失败
     */
    WriteResult delete(RuntimeContext runtimeContext, String path);

    /**
     * 将文件或目录从 {@code fromPath} 移动（重命名）到 {@code toPath}。
     *
     * <p>跨多个存储的实现（例如 {@code CompositeFilesystem}）在源和目标位于不同后端文件系统时，
     * 可能回退为读取、写入、删除的顺序操作。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param fromPath 源绝对路径
     * @param toPath 目标绝对路径
     * @return 完成时返回成功的 WriteResult，I/O 错误或源不存在时返回失败
     */
    WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath);

    /**
     * 检查文件或目录是否存在。
     *
     * <p>没有专用 {@code exists} API 时，实现可用轻量读取探测近似实现，但应避免读取整个文件。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有时使用 {@link RuntimeContext#empty()}
     * @param path 要检查的绝对路径
     * @return 路径存在时为 {@code true}，否则为 {@code false}
     */
    boolean exists(RuntimeContext runtimeContext, String path);

    // ==================== 路径验证工具 ====================

    /**
     * 验证 {@code path} 是否安全（非 null、非空白、不含 {@code ..} 路径穿越）。
     *
     * @param path 要验证的路径
     * @throws IllegalArgumentException 路径无效时抛出
     */
    static void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be null or blank");
        }
        String portable = path.replace('\\', '/');
        for (String segment : portable.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Path traversal ('..') not allowed: " + path);
            }
        }
    }
}
