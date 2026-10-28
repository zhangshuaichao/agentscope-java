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
package io.agentscope.harness.agent.example;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 示例：采用不同 {@link IsolationScope} 级别的分布式共享远程文件系统模式。
 *
 * <h2>背景</h2>
 * <p>远程模式将选定路径（如 {@code MEMORY.md}、{@code memory/}）路由到共享键值存储（此处为 {@link InMemoryStore}，生产环境通常使用 Redis 或其他分布式存储）。{@code IsolationScope} 控制存储命名空间前缀，从而决定哪些智能体调用能读取彼此数据。
 * <p>演示三种作用域：
 * <ul>
 *   <li>{@link IsolationScope#SESSION}：每个会话独立命名空间；同会话调用共享数据，不同会话看到空命名空间。</li>
 *   <li>{@link IsolationScope#USER}：同用户全部会话共享命名空间，不同用户使用隔离命名空间。</li>
 *   <li>{@link IsolationScope#AGENT}：每个智能体的所有用户与会话共享命名空间；任意调用方写入对同智能体其他调用方可见。</li>
 * </ul>
 * <p>断言使用作用域生成的命名空间键直接检查 {@link InMemoryStore}：
 * <ul>
 *   <li>{@code SESSION}：{@code agents/<agentId>/sessions/<sessionId>/root/MEMORY.md}</li>
 *   <li>{@code USER}：{@code agents/<agentId>/users/<userId>/root/MEMORY.md}</li>
 *   <li>{@code AGENT}：{@code agents/<agentId>/shared/root/MEMORY.md}</li>
 * </ul>
 * <p>并非所有场景都调用 {@link HarnessAgent#call}；尽可能直接使用 {@link io.agentscope.harness.agent.workspace.WorkspaceManager#writeUtf8WorkspaceRelative}，让示例聚焦命名空间路由，而非智能体对话机制。
 */
@HarnessQuiescence
class RemoteFilesystemIsolationScopeExampleTest {

    @TempDir Path workspace;

    // -------------------------------------------------------------------------
    // 场景 A：SESSION 作用域
    // -------------------------------------------------------------------------

    /**
     * SESSION 作用域：session-1 写入对 session-2 不可见。
     *
     * <p>存储命名空间为 {@code agents/<agentId>/sessions/<sessionId>}，因此每个会话拥有完全独立的 MEMORY.md。
     */
    @Test
    void sessionScope_differentSessions_isolatedNamespaces() throws Exception {
        Files.createDirectories(workspace);
        InMemoryStore store = new InMemoryStore();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(
                                new RemoteFilesystemSpec(store)
                                        .isolationScope(IsolationScope.SESSION))
                        .stateStore(stateStore())
                        .build()) {

            // 以 session-1 调用并写 MEMORY.md。
            agent.call(userMsg("from session-1"), ctx("session-1", "alice")).block();
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            ctx("session-1", "alice"), "MEMORY.md", "session-1 notes");

            // 验证数据落到 session-1 命名空间。
            assertNotNull(
                    store.get(
                            List.of("agents", "assistant", "sessions", "session-1", "root"),
                            "/MEMORY.md"),
                    "MEMORY.md should be stored under the session-1 namespace");

            // 验证 session-2 命名空间为空，实现隔离。
            assertNull(
                    store.get(
                            List.of("agents", "assistant", "sessions", "session-2", "root"),
                            "/MEMORY.md"),
                    "session-2 namespace should be empty");
        }
    }

    /**
     * SESSION 作用域：同会话写入可跨调用共享。
     */
    @Test
    void sessionScope_sameSession_sharedNamespace() throws Exception {
        Files.createDirectories(workspace);
        InMemoryStore store = new InMemoryStore();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(
                                new RemoteFilesystemSpec(store)
                                        .isolationScope(IsolationScope.SESSION))
                        .stateStore(stateStore())
                        .build()) {

            // 首次调用在 session-1 下写 MEMORY.md。
            agent.call(userMsg("call 1"), ctx("session-1", "alice")).block();
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            ctx("session-1", "alice"), "MEMORY.md", "shared memory");

            // 相同会话的第二次调用可以读取。
            agent.call(userMsg("call 2"), ctx("session-1", "alice")).block();

            assertNotNull(
                    store.get(
                            List.of("agents", "assistant", "sessions", "session-1", "root"),
                            "/MEMORY.md"),
                    "MEMORY.md should persist in the session-1 namespace across calls");
        }
    }

    // -------------------------------------------------------------------------
    // 场景 B：USER 作用域
    // -------------------------------------------------------------------------

    /**
     * USER 作用域：同一用户不同会话共享一个命名空间。
     *
     * <p>命名空间为 {@code agents/<agentId>/users/<userId>}，所以 session-A 写入立即对同用户 session-B 可见。
     */
    @Test
    void userScope_sameUser_sharedAcrossSessions() throws Exception {
        Files.createDirectories(workspace);
        InMemoryStore store = new InMemoryStore();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(
                                new RemoteFilesystemSpec(store).isolationScope(IsolationScope.USER))
                        .stateStore(stateStore())
                        .build()) {

            // 以 alice / session-a 调用并写 MEMORY.md。
            agent.call(userMsg("hi from session-a"), ctx("session-a", "alice")).block();
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            ctx("session-a", "alice"), "MEMORY.md", "alice's memory");

            // 条目落到用户作用域命名空间。
            assertNotNull(
                    store.get(
                            List.of("agents", "assistant", "users", "alice", "root"), "/MEMORY.md"),
                    "MEMORY.md should be stored under alice's user namespace");

            // 以 alice / session-b 调用，看到同一存储命名空间。
            agent.call(userMsg("hi from session-b"), ctx("session-b", "alice")).block();
            assertNotNull(
                    store.get(
                            List.of("agents", "assistant", "users", "alice", "root"), "/MEMORY.md"),
                    "alice's MEMORY.md should still be accessible from session-b");
        }
    }

    /**
     * USER 作用域：不同用户使用隔离命名空间。
     */
    @Test
    void userScope_differentUsers_isolatedNamespaces() throws Exception {
        Files.createDirectories(workspace);
        InMemoryStore store = new InMemoryStore();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(
                                new RemoteFilesystemSpec(store).isolationScope(IsolationScope.USER))
                        .stateStore(stateStore())
                        .build()) {

            agent.call(userMsg("alice writes"), ctx("s1", "alice")).block();
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(ctx("s1", "alice"), "MEMORY.md", "alice's data");

            // Bob 命名空间应为空。
            assertNull(
                    store.get(List.of("agents", "assistant", "users", "bob", "root"), "/MEMORY.md"),
                    "bob's namespace should be isolated from alice's");
        }
    }

    // -------------------------------------------------------------------------
    // 场景 C：AGENT 作用域
    // -------------------------------------------------------------------------

    /**
     * AGENT 作用域：每个智能体的全部用户与会话共享一个命名空间。
     *
     * <p>命名空间为 {@code agents/<agentId>/shared}，任意用户写入都对同智能体其他用户可见。
     */
    @Test
    void agentScope_allCallersShareOneNamespace() throws Exception {
        Files.createDirectories(workspace);
        InMemoryStore store = new InMemoryStore();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("shared-assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(
                                new RemoteFilesystemSpec(store)
                                        .isolationScope(IsolationScope.AGENT))
                        .stateStore(stateStore())
                        .build()) {

            // Alice 写入。
            agent.call(userMsg("alice"), ctx("s1", "alice")).block();
            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            ctx("s1", "alice"), "MEMORY.md", "shared knowledge");

            assertNotNull(
                    store.get(
                            List.of("agents", "shared-assistant", "shared", "root"), "/MEMORY.md"),
                    "MEMORY.md should be in the agent-scoped shared namespace");

            // Bob 调用也从同一共享命名空间读取。
            agent.call(userMsg("bob"), ctx("s2", "bob")).block();
            assertNotNull(
                    store.get(
                            List.of("agents", "shared-assistant", "shared", "root"), "/MEMORY.md"),
                    "shared namespace should still contain the data after bob's call");
        }
    }

    // -------------------------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------------------------

    private static RuntimeContext ctx(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Model stubModel(String text) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(text).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }

    private static AgentStateStore stateStore() {
        return mock(AgentStateStore.class, delegatesTo(new InMemoryAgentStateStore()));
    }
}
