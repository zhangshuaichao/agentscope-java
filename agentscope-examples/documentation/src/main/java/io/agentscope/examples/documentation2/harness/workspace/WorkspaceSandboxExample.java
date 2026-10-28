/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.harness.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WorkspaceSandboxExample：文件系统模式 2，在隔离 Docker 容器中操作文件并执行命令，工作区可跨调用保留；宿主仍负责 Docker、投影文件与配置资源，不能把它当作无需安全配置的绝对隔离。
 * <ol><li>{@link DockerFilesystemSpec} 将文件与 Shell 操作路由到容器。
 * <li>{@link IsolationScope#USER} 为每用户分配沙箱，同用户复用或从快照恢复。
 * <li>同用户下次调用可见上次文件。
 * <li>每次启动按内容哈希同步宿主 {@code AGENTS.md}、{@code skills/} 等。</ol>
 * <p>前提：Docker 可用（{@code docker info} 成功）、{@code ubuntu:24.04} 已拉取或可拉取、设置 {@code DASHSCOPE_API_KEY}。
 * <p>多副本通过 {@link io.agentscope.harness.agent.DistributedStore} 共享沙箱状态，自动组装 {@code AgentStateStore}、{@code SandboxSnapshotSpec} 与 {@code SandboxExecutionGuard}。示例及运行命令：
 * <pre>
 *   DistributedStore store = RedisDistributedStore.fromJedis(jedis);
 *   HarnessAgent.builder()
 *       .distributedStore(store)
 *       .filesystem(new DockerFilesystemSpec()
 *           .image("ubuntu:24.04")
 *           .isolationScope(IsolationScope.USER))
 *       .build();
 * </pre>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceSandboxExample
 * </pre>
 */
public class WorkspaceSandboxExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Filesystem Mode 2 — Docker Sandbox (isolated execution)");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-sandbox-example");

        Files.writeString(
                workspace.resolve("AGENTS.md"),
                """
                # Sandbox Agent

                You are a coding assistant running inside a Docker sandbox.
                All file operations and shell commands execute inside the container.
                The host is untouched.
                Keep answers concise (under two sentences).
                """);

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("sandbox-agent")
                        .sysPrompt("You are a coding assistant in a Docker sandbox.")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .filesystem(
                                new DockerFilesystemSpec()
                                        .image("ubuntu:24.04")
                                        .isolationScope(IsolationScope.USER))
                        .build();

        // ── Alice 在沙箱中运行命令 ──

        System.out.println("── Alice call 1: create a file inside the sandbox ──\n");

        RuntimeContext aliceCtx =
                RuntimeContext.builder().userId("alice").sessionId("alice-s1").build();

        System.out.print("Agent: ");
        agent.streamEvents(
                        new UserMessage(
                                "Create a file /workspace/hello.txt containing 'Hello from sandbox'"
                                        + " and then cat it to verify."),
                        aliceCtx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n  [tool] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        // ── Alice 第二次调用，验证跨调用持久化 ──

        System.out.println("\n\n── Alice call 2: verify the file survives across calls ──\n");

        System.out.print("Agent: ");
        agent.streamEvents(
                        new UserMessage("Read /workspace/hello.txt — does it still exist?"),
                        aliceCtx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n  [tool] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        // ── Bob 使用独立沙箱，不能看到 Alice 文件 ──

        System.out.println("\n\n── Bob: separate sandbox (USER isolation) ──\n");

        RuntimeContext bobCtx = RuntimeContext.builder().userId("bob").sessionId("bob-s1").build();

        Msg bobReply =
                agent.call(
                                new UserMessage(
                                        "Does /workspace/hello.txt exist? Just answer yes or no."),
                                bobCtx)
                        .block();
        System.out.println(
                "Bob reply: " + (bobReply != null ? bobReply.getTextContent() : "(null)"));
        System.out.println("(Expected: no — Bob has a separate sandbox)");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Done. Alice and Bob ran in isolated Docker containers.");
        System.out.println("=".repeat(60));
    }
}
