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
package io.agentscope.dataagent.web.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.DataAgentApp;
import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import io.agentscope.dataagent.web.persistence.jpa.ContributionRepository;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

/**
 * 锁定 {@link MarketContributionService} 的 {@code PENDING → APPROVED/REJECTED} 状态契约，
 * 即运营人员使用的管理员审批流程。基于 H2 启动完整 Spring 上下文，验证 JPA 持久化与文件落地不会失配。
 * <p>每次测试在 {@code java.io.tmpdir}/agentscope-dataagent-contrib-it 下创建全新工作区，
 * 使 {@code bootstrap.cwd().resolve("shared/agents/<agentId>")} 对应测试独占的智能体目录，
 * 可读回验证快照按原样落地。
 */
@Disabled("Pending fix for circular dependency between builderBootstrap and userSandboxRegistry")
@SpringBootTest(classes = DataAgentApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MarketContributionServiceTest.StubModelConfig.class)
@TestPropertySource(
        properties = {
            "dataagent.jwt.secret=test-jwt-secret-must-be-at-least-32-characters-long",
            "dataagent.dashscope.api-key=",
            "spring.datasource.url=jdbc:h2:mem:dataagentContribIT;DB_CLOSE_DELAY=-1;MODE=MYSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.open-in-view=false",
            // 跳过 bob/alice 演示初始化，只断言贡献记录。
            "spring.sql.init.mode=never"
        })
class MarketContributionServiceTest {

    @Autowired MarketContributionService service;
    @Autowired ContributionRepository repository;
    @Autowired DataAgentBootstrap bootstrap;

    /**
     * 将 {@code dataagent.workspace} 指向本次运行独有临时目录，隔离共享根目录与其他测试或开发者机器状态。
     */
    @DynamicPropertySource
    static void workspaceDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("agentscope-dataagent-contrib-it");
        registry.add("dataagent.workspace", dir::toString);
    }

    private static List<FileEntry> body(String content) {
        return List.of(new FileEntry("", content));
    }

    @Test
    void submitInsertsPendingRow() {
        ContributionEntity submitted =
                service.submit(
                        "alice",
                        "data-agent",
                        null,
                        ContributionEntity.TARGET_SKILL,
                        "cohort-builder",
                        "useful for quarterly cohort analysis",
                        body("# Cohort Builder\n\nbuild cohorts.\n"));

        assertThat(submitted.getId()).isNotNull();
        assertThat(submitted.getStatus()).isEqualTo(ContributionEntity.STATUS_PENDING);
        assertThat(submitted.getSourceUserId()).isEqualTo("alice");
        assertThat(submitted.getTargetAgentId()).isEqualTo("data-agent");
        assertThat(submitted.getReviewerUserId()).isNull();

        // 两条查询路径均可看见。
        assertThat(service.listByStatus(ContributionEntity.STATUS_PENDING))
                .extracting(ContributionEntity::getId)
                .contains(submitted.getId());
        assertThat(service.listMine("alice"))
                .extracting(ContributionEntity::getId)
                .containsExactly(submitted.getId());
    }

    /**
     * 批准裸技能名时，在智能体目录中的技能包内落地 {@code SKILL.md}，符合沙箱投影约定。
     */
    @Test
    void approveSkillMaterializesSkillMdUnderSharedRoot() throws IOException {
        String text = "# Demo Skill\n\nhello.\n";
        ContributionEntity submitted =
                service.submit(
                        "alice",
                        "data-agent",
                        null,
                        ContributionEntity.TARGET_SKILL,
                        "demo-skill",
                        "demo rationale",
                        body(text));

        ContributionEntity approved =
                service.approve(submitted.getId(), "admin", "looks good", null);

        assertThat(approved.getStatus()).isEqualTo(ContributionEntity.STATUS_APPROVED);
        assertThat(approved.getReviewerUserId()).isEqualTo("admin");
        assertThat(approved.getReviewerNote()).isEqualTo("looks good");
        assertThat(approved.getUpdatedAt()).isGreaterThanOrEqualTo(submitted.getCreatedAt());

        Path expected =
                bootstrap
                        .cwd()
                        .resolve("shared")
                        .resolve("agents")
                        .resolve("data-agent")
                        .resolve("skills")
                        .resolve("demo-skill")
                        .resolve("SKILL.md");
        assertThat(expected).exists();
        assertThat(Files.readString(expected, StandardCharsets.UTF_8)).isEqualTo(text);
    }

    /**
     * 子智能体和记忆片段落到各自类型目录下的原目标路径，不套用技能的 SKILL.md 特例。
     */
    @Test
    void approveSubagentMaterializesAtLiteralPath() throws IOException {
        String text = "# Report Writer\n\nplain markdown body.\n";
        ContributionEntity submitted =
                service.submit(
                        "alice",
                        "data-agent",
                        null,
                        ContributionEntity.TARGET_SUBAGENT,
                        "report-writer.md",
                        null,
                        body(text));

        service.approve(submitted.getId(), "admin", null, null);

        Path expected =
                bootstrap
                        .cwd()
                        .resolve("shared")
                        .resolve("agents")
                        .resolve("data-agent")
                        .resolve("subagents")
                        .resolve("report-writer.md");
        assertThat(expected).exists();
        assertThat(Files.readString(expected, StandardCharsets.UTF_8)).isEqualTo(text);
    }

    @Test
    void rejectTransitionsWithoutFilesystemWrite() {
        ContributionEntity submitted =
                service.submit(
                        "alice",
                        "data-agent",
                        null,
                        ContributionEntity.TARGET_MEMORY,
                        "rejected-snippet.md",
                        null,
                        body("memory body"));

        ContributionEntity rejected =
                service.reject(submitted.getId(), "admin", "duplicate of existing entry");

        assertThat(rejected.getStatus()).isEqualTo(ContributionEntity.STATUS_REJECTED);
        assertThat(rejected.getReviewerNote()).isEqualTo("duplicate of existing entry");

        Path memoryDir =
                bootstrap
                        .cwd()
                        .resolve("shared")
                        .resolve("agents")
                        .resolve("data-agent")
                        .resolve("memory");
        // 拒绝贡献不得改动智能体记忆树，目标文件不应出现，
        // 即使对应目录曾由此前批准操作创建。
        assertThat(memoryDir.resolve("rejected-snippet.md")).doesNotExist();
    }

    /**
     * 贡献批准后不能再次批准或改为拒绝：两者均应抛出异常，持久记录保持 {@code APPROVED}。
     */
    @Test
    void terminalStatusesAreNotRetransitionable() {
        ContributionEntity submitted =
                service.submit(
                        "alice",
                        "data-agent",
                        null,
                        ContributionEntity.TARGET_SKILL,
                        "idempotent-skill",
                        null,
                        body("# Idempotent\n"));

        service.approve(submitted.getId(), "admin", "first approval", null);

        assertThatThrownBy(
                        () -> service.approve(submitted.getId(), "admin", "second approval", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not pending");
        assertThatThrownBy(() -> service.reject(submitted.getId(), "admin", "too late"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not pending");

        ContributionEntity reloaded = repository.findById(submitted.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ContributionEntity.STATUS_APPROVED);
        assertThat(reloaded.getReviewerNote()).isEqualTo("first approval");
    }

    @Test
    void approveOnMissingIdThrows() {
        assertThatThrownBy(() -> service.approve(999_999L, "admin", "ghost", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void submitRejectsInvalidInputBeforePersisting() {
        long before = repository.count();

        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "",
                                        "agent",
                                        null,
                                        ContributionEntity.TARGET_SKILL,
                                        "p",
                                        null,
                                        body("body")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "alice",
                                        "agent",
                                        null,
                                        "bogus-type",
                                        "p",
                                        null,
                                        body("body")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "alice",
                                        "agent",
                                        null,
                                        ContributionEntity.TARGET_SKILL,
                                        "",
                                        null,
                                        body("body")))
                .isInstanceOf(IllegalArgumentException.class);
        // 提交时已拒绝路径遍历，文件写入检查构成纵深防御。
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "alice",
                                        "agent",
                                        null,
                                        ContributionEntity.TARGET_SKILL,
                                        "../escape",
                                        null,
                                        body("body")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "alice",
                                        "agent",
                                        null,
                                        ContributionEntity.TARGET_SKILL,
                                        "p",
                                        null,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.count())
                .as("no rows must be inserted when validation rejects the submission")
                .isEqualTo(before);
    }

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        @Primary
        Model stubModel() {
            Model model = Mockito.mock(Model.class);
            Mockito.when(model.getModelName()).thenReturn("stub-model");
            ChatResponse chunk =
                    new ChatResponse(
                            "stub-id",
                            List.of(TextBlock.builder().text("ok").build()),
                            null,
                            Map.of(),
                            "stop");
            Mockito.when(model.stream(Mockito.anyList(), Mockito.any(), Mockito.any()))
                    .thenReturn(Flux.just(chunk));
            return model;
        }

        @Bean
        BaseStore inMemoryStore() {
            return new InMemoryStore();
        }
    }
}
