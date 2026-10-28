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
package io.agentscope.harness.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 覆盖基于 {@code distributedStore} 的 {@link SubagentRegistry} 所引入的分布式已暴露子智能体恢复链。
 *
 * <p>两个独立 {@link HarnessGateway} 实例代表两个节点，仅共享 {@code DistributedStore} 提供的存储原语：一个保存注册信息的 {@link BaseStore} 和一个保存子智能体对话状态的 {@link AgentStateStore}。测试完整路径：
 *
 * <pre>
 *   node A: expose → persist record + state   ⟶   shared store
 *   node B: cache miss → registry.find → materialize → load state by sessionId → continue
 * </pre>
 *
 * <p>上图中文对照：节点 A 暴露子智能体，将注册记录与状态持久化到共享存储；节点 B 本地缓存未命中时，通过 registry.find 查找并重建实例，按 sessionId 加载状态后继续执行。
 *
 * <p>{@link MockModel} 回显上下文中 {@code "ping"} 轮数，以检查状态连续性：节点 B 观察到 {@code turns=2}，要求同时从共享注册表解析句柄并从共享状态存储加载节点 A 的上一轮。
 */
@HarnessQuiescence
class SubagentRegistryRecoveryIntegrationTest {

    @TempDir Path workspace;

    private static final String SUB_SESSION = "exposed-echo-session";

    /** 回复中报告上下文用户轮次数的子智能体。 */
    private HarnessAgent buildEchoSubagent(AgentStateStore sharedState) {
        Function<List<Msg>, List<ChatResponse>> generator =
                msgs -> {
                    long pings =
                            msgs.stream()
                                    .map(Msg::getTextContent)
                                    .filter(t -> t != null && t.contains("ping"))
                                    .count();
                    return List.of(
                            new ChatResponse(
                                    "r-" + pings,
                                    List.of(TextBlock.builder().text("turns=" + pings).build()),
                                    null,
                                    Map.of(),
                                    "stop"));
                };
        return HarnessAgent.builder()
                .name("echo")
                .model(new MockModel(generator))
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .stateStore(sharedState)
                .build();
    }

    private static Msg ping() {
        return Msg.builder().role(MsgRole.USER).textContent("ping").build();
    }

    private static String text(Msg m) {
        return m != null ? m.getTextContent() : null;
    }

    @Test
    void exposedSubagentResolvesAndContinuesOnAnotherNode() {
        // 共享的“分布式存储”原语。
        BaseStore sharedBase = new InMemoryStore();
        AgentStateStore sharedState = new InMemoryAgentStateStore();

        // 跟踪工厂创建的每个智能体，测试结束后全部关闭，
        // 防止后台 WorkspaceTaskRepository 线程在 JUnit 开始清理
        // @TempDir 后仍写入目录。
        List<HarnessAgent> createdAgents = new ArrayList<>();
        Supplier<HarnessAgent> subFactory =
                () -> {
                    HarnessAgent a = buildEchoSubagent(sharedState);
                    createdAgents.add(a);
                    return a;
                };

        try {
            // ---- 节点 A：暴露活动子智能体并运行首轮。----
            HarnessGateway nodeA = HarnessGateway.create();
            nodeA.setSubagentRegistry(new StoreBackedSubagentRegistry(sharedBase));
            nodeA.setSubagentMaterializer((agentId, rc) -> Optional.of(subFactory.get()));

            HarnessAgent liveSub = subFactory.get();
            String subagentId = nodeA.exposeSubagent("echo", SUB_SESSION, liveSub, null);
            assertNotNull(subagentId);

            Msg firstReply = nodeA.runSubagent(subagentId, List.of(ping())).block();
            assertEquals("turns=1", text(firstReply), "node A sees exactly its own turn");

            // ---- 节点 B：新网关，活动缓存为空，只共享存储。----
            HarnessGateway nodeB = HarnessGateway.create();
            nodeB.setSubagentRegistry(new StoreBackedSubagentRegistry(sharedBase));
            nodeB.setSubagentMaterializer((agentId, rc) -> Optional.of(subFactory.get()));

            Msg secondReply = nodeB.runSubagent(subagentId, List.of(ping())).block();
            // turns=2 用于证明：(1) 从未暴露过该智能体的节点从共享注册表解析了 subagentId，
            // (2) 智能体已重新实例化，(3) 节点 A 的上一轮
            // 通过 sessionId 从共享状态存储加载。
            assertEquals(
                    "turns=2", text(secondReply), "node B recovered and continued the session");
        } finally {
            for (HarnessAgent agent : createdAgents) {
                try {
                    agent.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Test
    void unknownSubagentWithoutSharedRegistryIsUnresolvable() {
        BaseStore sharedBase = new InMemoryStore();
        AgentStateStore sharedState = new InMemoryAgentStateStore();
        List<HarnessAgent> createdAgents = new ArrayList<>();
        Supplier<HarnessAgent> subFactory =
                () -> {
                    HarnessAgent a = buildEchoSubagent(sharedState);
                    createdAgents.add(a);
                    return a;
                };

        try {
            HarnessGateway nodeA = HarnessGateway.create();
            nodeA.setSubagentRegistry(new StoreBackedSubagentRegistry(sharedBase));
            String subagentId = nodeA.exposeSubagent("echo", SUB_SESSION, subFactory.get(), null);

            // 注册表连接另一份空存储的节点，即使有 materializer 也看不到句柄，
            // 由此检查跨节点身份是由注册表
            // 承载的。
            HarnessGateway isolatedNode = HarnessGateway.create();
            isolatedNode.setSubagentRegistry(new StoreBackedSubagentRegistry(new InMemoryStore()));
            isolatedNode.setSubagentMaterializer((agentId, rc) -> Optional.of(subFactory.get()));

            assertThrows(
                    IllegalArgumentException.class,
                    () -> isolatedNode.runSubagent(subagentId, List.of(ping())).block());
        } finally {
            for (HarnessAgent agent : createdAgents) {
                try {
                    agent.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Test
    void storeBackedRegistryPersistsAcrossInstancesAndHonoursTtl() {
        BaseStore sharedBase = new InMemoryStore();
        SubagentRegistry writer = new StoreBackedSubagentRegistry(sharedBase);
        SubagentRegistry reader = new StoreBackedSubagentRegistry(sharedBase);

        SubagentRecord record =
                new SubagentRecord(
                        "sub-abc", "echo", SUB_SESSION, "user-1", "parent-1", Instant.now(), null);
        writer.register(record);

        // 基于同一存储的独立注册表实例可解析该记录，模拟跨节点。
        Optional<SubagentRecord> found = reader.find("sub-abc");
        assertTrue(found.isPresent());
        assertEquals("echo", found.get().agentId());
        assertEquals(SUB_SESSION, found.get().sessionId());
        assertEquals("user-1", found.get().userId());

        // 撤销对其他实例可见。
        writer.revoke("sub-abc");
        assertTrue(reader.find("sub-abc").isEmpty());

        // 过期记录按不存在处理，并在查找时延迟清除。
        writer.register(
                new SubagentRecord(
                        "sub-expired",
                        "echo",
                        SUB_SESSION,
                        null,
                        null,
                        Instant.now().minusSeconds(120),
                        Instant.now().minusSeconds(60)));
        assertTrue(reader.find("sub-expired").isEmpty(), "expired record must not resolve");
    }
}
