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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.example.support.InMemorySandboxClient;
import io.agentscope.harness.agent.example.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 示例：采用不同 {@link IsolationScope} 级别的沙箱文件系统模式。
 *
 * <h2>背景</h2>
 * <p>沙箱模式中，每次智能体调用获取沙箱会话（如 Docker 容器，本测试则是本地临时目录）。{@code IsolationScope} 控制哪些调用<em>恢复</em>同一沙箱并共享积累的状态，哪些调用<em>创建</em>全新沙箱。
 * <p>演示三种作用域：
 * <ul>
 *   <li>{@link IsolationScope#SESSION}：相同会话 ID 恢复同一沙箱，不同会话获得全新沙箱。</li>
 *   <li>{@link IsolationScope#USER}：同用户任意会话恢复同一沙箱，不同用户获得全新沙箱。</li>
 *   <li>{@link IsolationScope#AGENT}：不论用户或会话，全部调用共享一个沙箱。</li>
 * </ul>
 * <p>本测试使用 {@link InMemorySandboxClient}，无需 Docker；Mockito 桩 {@link Model} 立即返回终止文本响应，使 ReAct 循环一步退出。断言统计 {@link InMemorySandboxClient#getCreateCount()} 和 {@link InMemorySandboxClient#getResumeCount()} 验证隔离行为。
 */
@HarnessQuiescence
class SandboxFilesystemIsolationScopeExampleTest {

    @TempDir Path workspace;

    // 每个 @Test 使用全新 JsonFileAgentStateStore 根目录，防止
    // 共享智能体名称（"assistant"）的沙箱状态通过 surefire 共享的
    // target/test-state-home 目录泄漏到其他用例。
    @TempDir Path stateHome;

    private String previousStateHome;

    @BeforeEach
    void overrideStateHome() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void restoreStateHome() {
        if (previousStateHome != null) {
            System.setProperty("agentscope.state.home", previousStateHome);
        } else {
            System.clearProperty("agentscope.state.home");
        }
    }

    // -------------------------------------------------------------------------
    // 场景 A：SESSION 作用域
    // -------------------------------------------------------------------------

    /**
     * SESSION 作用域：相同会话 ID 在第二次调用恢复沙箱。
     *
     * <p>调用 1 无持久化状态，调用 {@code create} 创建新沙箱；调用 2 已有 "session-1" 状态，调用 {@code resume} 恢复相同沙箱。
     */
    @Test
    void sessionScope_sameSession_resumesSandbox() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.SESSION);
        InMemorySandboxClient client = spec.getClient();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .sessionLogStore(
                                new WorkspaceSessionLogStore(
                                        new LocalFilesystem(workspace.resolve("journal"))))
                        .build();

        // 首次调用，无持久化状态，创建沙箱。
        agent.call(userMsg("hello"), ctx("session-1", null)).block();
        assertEquals(1, client.getCreateCount(), "first call should create a fresh sandbox");
        assertEquals(0, client.getResumeCount());

        // 第二次调用，相同会话，恢复沙箱。
        agent.call(userMsg("hello again"), ctx("session-1", null)).block();
        assertEquals(1, client.getCreateCount(), "second call should NOT create a new sandbox");
        assertEquals(1, client.getResumeCount(), "second call should resume the existing sandbox");
    }

    /**
     * SESSION 作用域：会话 ID 不同，每次调用创建新沙箱。
     */
    @Test
    void sessionScope_differentSession_createsFreshSandbox() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.SESSION);
        InMemorySandboxClient client = spec.getClient();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .sessionLogStore(
                                new WorkspaceSessionLogStore(
                                        new LocalFilesystem(workspace.resolve("journal"))))
                        .build();

        agent.call(userMsg("call from session-1"), ctx("session-2-1", "alice")).block();
        agent.call(userMsg("call from session-2"), ctx("session-2-2", "alice")).block();

        assertEquals(
                2, client.getCreateCount(), "each distinct session should create its own sandbox");
        assertEquals(0, client.getResumeCount());
    }

    // -------------------------------------------------------------------------
    // 场景 B：USER 作用域
    // -------------------------------------------------------------------------

    /**
     * USER 作用域：同用户不同会话 ID，两次调用共享同一沙箱。
     *
     * <p>调用 1 用户 "alice" 无持久化状态，执行 {@code create}；调用 2 "alice" 已有状态，即使 sessionId 不同也执行 {@code resume}。
     */
    @Test
    void userScope_sameUser_differentSessions_resumesSandbox() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.USER);
        InMemorySandboxClient client = spec.getClient();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .sessionLogStore(
                                new WorkspaceSessionLogStore(
                                        new LocalFilesystem(workspace.resolve("journal"))))
                        .build();

        agent.call(userMsg("session A"), ctx("session-a", "alice")).block();
        assertEquals(1, client.getCreateCount());
        assertEquals(0, client.getResumeCount());

        // 不同会话、相同用户，应恢复 Alice 的沙箱。
        agent.call(userMsg("session B"), ctx("session-b", "alice")).block();
        assertEquals(1, client.getCreateCount(), "same user → should NOT create a new sandbox");
        assertEquals(1, client.getResumeCount(), "same user → should resume existing sandbox");
    }

    /**
     * USER 作用域：不同用户各获独立沙箱。
     */
    @Test
    void userScope_differentUsers_createsFreshSandbox() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.USER);
        InMemorySandboxClient client = spec.getClient();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .sessionLogStore(
                                new WorkspaceSessionLogStore(
                                        new LocalFilesystem(workspace.resolve("journal"))))
                        .build();

        agent.call(userMsg("hi from alice2"), ctx("s1", "alice2")).block();
        agent.call(userMsg("hi from bob2"), ctx("s2", "bob2")).block();

        assertEquals(2, client.getCreateCount(), "each user should get their own fresh sandbox");
        assertEquals(0, client.getResumeCount());
    }

    // -------------------------------------------------------------------------
    // 场景 C：AGENT 作用域
    // -------------------------------------------------------------------------

    /**
     * AGENT 作用域：不论用户或会话，全部调用共享同一沙箱。
     *
     * <p>调用 1 无状态，执行 {@code create}；调用 2 已有 agentId 键对应状态，即使用户与会话不同也执行 {@code resume}。
     */
    @Test
    void agentScope_allCallsShareOneSandbox() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.AGENT);
        InMemorySandboxClient client = spec.getClient();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("shared-assistant")
                        .model(stubModel("done"))
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .sessionLogStore(
                                new WorkspaceSessionLogStore(
                                        new LocalFilesystem(workspace.resolve("journal"))))
                        .build();

        // 用户、会话均不同，仍共享一个 AGENT 作用域沙箱。
        agent.call(userMsg("alice says hi"), ctx("s1", "alice")).block();
        assertEquals(1, client.getCreateCount());

        agent.call(userMsg("bob says hi"), ctx("s2", "bob")).block();
        assertEquals(1, client.getCreateCount(), "second call should NOT create a new sandbox");
        assertEquals(
                1, client.getResumeCount(), "second call should resume the shared agent sandbox");

        agent.call(userMsg("charlie says hi"), ctx("s3", "charlie")).block();
        assertEquals(1, client.getCreateCount());
        assertEquals(
                2, client.getResumeCount(), "third call should also resume the shared sandbox");
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
}
