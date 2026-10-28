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
package io.agentscope.harness.agent.sandbox.impl.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 针对 {@link DockerSandbox} 组装的 {@code docker run} 命令进行单元测试，聚焦保持容器存活的入口程序。
 *
 * <p>问题 #2884 回归防护：旧入口 {@code while :; do sleep 3600; done} 使 PID 1（{@code sh}）阻塞于前台 {@code sleep}，无法及时处理 SIGTERM，导致 {@code docker stop --time=30} 等待完整 30 秒后才 SIGKILL，每轮智能体调用都多出约 30 秒销毁延迟。入口应安装 SIGTERM trap 并等待后台 sleep，使 PID 1 及时退出。
 */
class DockerSandboxRunCommandTest {

    @SuppressWarnings("unchecked")
    private static List<String> buildRunCommand(DockerSandboxState state, String containerName)
            throws Exception {
        DockerSandbox sandbox = new DockerSandbox(state);
        Method m = DockerSandbox.class.getDeclaredMethod("buildDockerRunCommand", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(sandbox, containerName);
    }

    private static DockerSandboxState minimalState() {
        DockerSandboxState state = new DockerSandboxState();
        state.setImage("ubuntu:22.04");
        state.setWorkspaceRoot("/workspace");
        state.setWorkspaceSpec(new WorkspaceSpec());
        return state;
    }

    @Test
    void entrypointInstallsSigtermTrapSoDockerStopReturnsPromptly() throws Exception {
        List<String> cmd = buildRunCommand(minimalState(), "agentscope-sandbox-test");

        // 入口程序是末尾三个词元：sh -c <script>。
        int n = cmd.size();
        assertEquals("sh", cmd.get(n - 3));
        assertEquals("-c", cmd.get(n - 2));

        String script = cmd.get(n - 1);
        // 必须安装 SIGTERM trap，使 PID 1（sh）在 `docker stop` 时退出。
        assertTrue(script.contains("trap"), "entrypoint must install a signal trap: " + script);
        assertTrue(script.contains("TERM"), "entrypoint must trap SIGTERM: " + script);
        // sleep 必须在后台运行并等待，使 trap 能中断等待，
        // 前台 sleep 会将 trap 延迟到 sleep 返回之后。
        assertTrue(
                script.contains("sleep 3600 &"),
                "sleep must be backgrounded so wait is interruptible: " + script);
        assertTrue(
                script.contains("wait"),
                "entrypoint must wait on the backgrounded sleep: " + script);
    }

    @Test
    void entrypointDoesNotUseForegroundSleepLoop() throws Exception {
        List<String> cmd = buildRunCommand(minimalState(), "agentscope-sandbox-test");
        String script = cmd.get(cmd.size() - 1);

        // 回归：旧前台 sleep 循环吞掉 SIGTERM。
        assertFalse(
                script.equals("while :; do sleep 3600; done"),
                "must not use the SIGTERM-ignoring foreground sleep loop");
    }

    @Test
    void entrypointUsesOnlyPortableShellPrimitives() throws Exception {
        List<String> cmd = buildRunCommand(minimalState(), "agentscope-sandbox-test");
        String script = cmd.get(cmd.size() - 1);

        // `sleep infinity` 是 GNU coreutils 扩展，BusyBox/Alpine 不支持，
        // 会导致最小镜像容器无法启动，因此入口必须避免使用它。
        assertFalse(
                script.contains("sleep infinity"),
                "entrypoint must not depend on non-portable `sleep infinity`: " + script);
    }

    @Test
    void runCommandStartsWithDetachedNamedRun() throws Exception {
        List<String> cmd = buildRunCommand(minimalState(), "agentscope-sandbox-test");

        assertEquals(
                List.of("docker", "run", "-d", "--name", "agentscope-sandbox-test"),
                cmd.subList(0, 5));
        // 镜像位于入口程序词元之前。
        assertEquals("ubuntu:22.04", cmd.get(cmd.size() - 4));
    }
}
