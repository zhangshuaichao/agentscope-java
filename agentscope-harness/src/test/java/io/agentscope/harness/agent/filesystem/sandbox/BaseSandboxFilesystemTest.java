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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class BaseSandboxFilesystemTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    /**
     * 模拟 {@code edit()} 将用户内容交给沙箱的方式。
     */
    private static String base64(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 沙箱后端调用自身失败（如 HTTP 504）时 {@code SandboxBackedFilesystem.execute} 生成的响应，本测试模拟命令未运行的情况。
     */
    private static ExecuteResponse sandboxRequestFailed() {
        return new ExecuteResponse(
                "Internal sandbox error: Execute failed (status=504)", -1, false);
    }

    // ================================================================
    // 单元测试：预设响应，适用于全部平台
    // ================================================================

    @Nested
    class CannedResponseTests {

        @Test
        void glob_recursivePattern_stripsDoubleStarPrefixBeforeFindName() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            GlobResult result = filesystem.glob(RT, "**/*.md", "/workspace");

            assertTrue(result.isSuccess());
            assertTrue(
                    filesystem.lastCommand.contains("stat -c"),
                    "glob should use stat for metadata");
            assertEquals(
                    List.of("/workspace/README.md", "/workspace/docs/guide.md"),
                    result.matches().stream().map(FileInfo::path).collect(Collectors.toList()));
        }

        @Test
        void glob_parsesSize() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            GlobResult result = filesystem.glob(RT, "*.md", "/workspace");

            assertTrue(result.isSuccess());
            assertEquals(
                    1024L,
                    result.matches().stream()
                            .filter(f -> f.path().equals("/workspace/README.md"))
                            .findFirst()
                            .orElseThrow()
                            .size());
        }

        @Test
        void glob_parsesModifiedAt() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            GlobResult result = filesystem.glob(RT, "*.md", "/workspace");

            assertTrue(result.isSuccess());
            String modifiedAt =
                    result.matches().stream()
                            .filter(f -> f.path().equals("/workspace/README.md"))
                            .findFirst()
                            .orElseThrow()
                            .modifiedAt();
            assertFalse(modifiedAt.isEmpty(), "modifiedAt should be populated");
        }

        @Test
        void ls_reportsFileSizeAndModifiedAt() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            LsResult result = filesystem.ls(RT, "/workspace");

            assertTrue(result.isSuccess());
            assertFalse(result.entries().isEmpty());

            FileInfo file =
                    result.entries().stream()
                            .filter(e -> e.path().equals("/workspace/readme.txt"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(12L, file.size());
            assertFalse(file.modifiedAt().isEmpty(), "file modifiedAt should be populated");
        }

        @Test
        void ls_reportsDirModifiedAt() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            LsResult result = filesystem.ls(RT, "/workspace");

            assertTrue(result.isSuccess());
            FileInfo dir =
                    result.entries().stream()
                            .filter(e -> e.path().equals("/workspace/docs"))
                            .findFirst()
                            .orElseThrow();
            assertTrue(dir.isDirectory());
            assertFalse(dir.modifiedAt().isEmpty(), "dir modifiedAt should be populated");
        }

        // ==================== 缺陷复现：执行失败被伪装成结果（#2961）
        // ====================

        @Test
        void ls_executeFailure_negativeExit_shouldFailWithCause() {
            LsResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed()).ls(RT, "/workspace");

            assertFalse(result.isSuccess(), "ls should fail when the command never ran");
            assertTrue(result.error().contains("/workspace"), "error should locate the target");
            assertTrue(result.error().contains("status=504"), "error should carry the cause");
        }

        @Test
        void ls_executeFailure_nullOutput_shouldFailWithExitCodeFallback() {
            LsResult result =
                    new FixedResponseFilesystem(new ExecuteResponse(null, -1, false))
                            .ls(RT, "/workspace");

            assertFalse(result.isSuccess(), "a null-output failure must not collapse into success");
            assertTrue(
                    result.error().contains("exit code -1"),
                    "error should fall back to the exit code when no diagnostic output exists");
        }

        @Test
        void ls_executeFailure_unknownExitCode_shouldFail() {
            LsResult result =
                    new FixedResponseFilesystem(new ExecuteResponse("unknown state", null, false))
                            .ls(RT, "/workspace");

            assertFalse(result.isSuccess(), "ls should fail when the exit code is unknown");
        }

        @Test
        void ls_executeFailure_timeout_shouldFailWithMessage() {
            LsResult result =
                    new FixedResponseFilesystem(
                                    new ExecuteResponse("Command timed out after 30s", 124, false))
                            .ls(RT, "/workspace");

            assertFalse(result.isSuccess(), "ls should fail when execution times out");
            assertTrue(result.error().contains("timed out"), "error should carry the cause");
        }

        @Test
        void ls_commandFailure_positiveExit_shouldFailWithOutput() {
            LsResult result =
                    new FixedResponseFilesystem(
                                    new ExecuteResponse("sh: stat: not found", 127, false))
                            .ls(RT, "/workspace");

            assertFalse(result.isSuccess(), "ls should fail when the command itself fails");
            assertTrue(result.error().contains("stat"), "error should carry the command output");
        }

        @Test
        void read_text_executeFailure_shouldFailInsteadOfErrorAsContent() {
            ReadResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed())
                            .read(RT, "/workspace/notes.txt", 0, 10);

            assertFalse(result.isSuccess(), "read should fail when the command never ran");
            assertTrue(result.error().contains("status=504"), "error should carry the cause");
        }

        @Test
        void read_binary_executeFailure_shouldFailInsteadOfFileNotFound() {
            ReadResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed())
                            .read(RT, "/workspace/logo.png", 0, 10);

            assertFalse(result.isSuccess(), "read should fail when the command never ran");
            assertTrue(
                    result.error().contains("status=504"),
                    "execution failure must not be mislabeled as file_not_found");
        }

        @Test
        void read_binary_commandFailure_shouldKeepFileNotFoundSignal() {
            ReadResult result =
                    new FixedResponseFilesystem(new ExecuteResponse("", 1, false))
                            .read(RT, "/workspace/logo.png", 0, 10);

            assertFalse(result.isSuccess(), "a missing file is still a failure");
            assertTrue(
                    result.error().contains("file_not_found"),
                    "a real command failure keeps the designed signal");
        }

        @Test
        void read_binary_timeout_shouldFailWithMessageInsteadOfFileNotFound() {
            ReadResult result =
                    new FixedResponseFilesystem(
                                    new ExecuteResponse("Command timed out after 30s", 124, false))
                            .read(RT, "/workspace/logo.png", 0, 10);

            assertFalse(result.isSuccess(), "read should fail when execution times out");
            assertTrue(
                    result.error().contains("timed out"),
                    "a timeout must not be mislabeled as file_not_found");
        }

        @Test
        void grep_executeFailure_shouldFailInsteadOfEmptySuccess() {
            GrepResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed())
                            .grep(RT, "pattern", "/workspace", null);

            assertFalse(result.isSuccess(), "grep should fail when the command never ran");
            assertTrue(result.error().contains("status=504"), "error should carry the cause");
        }

        @Test
        void glob_executeFailure_shouldFailInsteadOfErrorAsPaths() {
            GlobResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed())
                            .glob(RT, "*.md", "/workspace");

            assertFalse(result.isSuccess(), "glob should fail when the command never ran");
            assertTrue(result.error().contains("status=504"), "error should carry the cause");
        }

        @Test
        void edit_native_success_encodesParamsAndRunsInlineScript() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("__RESULT__{\"count\": 1}\n", 0, false));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess());
            assertEquals("/workspace/f.txt", result.path());
            assertEquals(1, result.occurrences());
            // 完全不创建暂存目录，也不传输文件。
            assertTrue(fs.uploadedFiles.isEmpty());
            assertTrue(fs.lastCommand.contains("python3 - "));
            assertTrue(fs.lastCommand.contains("'/workspace/f.txt'"));
            assertTrue(fs.lastCommand.contains("'" + base64("World") + "'"));
            assertTrue(fs.lastCommand.contains("'" + base64("Java") + "'"));
            assertTrue(fs.lastCommand.contains("__AGENTSCOPE_EDIT_PY__"));
            // Python 退出状态必须传到 Java，否则 127 不会触发后备逻辑。
            assertTrue(
                    fs.lastCommand.contains("exit $__ec"),
                    "exit code passthrough required: " + fs.lastCommand);
        }

        // ==================== 缺陷复现：编辑程序被压成单行
        // （#2571） ====================

        @Test
        void edit_generatedPythonProgram_isSeparatedByRealLineFeeds() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            filesystem.edit(RT, "/workspace/file.txt", "old", "new", false);

            String cmd = filesystem.lastCommand;
            assertTrue(
                    cmd.startsWith("python3 - "),
                    "edit should drive the replacement through a python3 program");
            // 回归缺陷把程序压成一行，因此不变量是各语句
            // 必须以真实换行符分隔。程序自身字符串字面量中仍可出现反斜杠 n
            // （CRLF 规范化器），这并无问题。
            assertTrue(
                    cmd.contains("import base64, os, sys, json, uuid\ntarget"),
                    "the python program must be separated by real line feeds");
            assertTrue(
                    cmd.contains("\ncount = text.count(old)"),
                    "every statement must be on its own line, not joined by an escape");
        }

        @Test
        void edit_executeFailure_shouldFailWithCause() {
            EditResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed())
                            .edit(RT, "/workspace/file.txt", "old", "new", false);

            assertFalse(result.isSuccess(), "edit should fail when the command never ran");
            assertTrue(result.error().contains("status=504"), "error should carry the cause");
        }

        @Test
        void edit_executeFailure_nullOutput_shouldFailWithExitCodeFallback() {
            EditResult result =
                    new FixedResponseFilesystem(new ExecuteResponse(null, -1, false))
                            .edit(RT, "/workspace/file.txt", "old", "new", false);

            assertFalse(result.isSuccess(), "edit should fail when the command never ran");
            assertTrue(
                    result.error().contains("exit code -1"),
                    "error should fall back to the exit code when no diagnostic output exists");
        }

        @Test
        void edit_native_writeFailedDetailWithErrorLikePath_notMisclassified() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse(
                            "__RESULT__{\"error\": \"write_failed\", \"detail\": \"[Errno 13]"
                                    + " '/ws/string_not_found/x'\"}\n",
                            0,
                            false));
            // write_failed 现在降级到传输；不能把路径名误判为
            // string_not_found 错误，因此后备逻辑仍须运行并报告
            // 自身的传输结果。
            fs.withDownloadResult(
                    List.of(FileDownloadResponse.fail("/ws/string_not_found/x", "nope")));

            EditResult result = fs.edit(RT, "/ws/string_not_found/x", "a", "b", false);

            assertFalse(result.isSuccess());
            assertFalse(result.error().contains("String not found in file"));
            assertTrue(fs.downloadedPaths.contains("/ws/string_not_found/x"));
        }

        @Test
        void edit_native_writeFailed_fallsBackToTransfer() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse(
                            "__RESULT__{\"error\": \"write_failed\", \"detail\": \"[Errno 13]\"}\n",
                            0,
                            false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess(), "write_failed should degrade: " + result.error());
            assertEquals(1, result.occurrences());
            assertTrue(fs.downloadedPaths.contains("/workspace/f.txt"));
            assertEquals(
                    "Hello Java!",
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8));
        }

        @Test
        void edit_native_multilineParams_enterCommandOnlyBase64Encoded() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("__RESULT__{\"count\": 1}\n", 0, false));
            String oldStr = "line2\"quote\\backslash\nline2b";
            String newStr = "replaced\"line\\here\nnewline";

            EditResult result = fs.edit(RT, "/workspace/f.txt", oldStr, newStr, false);

            assertTrue(result.isSuccess());
            assertTrue(
                    fs.lastCommand.contains("'" + base64(oldStr) + "'"),
                    "old should be present base64-encoded");
            assertTrue(
                    fs.lastCommand.contains("'" + base64(newStr) + "'"),
                    "new should be present base64-encoded");
            // 不得出现原始多行／带引号内容，否则会破坏单引号
            // 参数，换行还会把命令拆成额外行。
            assertFalse(
                    fs.lastCommand.contains(oldStr),
                    "raw user content must not leak into the command string");
            assertFalse(fs.lastCommand.contains("\n" + newStr));
        }

        @Test
        void edit_argumentsTooLarge_isRejectedWithoutExecuting() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("__RESULT__{\"count\": 1}\n", 0, false));
            // 100 KiB 文本经 base64 编码约为 137 KiB，超过 112 KiB 命令预算，
            // 也超过内核 128 KiB 单参数限制。
            String huge = "a".repeat(100 * 1024);

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", huge, false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("too large"));
            assertTrue(result.error().contains("Split the edit"));
            assertEquals(0, fs.executeCalls, "oversized arguments must not reach the sandbox");
            assertTrue(fs.uploadedFiles.isEmpty());
        }

        @Test
        void edit_native_stringNotFound_mapsError() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse("__RESULT__{\"error\": \"string_not_found\"}\n", 0, false));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "nonexistent", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("String not found"));
        }

        @Test
        void edit_native_multipleOccurrences_mapsCount() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse(
                            "__RESULT__{\"error\": \"multiple_occurrences\", \"count\": 3}\n",
                            0,
                            false));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "foo", "x", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("3 times"));
        }

        @Test
        void edit_transferPathReuploaded_contentAfterFallback() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess(), "fallback should succeed: " + result.error());
            // 后备路径恰好重新上传一个文件，即编辑后的内容。
            assertEquals(1, fs.uploadedFiles.size());
            assertEquals(
                    "Hello Java!",
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8));
        }

        @Test
        void edit_emptyOldString_failsFast() {
            EditSpyFilesystem fs = new EditSpyFilesystem();

            EditResult result = fs.edit(RT, "/workspace/f.txt", "", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("oldString must not be null or empty"));
            assertTrue(fs.uploadedFiles.isEmpty());
        }

        @Test
        void edit_pythonMissing_fallsBackToTransfer() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess(), "fallback should succeed: " + result.error());
            assertEquals(1, result.occurrences());
            assertTrue(fs.downloadedPaths.contains("/workspace/f.txt"));
            assertEquals(
                    "Hello Java!",
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8));
        }

        @Test
        void edit_pythonMissing_transferDownloadFails_reportsReadFailureNotMissingFile() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("python3: command not found", 127, false));
            fs.withDownloadResult(List.of());

            EditResult result = fs.edit(RT, "/workspace/missing.txt", "old", "new", false);

            assertFalse(result.isSuccess());
            // 下载失败不能证明文件不存在。
            assertFalse(result.error().contains("not found"));
            assertTrue(result.error().contains("Could not read file"));
        }

        @Test
        void edit_pythonMissing_transferDownloadError_carriesCause() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("python3: command not found", 127, false));
            fs.withDownloadResult(
                    List.of(FileDownloadResponse.fail("/workspace/f.txt", "permission denied")));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "old", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("Could not read file"));
            assertTrue(
                    result.error().contains("permission denied"), "underlying cause must survive");
        }

        @Test
        void edit_native_statFailure_mapsToWriteFailedNotRawTraceback() {
            // 若目标在脚本读取与 stat 之间消失，之前会抛出未捕获的
            // OSError，导致没有 __RESULT__ 行，Java 报告
            // "unexpected server response"，而不是映射后的错误。
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse(
                            "Traceback (most recent call last):\n  FileNotFoundError: '/gone.txt'"
                                    + "\n__RESULT__{\"error\": \"write_failed\", \"detail\":"
                                    + " \"[Errno 2] No such file or directory\"}\n",
                            0,
                            false));
            fs.withDownloadResult(
                    List.of(FileDownloadResponse.success("/gone.txt", "Hello".getBytes())));

            EditResult result = fs.edit(RT, "/gone.txt", "Hello", "Hi", false);

            // write_failed 被视为目标未改动，因此传输路径重试。
            assertTrue(result.isSuccess(), "write_failed should degrade: " + result.error());
            assertEquals(1, result.occurrences());
        }

        @Test
        void edit_pythonMissing_transferStringNotFound() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "hello world".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "nonexistent", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("String not found"));
            // 传输下载后未发现匹配，所以没有重新上传。
            assertTrue(fs.uploadedFiles.isEmpty());
        }

        @Test
        void edit_pythonMissing_transferMultipleOccurrences() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("python3: command not found", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "foo bar foo".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "foo", "x", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("appears"));
        }

        @Test
        void edit_pythonMissing_transferReplaceAll() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "a b a b a".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "a", "x", true);

            assertTrue(result.isSuccess());
            assertEquals(3, result.occurrences());
            assertEquals(
                    "x b x b x",
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8));
        }

        @Test
        void edit_pythonMissing_transferEmptyFile() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(FileDownloadResponse.success("/workspace/f.txt", new byte[0])));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "old", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("empty"));
        }

        @Test
        void edit_pythonMissing_transferUploadFails() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "hello world".getBytes())));
            fs.withTransferUploadFailure("disk full");

            EditResult result = fs.edit(RT, "/workspace/f.txt", "world", "Java", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("disk full"));
        }

        @Test
        void edit_pythonMissing_transferInvalidUtf8_failsInsteadOfReencoding() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            // 0xFF 永远不是合法 UTF-8 字节（属于旧式编码）；后备逻辑必须
            // 拒绝处理，而非替换它并静默改写文件。
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", new byte[] {'a', (byte) 0xFF, 'b'})));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "a", "x", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("not valid UTF-8"));
            // 没有重新上传任何内容。
            assertTrue(fs.uploadedFiles.isEmpty());
        }

        @Test
        void edit_pythonMissing_stderrOnly_exitZero_fallsBack() {
            // 某些后端在 stderr 报告 "python3: not found"，但末尾
            // shell 命令使整体退出码仍为 0（退出码透传之前，
            // 或只有 stderr 信号可用时）。
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 0, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(
                    result.isSuccess(),
                    "stderr-based detection should fall back: " + result.error());
            assertEquals(1, result.occurrences());
        }

        @Test
        void edit_nullNewString_isRejected_notTreatedAsDeletion() {
            EditSpyFilesystem fs = new EditSpyFilesystem();

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", null, false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("must not be null"));
            assertTrue(fs.uploadedFiles.isEmpty(), "nothing should be staged for a rejected call");
        }

        @Test
        void edit_emptyNewString_deletesWithNormalSemantics() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("__RESULT__{\"count\": 2}\n", 0, false));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "", false);

            assertTrue(result.isSuccess());
            assertEquals(2, result.occurrences());
            // 空 newString 编码为空 argv 项，表示真正删除。
            assertTrue(fs.lastCommand.contains("''"));
        }

        @Test
        void edit_pythonNotExecutable_exit126_fallsBackToTransfer() {
            // 126 表示找到 python3 但不可执行；缺少此处理会报告
            // "unexpected server response"，而不是降级到传输路径。
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse("sh: 1: python3: Permission denied\n", 126, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess(), "126 should fall back: " + result.error());
            assertEquals(1, result.occurrences());
        }

        @Test
        void edit_malformedResultJson_failsWithRawPayload() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("__RESULT__not-json{{", 0, false));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("not-json{{"));
        }
    }

    // ================================================================
    // 集成测试：真实 shell 执行，仅 Linux
    // ================================================================

    @Nested
    @EnabledOnOs(OS.LINUX)
    class LocalShellIntegrationTests {

        @TempDir Path tmpDir;

        @Test
        void ls_returnsRealSizeAndModifiedAt() throws IOException {
            byte[] content = "hello world\n".getBytes(StandardCharsets.UTF_8);
            Files.write(tmpDir.resolve("file.txt"), content);
            Files.createDirectory(tmpDir.resolve("subdir"));

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            LsResult result = fs.ls(RT, tmpDir.toString());

            assertTrue(result.isSuccess());
            assertEquals(2, result.entries().size());

            FileInfo file =
                    result.entries().stream()
                            .filter(e -> e.path().endsWith("file.txt"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(content.length, file.size());
            assertFalse(file.modifiedAt().isEmpty());

            FileInfo dir =
                    result.entries().stream()
                            .filter(FileInfo::isDirectory)
                            .findFirst()
                            .orElseThrow();
            assertFalse(dir.modifiedAt().isEmpty());
        }

        @Test
        void glob_returnsRealSizeAndModifiedAt() throws IOException {
            Files.write(tmpDir.resolve("a.md"), "aaa".getBytes(StandardCharsets.UTF_8));
            Path sub = Files.createDirectory(tmpDir.resolve("sub"));
            Files.write(sub.resolve("b.md"), "bbbbb".getBytes(StandardCharsets.UTF_8));

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            GlobResult result = fs.glob(RT, "**/*.md", tmpDir.toString());

            assertTrue(result.isSuccess());
            assertEquals(2, result.matches().size());

            FileInfo a =
                    result.matches().stream()
                            .filter(f -> f.path().endsWith("a.md"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(3L, a.size());
            assertFalse(a.modifiedAt().isEmpty());

            FileInfo b =
                    result.matches().stream()
                            .filter(f -> f.path().endsWith("b.md"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(5L, b.size());
            assertFalse(b.modifiedAt().isEmpty());
        }

        @Test
        void glob_emptyResultWhenNoMatch() {
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            GlobResult result = fs.glob(RT, "*.xyz", tmpDir.toString());
            assertTrue(result.isSuccess());
            assertTrue(result.matches().isEmpty());
        }

        // ==================== 缺陷复现：ls shell 吞掉错误 ====================

        @Test
        void ls_nonExistentPath_shouldReturnFail() {
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            LsResult r = fs.ls(RT, "/this/path/does/not/exist/at/all");
            assertFalse(r.isSuccess(), "ls on non-existent path should fail");
        }

        @Test
        void ls_filePath_shouldReturnFail() throws IOException {
            Path file = tmpDir.resolve("file.txt");
            Files.writeString(file, "content");
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            LsResult r = fs.ls(RT, file.toAbsolutePath().toString());
            assertFalse(r.isSuccess(), "ls on a file path should fail");
        }

        @Test
        void edit_simpleReplacement() throws IOException {
            Path file = tmpDir.resolve("test.txt");
            Files.writeString(file, "Hello World");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "World", "Java", false);

            assertTrue(result.isSuccess(), "edit should succeed: " + result.error());
            assertEquals("Hello Java", Files.readString(file));
            assertEquals(1, result.occurrences());
        }

        @Test
        void edit_withSpecialCharacters() throws IOException {
            Path file = tmpDir.resolve("special.txt");
            Files.writeString(file, "line1\nline2\"quote\\backslash\nline3");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result =
                    fs.edit(
                            RT,
                            file.toString(),
                            "line2\"quote\\backslash",
                            "replaced\"line\\here",
                            false);

            assertTrue(result.isSuccess(), "edit should succeed: " + result.error());
            assertEquals("line1\nreplaced\"line\\here\nline3", Files.readString(file));
            assertEquals(1, result.occurrences());
        }

        @Test
        void edit_replaceAll() throws IOException {
            Path file = tmpDir.resolve("replace.txt");
            Files.writeString(file, "a b a b a");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "a", "x", true);

            assertTrue(result.isSuccess(), "edit should succeed: " + result.error());
            assertEquals("x b x b x", Files.readString(file));
            assertEquals(3, result.occurrences());
        }

        @Test
        void edit_fileNotFound() {
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result =
                    fs.edit(RT, tmpDir.resolve("nonexistent.txt").toString(), "old", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("not found"));
        }

        @Test
        void edit_stringNotFound() throws IOException {
            Path file = tmpDir.resolve("missing.txt");
            Files.writeString(file, "Hello World");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "Goodbye", "Hi", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("String not found"));
        }

        @Test
        void edit_multipleOccurrencesWithoutReplaceAll() throws IOException {
            Path file = tmpDir.resolve("multi.txt");
            Files.writeString(file, "foo bar foo baz foo");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "foo", "x", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("appears"));
            // 有歧义的替换必须保持文件不变。
            assertEquals("foo bar foo baz foo", Files.readString(file));
        }

        @Test
        void edit_pathContainingHeredocDelimiter_doesNotTruncateCommand() throws IOException {
            // 单引号目标参数中的换行仍属于参数本身，
            // 不会结束命令行，heredoc 正文只在之后读取。
            // 因此路径中即使某行等于分隔符，也不能提前关闭 heredoc。
            Path dir = Files.createDirectory(tmpDir.resolve("dir__AGENTSCOPE_EDIT_PY__"));
            Path file = dir.resolve("f.txt");
            Files.writeString(file, "Hello World");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "World", "Java", false);

            assertTrue(
                    result.isSuccess(),
                    "delimiter-shaped path should still edit: " + result.error());
            assertEquals("Hello Java", Files.readString(file));
        }

        // ==================== heredoc 引号边界：文件路径是最后一处
        // 仍需经过 shell 引号处理的用户输入 ====================

        @Test
        void edit_pathContainingSingleQuote_editsTheRightFile() throws IOException {
            Path file = tmpDir.resolve("it's a file.txt");
            Files.writeString(file, "Hello World");
            // 另建一个名称仅差一次替换的同级文件；若引号处理失效，编辑可能
            // 错误落到此文件。
            Path decoy = tmpDir.resolve("its a file.txt");
            Files.writeString(decoy, "Hello World");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "World", "Java", false);

            assertTrue(result.isSuccess(), "single-quoted path should edit: " + result.error());
            assertEquals("Hello Java", Files.readString(file));
            assertEquals("Hello World", Files.readString(decoy), "decoy must be untouched");
        }

        @Test
        void edit_pathContainingBacktick_doesNotTriggerCommandSubstitution() throws IOException {
            Path file = tmpDir.resolve("`id`.txt");
            Files.writeString(file, "Hello World");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "World", "Java", false);

            assertTrue(result.isSuccess(), "backtick path should edit: " + result.error());
            assertEquals("Hello Java", Files.readString(file));
        }

        @Test
        void edit_paramsEndingWithNewline_surviveEncodingAndHeredoc() throws IOException {
            Path file = tmpDir.resolve("trailing.txt");
            Files.writeString(file, "keep\nreplace me\n");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            // old 带末尾换行；命令参数内换行可能把命令拆成多行，
            // 因此此测试固定验证端到端编码。
            EditResult result = fs.edit(RT, file.toString(), "replace me\n", "done\n", false);

            assertTrue(
                    result.isSuccess(), "trailing-newline params should edit: " + result.error());
            assertEquals(1, result.occurrences());
            assertEquals("keep\ndone\n", Files.readString(file));
        }

        @Test
        void edit_crlfFile_matchesLfPattern() throws IOException {
            Path file = tmpDir.resolve("crlf.txt");
            Files.writeString(file, "alpha\r\nbeta\r\ngamma\r\n");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "beta", "BETA", false);

            assertTrue(result.isSuccess(), "CRLF file must match an LF pattern: " + result.error());
            assertEquals(1, result.occurrences());
            assertEquals("alpha\nBETA\ngamma\n", Files.readString(file));
        }

        @Test
        void edit_crlf_parityBetweenNativeAndFallback() throws IOException {
            // 原生路径与传输后备路径必须接受或拒绝相同编辑；
            // 否则编辑是否成功将取决于是否恰好安装 python3。
            Path nativeFile = tmpDir.resolve("crlf-native.txt");
            Files.writeString(nativeFile, "one\r\ntwo\r\n");
            EditResult nativeResult =
                    new LocalShellSandboxFilesystem()
                            .edit(RT, nativeFile.toString(), "two", "2", false);

            Path fallbackFile = tmpDir.resolve("crlf-fallback.txt");
            Files.writeString(fallbackFile, "one\r\ntwo\r\n");
            EditResult fallbackResult =
                    new LocalShellFallbackFilesystem(false)
                            .edit(RT, fallbackFile.toString(), "two", "2", false);

            assertTrue(nativeResult.isSuccess(), "native: " + nativeResult.error());
            assertTrue(fallbackResult.isSuccess(), "fallback: " + fallbackResult.error());
            assertEquals(nativeResult.occurrences(), fallbackResult.occurrences());
            assertEquals(Files.readString(nativeFile), Files.readString(fallbackFile));
        }

        @Test
        void edit_crlf_ambiguousOccurrenceStillRejected() throws IOException {
            // 原生路径对 CRLF 内容也必须触发 >1 保护，与
            // 共享替换语义一致。
            Path file = tmpDir.resolve("crlf-multi.txt");
            Files.writeString(file, "a\r\na\r\n");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "a", "x", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("2 times"));
            assertEquals("a\r\na\r\n", Files.readString(file), "file must be untouched");
        }

        @Test
        void edit_symlinkTarget_preservesLinkAndWritesThrough() throws IOException {
            Path real = tmpDir.resolve("real.txt");
            Files.writeString(real, "Hello World");
            Path link = tmpDir.resolve("link.txt");
            Files.createSymbolicLink(link, real.getFileName());

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, link.toString(), "World", "Java", false);

            assertTrue(result.isSuccess(), "edit through link should succeed: " + result.error());
            // 链接自身仍是符号链接，os.replace 不得替换链接。
            assertTrue(Files.isSymbolicLink(link), "symlink must survive the edit");
            // 内容写到真实目标。
            assertEquals("Hello Java", Files.readString(real));
        }

        @Test
        void edit_fallback_writeThroughUploadPreservesSymlink() throws IOException {
            // 强制传输后备（python3“缺失”）：上传时打开路径的 exec 风格后端
            // 采用 shell 重定向语义，会穿透链接写入目标。
            Path real = tmpDir.resolve("real2.txt");
            Files.writeString(real, "Hello World");
            Path link = tmpDir.resolve("link2.txt");
            Files.createSymbolicLink(link, real.getFileName());

            LocalShellFallbackFilesystem fs = new LocalShellFallbackFilesystem(false);
            EditResult result = fs.edit(RT, link.toString(), "World", "Java", false);

            assertTrue(result.isSuccess(), "fallback should succeed: " + result.error());
            assertEquals(1, result.occurrences());
            assertTrue(Files.isSymbolicLink(link), "write-through upload preserves the link");
            assertEquals("Hello Java", Files.readString(real));
        }

        @Test
        void edit_fallback_replacingUploadReplacesSymlink() throws IOException {
            // 上传时替换路径（在 filePath 临时写入后重命名）的后端会断开
            // 链接，这是文档明确的与原生路径的差异，由后端负责。
            Path real = tmpDir.resolve("real3.txt");
            Files.writeString(real, "Hello World");
            Path link = tmpDir.resolve("link3.txt");
            Files.createSymbolicLink(link, real.getFileName());

            LocalShellFallbackFilesystem fs = new LocalShellFallbackFilesystem(true);
            EditResult result = fs.edit(RT, link.toString(), "World", "Java", false);

            assertTrue(result.isSuccess(), "fallback should succeed: " + result.error());
            assertFalse(Files.isSymbolicLink(link), "replacing upload replaces the link");
            assertEquals("Hello Java", Files.readString(link));
            assertEquals("Hello World", Files.readString(real), "original target untouched");
        }

        @Test
        void edit_replaceAll_occurrencesParityBetweenNativeAndFallback() throws IOException {
            // 相同输入经过两条路径必须报告相同次数和最终内容，
            // 防止两种实现语义漂移。
            Path nativeFile = tmpDir.resolve("parity-native.txt");
            Files.writeString(nativeFile, "a b a b a");
            EditResult nativeResult =
                    new LocalShellSandboxFilesystem()
                            .edit(RT, nativeFile.toString(), "a", "x", true);

            Path fallbackFile = tmpDir.resolve("parity-fallback.txt");
            Files.writeString(fallbackFile, "a b a b a");
            EditResult fallbackResult =
                    new LocalShellFallbackFilesystem(false)
                            .edit(RT, fallbackFile.toString(), "a", "x", true);

            assertTrue(nativeResult.isSuccess(), "native: " + nativeResult.error());
            assertTrue(fallbackResult.isSuccess(), "fallback: " + fallbackResult.error());
            assertEquals(nativeResult.occurrences(), fallbackResult.occurrences());
            assertEquals(3, fallbackResult.occurrences());
            assertEquals(Files.readString(nativeFile), Files.readString(fallbackFile));
            assertEquals("x b x b x", Files.readString(fallbackFile));
        }
    }

    // ================================================================
    // 测试辅助方法
    // ================================================================

    private static final class EditSpyFilesystem extends BaseSandboxFilesystem {

        final List<String> downloadedPaths = new ArrayList<>();
        final List<Map.Entry<String, byte[]>> uploadedFiles = new ArrayList<>();
        String lastCommand;
        int executeCalls;
        private List<FileDownloadResponse> cannedDownload = List.of();
        private ExecuteResponse cannedExecute =
                new ExecuteResponse("__RESULT__{\"count\": 1}\n", 0, false);
        private String uploadFailure;
        private String transferUploadFailure;

        void withDownloadResult(List<FileDownloadResponse> responses) {
            this.cannedDownload = responses;
        }

        void withExecuteResult(ExecuteResponse response) {
            this.cannedExecute = response;
        }

        void withUploadFailure(String error) {
            this.uploadFailure = error;
        }

        /**
         * 仅让非参数上传失败，即传输阶段重新上传编辑后内容的操作。
         */
        void withTransferUploadFailure(String error) {
            this.transferUploadFailure = error;
        }

        @Override
        public String id() {
            return "edit-spy";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            executeCalls++;
            lastCommand = command;
            return cannedExecute;
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            uploadedFiles.addAll(files);
            List<FileUploadResponse> results = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : files) {
                if (uploadFailure != null || transferUploadFailure != null) {
                    String err = uploadFailure != null ? uploadFailure : transferUploadFailure;
                    results.add(FileUploadResponse.fail(entry.getKey(), err));
                } else {
                    results.add(FileUploadResponse.success(entry.getKey()));
                }
            }
            return results;
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            downloadedPaths.addAll(paths);
            return cannedDownload;
        }
    }

    private static final class FakeSandboxFilesystem extends BaseSandboxFilesystem {

        String lastCommand;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            lastCommand = command;
            if (command.startsWith("if [ ! -e ") && command.contains("stat -c")) {
                return new ExecuteResponse(
                        "DIR:/workspace/docs\t1719300000\n"
                                + "FILE:/workspace/readme.txt\t12\t1719300000\n",
                        0,
                        false);
            }
            if (command.startsWith("find ") && command.contains("while IFS=")) {
                return new ExecuteResponse(
                        "/workspace/README.md\t1024\t1719300000\n"
                                + "/workspace/docs/guide.md\t512\t1719300000\n",
                        0,
                        false);
            }
            return new ExecuteResponse("", 0, false);
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            return List.of();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            return List.of();
        }
    }

    /**
     * execute() 始终返回固定响应，无需真实沙箱即可模拟任意执行层结果（成功或失败）。
     */
    private static final class FixedResponseFilesystem extends BaseSandboxFilesystem {

        private final ExecuteResponse response;

        FixedResponseFilesystem(ExecuteResponse response) {
            this.response = response;
        }

        @Override
        public String id() {
            return "fixed-response";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return response;
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            return List.of();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            return List.of();
        }
    }

    private static final class LocalShellSandboxFilesystem extends BaseSandboxFilesystem {

        @Override
        public String id() {
            return "local-shell";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            try {
                Process p =
                        new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
                String output =
                        new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = p.waitFor();
                return new ExecuteResponse(output, exitCode, false);
            } catch (Exception e) {
                return new ExecuteResponse("execute failed: " + e.getMessage(), 1, false);
            }
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            List<FileUploadResponse> results = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : files) {
                try {
                    Path dest = Path.of(entry.getKey());
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    Files.write(dest, entry.getValue());
                    results.add(FileUploadResponse.success(entry.getKey()));
                } catch (IOException e) {
                    results.add(FileUploadResponse.fail(entry.getKey(), e.getMessage()));
                }
            }
            return results;
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            List<FileDownloadResponse> results = new ArrayList<>();
            for (String path : paths) {
                try {
                    byte[] content = Files.readAllBytes(Path.of(path));
                    results.add(FileDownloadResponse.success(path, content));
                } catch (IOException e) {
                    results.add(FileDownloadResponse.fail(path, e.getMessage()));
                }
            }
            return results;
        }
    }

    /**
     * 真实文件后端，强制使用传输后备（python3“缺失”），让测试选择上传语义：穿透写入（打开路径，跟随符号链接）或替换（先移除路径，断开符号链接）。
     */
    private static final class LocalShellFallbackFilesystem extends BaseSandboxFilesystem {

        private final boolean replaceOnUpload;

        LocalShellFallbackFilesystem(boolean replaceOnUpload) {
            this.replaceOnUpload = replaceOnUpload;
        }

        @Override
        public String id() {
            return "local-shell-no-python";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            if (command.startsWith("python3 ")) {
                return new ExecuteResponse("python3: command not found", 127, false);
            }
            try {
                Process p =
                        new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
                String output =
                        new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return new ExecuteResponse(output, p.waitFor(), false);
            } catch (Exception e) {
                return new ExecuteResponse("execute failed: " + e.getMessage(), 1, false);
            }
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            List<FileUploadResponse> results = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : files) {
                try {
                    Path dest = Path.of(entry.getKey());
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    if (replaceOnUpload) {
                        Files.deleteIfExists(dest);
                    }
                    Files.write(dest, entry.getValue());
                    results.add(FileUploadResponse.success(entry.getKey()));
                } catch (IOException e) {
                    results.add(FileUploadResponse.fail(entry.getKey(), e.getMessage()));
                }
            }
            return results;
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            List<FileDownloadResponse> results = new ArrayList<>();
            for (String path : paths) {
                try {
                    results.add(
                            FileDownloadResponse.success(path, Files.readAllBytes(Path.of(path))));
                } catch (IOException e) {
                    results.add(FileDownloadResponse.fail(path, e.getMessage()));
                }
            }
            return results;
        }
    }
}
