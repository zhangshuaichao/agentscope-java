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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import redis.clients.jedis.JedisPooled;

/**
 * WorkspaceSharedStoreExample：通过 Redis 展示 {@link DistributedStore} 一行式分布式配置。
 * <ol><li>{@link RedisDistributedStore} 的 {@code .distributedStore(...)} 配置基于 Redis 的 AgentStateStore、BaseStore、SandboxSnapshot 及 ExecutionGuard。
 * <li>两个实例共享 Redis，模拟跨副本记忆与会话连续性。
 * <li>用户状态位于隔离命名空间。</ol>
 * <p>需要运行中的 Redis（默认 {@code localhost:6379}）与 {@code DASHSCOPE_API_KEY}；可用 Docker 启动 Redis，通过 {@code REDIS_URL} 覆盖地址。命令：
 * <pre>
 *   docker run -d --name redis -p 6379:6379 redis:7
 * </pre>
 * <pre>
 *   export REDIS_URL=redis://your-redis-host:6379
 * </pre>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceSharedStoreExample
 * </pre>
 */
public class WorkspaceSharedStoreExample {

    private static final String DEFAULT_REDIS_URL = "redis://localhost:6379";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Distributed Store — One-line Redis Configuration");
        System.out.println("=".repeat(60) + "\n");

        String redisUrl = System.getenv().getOrDefault("REDIS_URL", DEFAULT_REDIS_URL);
        System.out.println("Connecting to Redis: " + redisUrl + "\n");

        JedisPooled jedis = new JedisPooled(java.net.URI.create(redisUrl));
        DistributedStore store = RedisDistributedStore.fromJedis(jedis, "agentscope:example:");

        Path workspace = Files.createTempDirectory("agentscope-shared-store-example");

        Files.writeString(
                workspace.resolve("AGENTS.md"),
                """
                # Shared Store Agent

                You are a helpful assistant.
                Keep answers concise (under two sentences).
                """);

        // ── 构建副本 1，一行完成分布式配置 ──

        System.out.println("── Building replica-1 and replica-2 with distributedStore ──\n");

        HarnessAgent replica1 =
                HarnessAgent.builder()
                        .name("shared-agent")
                        .sysPrompt("You are a helpful assistant.")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .distributedStore(store)
                        .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
                        .build();

        // ── Alice 与副本 1 对话 ──

        System.out.println("── Alice talks to replica-1 ──\n");

        RuntimeContext aliceCtx =
                RuntimeContext.builder().userId("alice").sessionId("alice-s1").build();

        Msg r1 =
                replica1.call(new UserMessage("My favorite color is blue. Remember it."), aliceCtx)
                        .block();
        System.out.println("Replica-1 reply: " + (r1 != null ? r1.getTextContent() : "(null)"));

        // ── Bob 与副本 1 对话，与 Alice 隔离 ──

        System.out.println("\n── Bob talks to replica-1 (separate namespace) ──\n");

        RuntimeContext bobCtx = RuntimeContext.builder().userId("bob").sessionId("bob-s1").build();

        Msg r2 =
                replica1.call(new UserMessage("My favorite color is red. Remember it."), bobCtx)
                        .block();
        System.out.println("Replica-1 reply: " + (r2 != null ? r2.getTextContent() : "(null)"));

        // ── 构建副本 2，共用存储展示跨副本连续性 ──

        HarnessAgent replica2 =
                HarnessAgent.builder()
                        .name("shared-agent")
                        .sysPrompt("You are a helpful assistant.")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .distributedStore(store)
                        .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
                        .build();

        // ── Alice 在副本 2 恢复对话 ──

        System.out.println("\n── Alice resumes on replica-2 (cross-replica continuity) ──\n");

        Msg r3 = replica2.call(new UserMessage("What is my favorite color?"), aliceCtx).block();
        System.out.println(
                "Replica-2 reply (Alice): " + (r3 != null ? r3.getTextContent() : "(null)"));

        // ── Bob 在副本 2 恢复对话 ──

        System.out.println("\n── Bob resumes on replica-2 ──\n");

        Msg r4 = replica2.call(new UserMessage("What is my favorite color?"), bobCtx).block();
        System.out.println(
                "Replica-2 reply (Bob): " + (r4 != null ? r4.getTextContent() : "(null)"));

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Done. Both users' state survived cross-replica migration.");
        System.out.println("=".repeat(60));
    }
}
