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

/**
 * 沙箱命令执行结果。
 *
 * @param exitCode 进程退出码（0 表示成功）
 * @param stdout 捕获的标准输出
 * @param stderr 捕获的标准错误
 * @param truncated 输出是否因超过最大捕获大小而截断
 */
public record ExecResult(int exitCode, String stdout, String stderr, boolean truncated) {

    /**
     * 命令退出码为 0 时返回 {@code true}。
     *
     * @return 退出码为 0 时为 true
     */
    public boolean ok() {
        return exitCode == 0;
    }

    /**
     * 返回合并的标准输出和标准错误；标准错误非空时添加 "[stderr]" 前缀。
     *
     * @return 合并后的输出字符串
     */
    public String combinedOutput() {
        if (stderr == null || stderr.isBlank()) {
            return stdout != null ? stdout : "";
        }
        String out = stdout != null ? stdout : "";
        return out + (out.isBlank() ? "" : "\n") + "[stderr] " + stderr;
    }
}
