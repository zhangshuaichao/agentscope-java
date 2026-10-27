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
package io.agentscope.harness.agent.filesystem.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以 {@link #execute} 为核心抽象方法的沙箱实现基类。
 *
 * <p>此类通过 {@link #execute} 委托 Shell 命令，为全部 {@link AbstractFilesystem} 方法提供默认实现。
 * 文件列表、grep 和 glob 使用标准 Unix 命令。读取使用服务端命令实现分页访问。
 * 写入将内容传输委托给 {@link #uploadFiles}。编辑在沙箱内运行静态内联 Python 脚本，
 * {@code old}/{@code new} 以 base64 编码形式通过命令行传入（该字符集没有 Shell 元字符，因此用户内容无法逃逸参数），目标文件本身不离开沙箱；
 * 当 {@code python3} 不可用时，回退为通过 {@link #downloadFiles} 下载、通过 {@link FilesystemUtils#stringReplacement(String, String, String, boolean)} 在 Java 中替换，
 * 再通过 {@link #uploadFiles} 重新上传。
 *
 * <p>子类必须实现：
 * <ul>
 *   <li>{@link #execute}：在沙箱中执行命令</li>
 *   <li>{@link #uploadFiles}：向沙箱上传文件</li>
 *   <li>{@link #downloadFiles}：从沙箱下载文件</li>
 *   <li>{@link #id()}：沙箱实例的唯一标识符</li>
 * </ul>
 */
public abstract class BaseSandboxFilesystem implements AbstractSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(BaseSandboxFilesystem.class);

    @Override
    public abstract String id();

    @Override
    public abstract ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds);

    @Override
    public abstract List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files);

    @Override
    public abstract List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths);

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        String escapedPath = FilesystemUtils.shellQuote(path);
        String cmd =
                "if [ ! -e "
                        + escapedPath
                        + " ]; then echo '__NOT_EXISTS__'; "
                        + "elif [ ! -d "
                        + escapedPath
                        + " ]; then echo '__NOT_A_DIR__'; "
                        + "else for f in "
                        + escapedPath
                        + "/*; do "
                        + "  if [ -d \"$f\" ]; then "
                        + "    mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0); "
                        + "    printf 'DIR:%s\\t%s\\n' \"$f\" \"$mtime\"; "
                        + "  elif [ -f \"$f\" ]; then "
                        + "    size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0); "
                        + "    mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0); "
                        + "    printf 'FILE:%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; "
                        + "  fi; "
                        + "done; fi";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        if (!result.isSuccess()) {
            return LsResult.fail(executeFailureMessage(result, "listing", path));
        }
        String output = result.output() != null ? result.output().strip() : "";

        if ("__NOT_EXISTS__".equals(output)) {
            return LsResult.fail("Path does not exist: " + path);
        }
        if ("__NOT_A_DIR__".equals(output)) {
            return LsResult.fail("Not a directory: " + path);
        }

        List<FileInfo> entries = new ArrayList<>();

        if (!output.isBlank()) {
            for (String line : output.split("\n")) {
                if (line.startsWith("DIR:")) {
                    String payload = line.substring(4);
                    String[] parts = payload.split("\t", 2);
                    String dirPath = parts[0];
                    long mtimeMs = parts.length > 1 ? parseEpochSeconds(parts[1]) : 0L;
                    entries.add(FileInfo.ofDir(dirPath, mtimeMs));
                } else if (line.startsWith("FILE:")) {
                    String payload = line.substring(5);
                    String[] parts = payload.split("\t", 3);
                    String filePath = parts[0];
                    long size = parts.length > 1 ? parseLongSafe(parts[1]) : 0L;
                    long mtimeMs = parts.length > 2 ? parseEpochSeconds(parts[2]) : 0L;
                    entries.add(FileInfo.ofFile(filePath, size, mtimeMs));
                }
            }
        }

        return LsResult.success(entries);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        String fileType = FilesystemUtils.getFileType(filePath);
        String escapedPath = FilesystemUtils.shellQuote(filePath);

        if (!"text".equals(fileType)) {
            String cmd = "base64 " + escapedPath + " 2>/dev/null";
            ExecuteResponse result = execute(runtimeContext, cmd, null);
            if (!result.isSuccess()) {
                // 除 124 以外的正退出码（124 是 timeout(1) 表示命令未完成的惯例）
                // 表示命令已经运行，但 base64 无法读取文件；
                // 标准错误被丢弃，因此返回设计中的 file_not_found 信号，
                // 而不是空错误。
                boolean commandRanAndFailedToRead =
                        result.exitCode() != null
                                && result.exitCode() > 0
                                && result.exitCode() != 124;
                return commandRanAndFailedToRead
                        ? ReadResult.fail("File '" + filePath + "': file_not_found")
                        : ReadResult.fail(executeFailureMessage(result, "reading", filePath));
            }
            String encoded = result.output() != null ? result.output().strip() : "";
            return ReadResult.success(new FileData(encoded, "base64"));
        }

        int startLine = offset + 1;
        int endLine = limit > 0 ? offset + limit : Integer.MAX_VALUE;
        String cmd =
                "if [ ! -f "
                        + escapedPath
                        + " ]; then echo '__NOT_FOUND__'; "
                        + "elif [ ! -s "
                        + escapedPath
                        + " ]; then echo '__EMPTY__'; "
                        + "else sed -n '"
                        + startLine
                        + ","
                        + endLine
                        + "p' "
                        + escapedPath
                        + "; fi";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        if (!result.isSuccess()) {
            return ReadResult.fail(executeFailureMessage(result, "reading", filePath));
        }
        String output = result.output() != null ? result.output() : "";

        if (output.strip().equals("__NOT_FOUND__")) {
            return ReadResult.fail("File '" + filePath + "': file_not_found");
        }
        if (output.strip().equals("__EMPTY__")) {
            return ReadResult.success(
                    new FileData("System reminder: File exists but has empty contents", "utf-8"));
        }

        if (output.endsWith("\n")) {
            output = output.substring(0, output.length() - 1);
        }
        return ReadResult.success(new FileData(output, "utf-8"));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        String escapedPath = FilesystemUtils.shellQuote(filePath);
        String checkCmd =
                "if [ -e "
                        + escapedPath
                        + " ]; then echo 'EXISTS'; exit 1; fi; "
                        + "mkdir -p \"$(dirname "
                        + escapedPath
                        + ")\" 2>&1";

        ExecuteResponse checkResult = execute(runtimeContext, checkCmd, null);
        if (checkResult.exitCode() != null && checkResult.exitCode() != 0) {
            if (checkResult.output() != null && checkResult.output().contains("EXISTS")) {
                return WriteResult.fail(
                        "Cannot write to "
                                + filePath
                                + " because it already exists. Read and then make an"
                                + " edit, or write to a new path.");
            }
            return WriteResult.fail("Failed to write file '" + filePath + "'");
        }

        List<FileUploadResponse> responses =
                uploadFiles(
                        runtimeContext,
                        List.of(Map.entry(filePath, content.getBytes(StandardCharsets.UTF_8))));
        if (responses.isEmpty() || !responses.get(0).isSuccess()) {
            String err =
                    responses.isEmpty() ? "upload returned no response" : responses.get(0).error();
            return WriteResult.fail("Failed to write file '" + filePath + "': " + err);
        }

        return WriteResult.ok(filePath);
    }

    /**
     * 通过 heredoc 执行的静态 Python 辅助脚本。用户内容从不以原始形式进入命令字符串：
     * {@code old}/{@code new} 以 base64 编码传入（字符集中没有 Shell 元字符），{@code sys.argv} 携带目标路径与编码数据，脚本正文为常量。
     * 输出为单行 {@code __RESULT__{json}}，因此解析不会接触用户内容。
     */
    private static final String EDIT_SCRIPT =
            """
            import base64, os, sys, json, uuid
            target, old_b64, new_b64 = sys.argv[1], sys.argv[2], sys.argv[3]
            replace_all = sys.argv[4] == "true"
            def result(obj):
                print("__RESULT__" + json.dumps(obj))
            if not os.path.isfile(target):
                result({"error": "file_not_found"})
                sys.exit(0)
            try:
                old = base64.b64decode(old_b64).decode("utf-8")
                new = base64.b64decode(new_b64).decode("utf-8")
            except Exception as e:
                result({"error": "read_failed", "detail": str(e)})
                sys.exit(0)
            if old == "":
                result({"error": "string_not_found"})
                sys.exit(0)
            try:
                with open(target, "rb") as f:
                    text = f.read().decode("utf-8")
            except Exception as e:
                result({"error": "read_failed", "detail": str(e)})
                sys.exit(0)
            # Same LF normalization as FilesystemUtils.stringReplacement, so the native path
            # and the transfer fallback accept and reject exactly the same edits. The escapes
            # are doubled: a text block would otherwise turn them into real CR/LF bytes and
            # split the Python string literal across lines.
            def norm(s):
                return s.replace("\\r\\n", "\\n").replace("\\r", "\\n")
            text, old, new = norm(text), norm(old), norm(new)
            if len(text) == 0:
                result({"error": "empty"})
                sys.exit(0)
            count = text.count(old)
            if count == 0:
                result({"error": "string_not_found"})
                sys.exit(0)
            if count > 1 and not replace_all:
                result({"error": "multiple_occurrences", "count": count})
                sys.exit(0)
            out = text.replace(old, new) if replace_all else text.replace(old, new, 1)
            out_bytes = out.encode("utf-8")
            # realpath so os.replace writes through symlinks (replacing the link
            # itself would break workspace symlinks) and stays on target's mount.
            # uuid4 keeps the temp name unique per edit even when concurrent edits
            # share a long-lived shell process (os.getpid() would not be unique).
            # A hard process kill before os.replace can leave this uniquely named sibling
            # behind; it is visible in directory listings and can enter a workspace snapshot.
            # Do not sweep by age here: a slow concurrent edit could still own such a file.
            real = os.path.realpath(target)
            tmp_out = real + ".agentscope-edit-tmp-" + uuid.uuid4().hex
            try:
                # Failure here (including a read-only directory that forbids creating
                # the temp file, or os.replace failing) leaves the target untouched:
                # nothing is written to `real` until the atomic rename succeeds, and a
                # non-atomic copy fallback would reintroduce the truncate-on-failure
                # risk. Java then retries via the transfer path, whose shell
                # redirection needs only file write permission.
                # stat and the write share this try so a target that disappears between
                # the read above and this point maps to a write_failed result instead of
                # an uncaught traceback.
                st = os.stat(real)
                with open(tmp_out, "wb") as f:
                    f.write(out_bytes)
                os.chmod(tmp_out, st.st_mode)
                os.replace(tmp_out, real)
            except Exception as e:
                try:
                    if os.path.exists(tmp_out):
                        os.remove(tmp_out)
                except Exception:
                    pass
                result({"error": "write_failed", "detail": str(e)})
                sys.exit(0)
            result({"count": count})
            """;

    private static final String EDIT_HEREDOC_DELIMITER = "__AGENTSCOPE_EDIT_PY__";

    private static final String EDIT_RESULT_MARKER = "__RESULT__";

    /**
     * 整个命令字符串的字节数上限。
     *
     * <p>命令作为单个 argv 元素（{@code sh -c '<cmd>'}）交给后端，因此内核的 {@code MAX_ARG_STRLEN}（Linux 上 32 页，即 128 KiB）限制适用于整个命令，而非每个参数。
     * 脚本正文、目标路径和引号与两个 base64 载荷共用预算，所以检查完整字符串。
     * 112 KiB 在内核上限之下留下 16 KiB 余量；实测从 128 KiB 开始出现 E2BIG。
     */
    private static final int MAX_INLINE_COMMAND_BYTES = 112 * 1024;

    private static final ObjectMapper NATIVE_RESULT_MAPPER = new ObjectMapper();

    /**
     * 判断 {@code __RESULT__} 载荷是否报告 {@code write_failed}，即原子写入未修改目标，传输路径应重试。
     */
    private static boolean isWriteFailed(String payload) {
        try {
            NativeScriptResult parsed =
                    NATIVE_RESULT_MAPPER.readValue(payload.trim(), NativeScriptResult.class);
            return parsed != null && "write_failed".equals(parsed.error());
        } catch (Exception e) {
            // 无法解析的载荷由 mapNativeResult 报告。
            return false;
        }
    }

    /**
     * 在沙箱中编辑文件，将 {@code oldString} 替换为 {@code newString}。
     *
     * <p>在沙箱中运行静态内联 Python 脚本，{@code old}/{@code new} 经 base64 编码后通过命令行传入（base64 字符集没有 Shell 元字符，用户内容无法逃逸参数）。
     * 文件本身不跨越边界。当 {@code python3} 缺失或原生写入失败时，回退为下载 → Java 替换 → 重新上传。
     *
     * <p>若参数过大导致命令超过 {@link #MAX_INLINE_COMMAND_BYTES}，将返回诊断并拒绝，而不是进行传输：
     * {@code old}/{@code new} 是编辑片段，超大请求应拆为多次编辑。
     *
     * <p>契约：通过解析后的目标（{@code realpath}）进行编辑，因此编辑符号链接会写入其指向的文件，并保留链接本身。
     * 原生路径只通过同目录临时文件加原子重命名写入，所以 {@code write_failed} 表示文件未变；
     * 此时 {@code edit()} 通过传输路径重试，其 Shell 重定向仅需要文件写权限。
     * 在创建临时文件与重命名之间强制终止，可能在工作区留下唯一命名的同级临时文件；它可能出现在列表或快照中。
     * 实现不会清理这些文件，因为基于文件年龄的清理可能与缓慢的并发编辑发生竞争。
     *
     * @param runtimeContext 每次调用的智能体上下文；可为 {@code null}
     * @param filePath 要编辑的文件路径
     * @param oldString 要查找的字面字符串，必须非空
     * @param newString 替换字符串，不得为 {@code null}；传入空字符串可删除匹配文本（保持通常的替换语义）
     * @param replaceAll 是否替换全部匹配，而不仅是第一个
     * @return 携带替换次数的 {@link EditResult#ok}，或 {@link EditResult#fail}
     */
    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        EditResult invalid = FilesystemUtils.validateEditArguments(oldString, newString);
        if (invalid != null) {
            return invalid;
        }

        String cmd = buildNativeEditCommand(filePath, oldString, newString, replaceAll);
        if (cmd == null) {
            int kib =
                    commandSizeBytes(nativeEditCommand(filePath, oldString, newString, replaceAll))
                            / 1024;
            return EditResult.fail(
                    "Error: edit arguments are too large ("
                            + kib
                            + " KiB encoded, limit "
                            + (MAX_INLINE_COMMAND_BYTES / 1024)
                            + " KiB). Split the edit into smaller pieces.");
        }

        ExecuteResponse execResult;
        try {
            execResult = execute(runtimeContext, cmd, null);
        } catch (Exception e) {
            log.warn("[sandbox-fs] native edit execute failed, falling back to transfer", e);
            return editViaTransfer(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        String output = execResult.output() != null ? execResult.output() : "";
        int marker = output.indexOf(EDIT_RESULT_MARKER);
        if (marker >= 0) {
            String payload = output.substring(marker + EDIT_RESULT_MARKER.length());
            if (isWriteFailed(payload)) {
                // 原生路径仅通过同目录临时文件加重命名写入目标，
                // 因此此处失败表示目标未变。通过传输路径重试，
                // 该路径的 Shell 重定向仅需要文件写权限，
                // 即使只读目录阻止创建临时文件，也可能成功。
                log.warn(
                        "[sandbox-fs] native edit write failed ({}), falling back to transfer",
                        payload.trim());
                return editViaTransfer(runtimeContext, filePath, oldString, newString, replaceAll);
            }
            return mapNativeResult(filePath, oldString, payload);
        }
        if (isPythonMissing(execResult)) {
            return editViaTransfer(runtimeContext, filePath, oldString, newString, replaceAll);
        }
        // 没有 __RESULT__ 行但 python3 确实运行了：属于执行层失败（超时、传输、
        // 解释器崩溃）。约定与 ls/read/grep/glob 相同。
        return EditResult.fail(executeFailureMessage(execResult, "editing", filePath));
    }

    /**
     * 构建原生编辑命令；若会超过 {@link #MAX_INLINE_COMMAND_BYTES} 则返回 {@code null}。
     * {@code old}/{@code new} 以 base64 编码传输：其字符集没有 Shell 元字符，因此无需额外引用，也无法逃逸单引号参数。
     */
    private static String buildNativeEditCommand(
            String filePath, String oldString, String newString, boolean replaceAll) {
        String cmd = nativeEditCommand(filePath, oldString, newString, replaceAll);
        return commandSizeBytes(cmd) > MAX_INLINE_COMMAND_BYTES ? null : cmd;
    }

    private static String nativeEditCommand(
            String filePath, String oldString, String newString, boolean replaceAll) {
        return "python3 - "
                + FilesystemUtils.shellQuote(filePath)
                + " "
                + FilesystemUtils.shellQuote(encodeBase64(oldString))
                + " "
                + FilesystemUtils.shellQuote(encodeBase64(newString))
                + " "
                + (replaceAll ? "true" : "false")
                + " <<'"
                + EDIT_HEREDOC_DELIMITER
                + "'\n"
                + EDIT_SCRIPT
                + EDIT_HEREDOC_DELIMITER
                + "\n__ec=$?; exit $__ec";
    }

    private static String encodeBase64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int commandSizeBytes(String cmd) {
        return cmd.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean isPythonMissing(ExecuteResponse response) {
        // 127 表示未找到命令，126 表示已找到但不可执行（例如 python3 存在但
        // 执行位被清除）。两者都意味着原生路径无法运行。
        if (response.exitCode() != null
                && (response.exitCode() == 127 || response.exitCode() == 126)) {
            return true;
        }
        String out = response.output() != null ? response.output().toLowerCase() : "";
        return out.contains("python3")
                && (out.contains("not found")
                        || out.contains("no such")
                        || out.contains("permission denied")
                        || out.contains("not executable"));
    }

    private static EditResult mapNativeResult(String filePath, String oldString, String json) {
        // 使用真正的 JSON 解析器，而不是子字符串匹配："detail" 值
        // 包含异常文本，可能带有用户控制的路径，扫描原始载荷
        // 可能误判（例如路径中包含 "string_not_found"）。
        NativeScriptResult parsed;
        try {
            parsed = NATIVE_RESULT_MAPPER.readValue(json.trim(), NativeScriptResult.class);
        } catch (Exception e) {
            log.warn("[sandbox-fs] unparseable native edit result: {}", json.trim(), e);
            return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
        }
        if (parsed == null) {
            return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
        }
        String error = parsed.error();
        if ("multiple_occurrences".equals(error)) {
            int count = parsed.count() != null ? parsed.count() : 1;
            if (count > 1) {
                return EditResult.fail(
                        "Error: String '"
                                + oldString
                                + "' appears "
                                + count
                                + " times in file. "
                                + "Use replaceAll=true to replace all instances, or provide a more"
                                + " specific string with surrounding context.");
            }
            return EditResult.fail(
                    "Error: String '"
                            + oldString
                            + "' appears multiple times. Use replaceAll=true to replace all"
                            + " occurrences.");
        }
        if ("string_not_found".equals(error)) {
            return EditResult.fail("Error: String not found in file: '" + oldString + "'");
        }
        if ("file_not_found".equals(error)) {
            return EditResult.fail("Error: File '" + filePath + "' not found");
        }
        if ("empty".equals(error)) {
            return EditResult.fail("Error: File '" + filePath + "' is empty");
        }
        if (error != null) {
            // read_failed、write_failed 及未来新增的标记，附带脚本提供的详细信息。
            return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
        }
        if (parsed.count() != null) {
            return EditResult.ok(filePath, parsed.count());
        }
        return EditResult.fail("Error editing file '" + filePath + "': " + json.trim());
    }

    /**
     * 沙箱脚本以单行输出的 {@code __RESULT__} 载荷。
     */
    private record NativeScriptResult(String error, Integer count, String detail) {}

    /**
     * 当 {@code python3} 不可用时的回退：下载、在 Java 中替换、重新上传。
     *
     * <p>与原生路径不同，这里的符号链接处理委托给 {@link #uploadFiles}：
     * 此方法按给定的 {@code filePath} 重新上传，不解析 {@code realpath}。
     * 因此，若后端上传时沿链接写入，链接会被保留；若后端替换路径，可能替换符号链接本身。
     * 原生路径保证保留链接，而此回退路径不保证。
     */
    private EditResult editViaTransfer(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        List<FileDownloadResponse> downloaded = downloadFiles(runtimeContext, List.of(filePath));
        if (downloaded.isEmpty() || !downloaded.get(0).isSuccess()) {
            // 区分“后端无法读取文件”（权限、传输、
            // 响应截断）与“路径不存在”：若把下载失败报告为
            // file_not_found，会误导调用方重新读取已经持有的文件。
            String cause = downloaded.isEmpty() ? "no response" : downloaded.get(0).error();
            return EditResult.fail(
                    "Error: Could not read file '"
                            + filePath
                            + "' for editing: "
                            + (cause != null ? cause : "unknown error"));
        }
        byte[] contentBytes = downloaded.get(0).content();
        if (contentBytes == null || contentBytes.length == 0) {
            return EditResult.fail("Error: File '" + filePath + "' is empty");
        }
        // 遇到无效 UTF-8 时失败，而不是悄悄替换字节：原生 Python
        // 路径采用严格解码，回退路径也不能将无法表示的旧编码
        // 文件悄然重新编码。
        String content;
        try {
            content = FilesystemUtils.decodeUtf8Strict(contentBytes);
        } catch (CharacterCodingException e) {
            return EditResult.fail(
                    "Error editing file '" + filePath + "': file is not valid UTF-8");
        }

        FilesystemUtils.ReplacementResult result =
                FilesystemUtils.stringReplacement(content, oldString, newString, replaceAll);

        if (!result.isSuccess()) {
            return EditResult.fail(result.error());
        }

        String newContent = result.content();
        int occurrences = result.occurrences();

        List<FileUploadResponse> uploaded =
                uploadFiles(
                        runtimeContext,
                        List.of(Map.entry(filePath, newContent.getBytes(StandardCharsets.UTF_8))));
        if (uploaded.isEmpty() || !uploaded.get(0).isSuccess()) {
            String err =
                    uploaded.isEmpty() ? "upload returned no response" : uploaded.get(0).error();
            return EditResult.fail("Error writing edited file '" + filePath + "': " + err);
        }

        return EditResult.ok(filePath, occurrences);
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        String searchPath = FilesystemUtils.shellQuote(path != null ? path : ".");
        String grepOpts = "-rHnF";
        String globPattern = "";
        if (glob != null && !glob.isBlank()) {
            globPattern = "--include=" + FilesystemUtils.shellQuote(stripRecursivePrefix(glob));
        }
        String patternEscaped = FilesystemUtils.shellQuote(pattern);

        String cmd =
                "grep "
                        + grepOpts
                        + " "
                        + globPattern
                        + " -e "
                        + patternEscaped
                        + " "
                        + searchPath
                        + " 2>/dev/null || true";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        if (!result.isSuccess()) {
            return GrepResult.fail(
                    executeFailureMessage(result, "searching", path != null ? path : "."));
        }
        String output = result.output() != null ? result.output().strip() : "";

        if (output.isEmpty()) {
            return GrepResult.success(List.of());
        }

        List<GrepMatch> matches = new ArrayList<>();
        for (String line : output.split("\n")) {
            String[] parts = line.split(":", 3);
            if (parts.length >= 3) {
                try {
                    matches.add(new GrepMatch(parts[0], Integer.parseInt(parts[1]), parts[2]));
                } catch (NumberFormatException e) {
                    // 跳过格式错误的行
                }
            }
        }

        return GrepResult.success(matches);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String escapedPath = FilesystemUtils.shellQuote(path != null ? path : "/");
        String escapedPattern = FilesystemUtils.shellQuote(stripRecursivePrefix(pattern));

        String cmd =
                "find "
                        + escapedPath
                        + " -type f -name "
                        + escapedPattern
                        + " 2>/dev/null | sort | while IFS= read -r f; do "
                        + "  size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0); "
                        + "  mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0); "
                        + "  printf '%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; "
                        + "done";

        ExecuteResponse result = execute(runtimeContext, cmd, null);
        if (!result.isSuccess()) {
            return GlobResult.fail(
                    executeFailureMessage(result, "globbing", path != null ? path : "/"));
        }
        String output = result.output() != null ? result.output().strip() : "";

        if (output.isEmpty()) {
            return GlobResult.success(List.of());
        }

        List<FileInfo> entries = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length >= 3) {
                String filePath = parts[0].trim();
                long size = parseLongSafe(parts[1]);
                long mtimeMs = parseEpochSeconds(parts[2]);
                entries.add(FileInfo.ofFile(filePath, size, mtimeMs));
            } else {
                entries.add(FileInfo.ofFile(line.trim(), 0, ""));
            }
        }

        return GlobResult.success(entries);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        AbstractFilesystem.validatePath(path);
        String escapedPath = FilesystemUtils.shellQuote(path);
        String cmd = "rm -rf " + escapedPath;
        ExecuteResponse result = execute(runtimeContext, cmd, null);
        if (result.exitCode() != 0) {
            return WriteResult.fail("Error deleting '" + path + "': " + result.output());
        }
        return WriteResult.ok(path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        AbstractFilesystem.validatePath(fromPath);
        AbstractFilesystem.validatePath(toPath);
        String escapedFrom = FilesystemUtils.shellQuote(fromPath);
        String escapedTo = FilesystemUtils.shellQuote(toPath);
        String cmd = "mkdir -p $(dirname " + escapedTo + ") && mv " + escapedFrom + " " + escapedTo;
        ExecuteResponse result = execute(runtimeContext, cmd, null);
        if (result.exitCode() != 0) {
            return WriteResult.fail(
                    "Error moving '" + fromPath + "' to '" + toPath + "': " + result.output());
        }
        return WriteResult.ok(toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String escapedPath = FilesystemUtils.shellQuote(path);
        ExecuteResponse result =
                execute(runtimeContext, "test -e " + escapedPath + " && echo yes || echo no", null);
        return result.output() != null && result.output().strip().startsWith("yes");
    }

    /**
     * 为不成功的 {@link #execute} 响应构建失败消息，添加操作上下文前缀；
     * 当响应没有诊断输出时，使用退出码作为回退信息。
     */
    private static String executeFailureMessage(
            ExecuteResponse result, String operation, String target) {
        String detail =
                result.output() != null && !result.output().isBlank()
                        ? result.output()
                        : "exit code " + result.exitCode();
        return "Error " + operation + " '" + target + "': " + detail;
    }

    /**
     * 移除模式开头的递归 glob 前缀 {@code **&#47;}，使其可传给 {@code find -name} 或 {@code grep --include=} 等仅匹配文件名部分的工具。
     * 例如，{@code **&#47;*.java} 变为 {@code *.java}。
     *
     * @param pattern glob 模式，可为 {@code null}
     * @return 移除开头所有 {@code **&#47;} 后的模式；无此前缀时返回原值
     */
    private static String stripRecursivePrefix(String pattern) {
        if (pattern != null && pattern.startsWith("**/")) {
            return pattern.substring(3);
        }
        return pattern;
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long parseEpochSeconds(String s) {
        long epochSec = parseLongSafe(s);
        return epochSec * 1000;
    }
}
