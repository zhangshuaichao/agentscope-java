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
package io.agentscope.harness.agent.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 多个调用方共享工作区根目录时，技能暂存的端到端覆盖。
 *
 * <p>复现 2.0.1 报告的场景：可见技能集合不同的并发调用，使一方孤儿 GC 删除另一方刚暂存的目录；在首轮模型调用前，{@code HarnessSkillMiddleware.onSystemPrompt} 抛出 {@code UncheckedIOException} 中止调用。
 *
 * <p>两个测试都经过真实生产路径（中间件 → {@link MarketplaceStager} → 磁盘）；模型使用桩，因为故障严格发生于任何模型调用之前。
 */
class SharedWorkspaceSkillStagingE2ETest {

    // 有意使用 [^<]*：提示的 <usage> 图例也提到 <files-root> 标签，
    // DOTALL 通配符会把该提及错误拼接到第一个实际结束标签。
    private static final Pattern FILES_ROOT = Pattern.compile("<files-root>([^<]*)</files-root>");

    @Test
    @DisplayName("E2E: two agents sharing one workspace both complete their calls")
    void concurrentAgentsSharingWorkspaceBothComplete(@TempDir Path shared) throws Exception {
        // 智能体 A 看见两个技能，B 只看见一个；A 仍在暂存 "beta" 时，
        // B 的保留列表就把它视为孤儿。之后关闭两个智能体，
        // 因为 HarnessAgent 持有维护线程，泄漏到共享 surefire JVM
        // 会挤占后续时间敏感测试的资源。
        try (HarnessAgent agentA = agent("agent-a", shared, repo("src-a", "alpha", "beta"));
                HarnessAgent agentB = agent("agent-b", shared, repo("src-b", "alpha"))) {

            List<Throwable> errors = runConcurrently(50, agentA, agentB);

            assertEquals(
                    List.of(),
                    errors,
                    () -> "no call should fail, but got: " + stackTraces(errors));
            assertTrue(
                    Files.isDirectory(shared.resolve(MarketplaceStager.CACHE_DIR)),
                    "the shared cache should exist after the calls");
        }
    }

    @Test
    @DisplayName("E2E: per-user visibility — the prompt never points at a deleted directory")
    void perUserVisibilityKeepsEachUsersFilesOnDisk(@TempDir Path shared) throws Exception {
        HarnessSkillMiddleware middleware = perUserMiddleware(shared);

        List<Throwable> errors = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        AtomicInteger advertisedCount = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        for (String user : List.of("alice", "bob")) {
            Thread t =
                    new Thread(
                            () -> {
                                RuntimeContext ctx =
                                        RuntimeContext.builder()
                                                .sessionId("s-" + user)
                                                .userId(user)
                                                .build();
                                try {
                                    start.await();
                                    for (int i = 0; i < 60; i++) {
                                        String prompt =
                                                middleware.onSystemPrompt(null, ctx, "").block();
                                        // 在此处检查，而不是运行结束后：关键在于
                                        // 交给模型的路径在交付瞬间
                                        // 必须有效；结束后检查只能观察
                                        // 最后完成暂存的调用方结果。
                                        assertNotNull(prompt);
                                        Matcher m = FILES_ROOT.matcher(skillsBlockOf(ctx));
                                        int seen = 0;
                                        while (m.find()) {
                                            Path advertised =
                                                    Paths.get(
                                                            m.group(1).trim().replace("\\ ", " "));
                                            if (!Files.isDirectory(advertised)) {
                                                synchronized (missing) {
                                                    missing.add(advertised.toString());
                                                }
                                            }
                                            seen++;
                                        }
                                        advertisedCount.addAndGet(seen);
                                    }
                                } catch (Throwable e) {
                                    synchronized (errors) {
                                        errors.add(e);
                                    }
                                } finally {
                                    done.countDown();
                                }
                            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "both users should finish");

        assertEquals(List.of(), errors, () -> "onSystemPrompt must not fail, but got: " + errors);
        assertEquals(
                List.of(),
                missing,
                () -> "prompt advertised files-roots that were not on disk: " + missing);
        assertTrue(advertisedCount.get() > 0, "fixture should have advertised a files-root");
    }

    @Test
    @DisplayName("E2E: one user's call must not delete a skill staged for another user")
    void oneUsersCallDoesNotDeleteAnotherUsersSkill(@TempDir Path shared) {
        HarnessSkillMiddleware middleware = perUserMiddleware(shared);
        RuntimeContext bob = RuntimeContext.builder().sessionId("s-bob").userId("bob").build();
        RuntimeContext alice =
                RuntimeContext.builder().sessionId("s-alice").userId("alice").build();

        // Bob 可见 "beta"，因此其调用暂存该技能，技能资料
        // 引导模型访问它。
        String bobPrompt = middleware.onSystemPrompt(null, bob, "").block();
        assertNotNull(bobPrompt);
        assertTrue(skillsBlockOf(bob).contains("<name>beta</name>"), "bob should see beta");
        // 按 userId 限定作用域后，alice 调用完全无法触达 bob 子树，
        // 使“不得删除”从时序属性变为结构性保证。
        Path betaDir =
                shared.resolve(MarketplaceStager.CACHE_DIR)
                        .resolve("bob")
                        .resolve("market")
                        .resolve("beta");
        assertTrue(Files.isDirectory(betaDir), "beta should be staged for bob");

        // Alice 不可见 "beta"，她的孤儿 GC 不得回收 bob 仍在使用的内容。
        middleware.onSystemPrompt(null, alice, "").block();

        assertTrue(
                Files.isDirectory(betaDir),
                "alice's call deleted the directory bob's prompt still points at: " + betaDir);
    }

    // =========================================================================
    // 测试夹具
    // =========================================================================

    /**
     * "beta" 仅 bob 可见，"alpha" 所有人可见，对应报告中的逐用户配置。
     */
    private static HarnessSkillMiddleware perUserMiddleware(Path workspace) {
        SkillVisibilityFilter perUser =
                (all, ctx) ->
                        all.stream()
                                .filter(
                                        s ->
                                                !"beta".equals(s.getName())
                                                        || "bob".equals(ctx.getUserId()))
                                .toList();
        return new HarnessSkillMiddleware(
                List.of(repo("market", "alpha", "beta")),
                new Toolkit(),
                null,
                perUser,
                new MarketplaceStager(workspace),
                ShellPathPolicy.localWithShell(workspace));
    }

    /**
     * 返回向模型展示的运行时技能目录。
     */
    private static String skillsBlockOf(RuntimeContext ctx) {
        WorkspaceContextMaterials materials = ctx.get(WorkspaceContextMaterials.class);
        assertNotNull(materials);
        return materials.items().stream()
                .filter(item -> "harness:skills".equals(item.sourceId()))
                .map(ContextItem::content)
                .findFirst()
                .orElseThrow();
    }

    private static List<Throwable> runConcurrently(int rounds, HarnessAgent... agents)
            throws InterruptedException {
        List<Throwable> errors = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(agents.length);
        for (int i = 0; i < agents.length; i++) {
            HarnessAgent agent = agents[i];
            String user = "user-" + i;
            Thread t =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    for (int r = 0; r < rounds; r++) {
                                        RuntimeContext ctx =
                                                RuntimeContext.builder()
                                                        .sessionId(user + "-" + r)
                                                        .userId(user)
                                                        .build();
                                        agent.call(new UserMessage("hi"), ctx).block();
                                    }
                                } catch (Throwable e) {
                                    synchronized (errors) {
                                        errors.add(e);
                                    }
                                } finally {
                                    done.countDown();
                                }
                            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "agent calls should finish");
        return errors;
    }

    private static String stackTraces(List<Throwable> errors) {
        StringWriter out = new StringWriter();
        errors.forEach(e -> e.printStackTrace(new PrintWriter(out, true)));
        return out.toString();
    }

    private static HarnessAgent agent(String name, Path workspace, AgentSkillRepository repo) {
        return HarnessAgent.builder()
                .name(name)
                .model(new StubModel())
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                // 将会话日志放在共享工作区之外：此测试关注技能暂存，
                // Windows 上默认日志是单个 SQLite 文件，每次调用约执行 18 次
                // 完全同步写入，可能慢到触及 120 秒预算（#3514）。
                .sessionLogStore(new InMemorySessionLogStore())
                .skillRepository(repo)
                .build();
    }

    /**
     * 技能带子目录，正是此前导致遍历中止的结构。
     */
    private static AgentSkillRepository repo(String source, String... names) {
        List<AgentSkill> skills = new ArrayList<>();
        for (String name : names) {
            Map<String, String> resources = new LinkedHashMap<>();
            resources.put("SKILL.md", "# " + name + "\n");
            for (int i = 0; i < 6; i++) {
                resources.put("scripts/run" + i + ".sh", "#!/bin/sh\necho " + name + "\n");
            }
            skills.add(new AgentSkill(name, "desc", "# " + name + "\n", resources, source));
        }
        return new StubRepo(skills, source);
    }

    private static final class StubModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "stub-model";
        }
    }

    private static final class StubRepo implements AgentSkillRepository {

        private final List<AgentSkill> skills;
        private final String source;

        StubRepo(List<AgentSkill> skills, String source) {
            this.skills = skills;
            this.source = source;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
        }

        @Override
        public List<String> getAllSkillNames() {
            return skills.stream().map(AgentSkill::getName).toList();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return skills;
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return skills.stream().anyMatch(s -> s.getName().equals(skillName));
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo(source, "", false);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }
}
