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
package io.agentscope.harness.agent.filesystem.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesystemWithShellTest {

    @Test
    void outputCharset_usesNativeEncodingOnWindows() {
        assertEquals(
                Charset.forName("windows-1252"),
                LocalFilesystemWithShell.outputCharset("Windows 10", "windows-1252"));
    }

    @Test
    void outputCharset_usesUtf8OnNonWindowsSystems() {
        assertEquals(StandardCharsets.UTF_8, LocalFilesystemWithShell.outputCharset("Linux"));
    }

    @Test
    void outputCharset_fallsBackToDefaultWhenWindowsNativeEncodingIsUnavailable() {
        assertEquals(
                Charset.defaultCharset(),
                LocalFilesystemWithShell.outputCharset("Windows 10", null));
    }

    @Test
    void execute_outputLargerThanOsPipeBufferCompletesWithoutDeadlock(@TempDir Path tempDir) {
        // 约 68—72 KB 标准输出，超过操作系统管道缓冲区（Windows 约 4 KB，Linux 64 KB），
        // 但低于默认 maxOutputBytes 上限。在 stdout/stderr 与 waitFor 并发排空之前，
        // 此场景会死锁并误报超时（退出码 124）。
        int lines = 4000;
        String payload = "0123456789abcdef"; // 每行 16 个字符
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String command =
                windows
                        ? "for /l %i in (1,1," + lines + ") do @echo " + payload
                        : "i=0; while [ \"$i\" -lt "
                                + lines
                                + " ]; do echo "
                                + payload
                                + "; i=$((i+1)); done";

        LocalFilesystemWithShell fs = new LocalFilesystemWithShell(tempDir);
        ExecuteResponse resp = fs.execute(null, command, 60);

        assertEquals(0, resp.exitCode(), "unexpected exit code, output: " + resp.output());
        assertFalse(resp.truncated());
        assertEquals(lines, resp.output().split(payload, -1).length - 1);
    }
}
