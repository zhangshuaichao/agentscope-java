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
package io.agentscope.dataagent.web.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.DataAgentApp;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore.StoredEntry;
import io.agentscope.dataagent.web.share.AgentShareGrant;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

/**
 * 基于内存 H2 启动完整 Spring Boot 上下文，端到端验证 DataAgent 唯一随附的 JPA 持久化路径。
 * 确认 {@link JpaPersistenceConfig} 默认启用并装配两个存储；{@link JpaUserStore} 创建默认 {@code admin}
 * 并支持增删改查；{@link JpaUserAgentDefinitionStore} 完整读回包含共享授权的智能体定义；
 * 应用中的 {@link UserStore} 与 {@link UserAgentDefinitionStore} Bean 实际使用 JPA 实现。
 */
@Disabled("Pending fix for circular dependency between builderBootstrap and userSandboxRegistry")
@SpringBootTest(classes = DataAgentApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(JpaPersistenceIntegrationTest.StubModelConfig.class)
@TestPropertySource(
        properties = {
            "dataagent.jwt.secret=test-jwt-secret-must-be-at-least-32-characters-long",
            "dataagent.workspace=${java.io.tmpdir}/agentscope-dataagent-jpa-it",
            "dataagent.dashscope.api-key=",
            "spring.datasource.url=jdbc:h2:mem:dataagentJpaIT;DB_CLOSE_DELAY=-1;MODE=MYSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.open-in-view=false",
            // 跳过仅开发使用的 bob/alice 初始化，避免干扰增删改查
            // 及默认管理员状态的断言。
            "spring.sql.init.mode=never"
        })
class JpaPersistenceIntegrationTest {

    @Autowired UserStore userStore;
    @Autowired UserAgentDefinitionStore agentStore;
    @Autowired DataAgentBootstrap bootstrap;

    @Test
    void jpaBeansAreWiredByDefault() {
        assertThat(userStore).isInstanceOf(JpaUserStore.class);
        assertThat(agentStore).isInstanceOf(JpaUserAgentDefinitionStore.class);
        assertThat(bootstrap).isNotNull();
    }

    @Test
    void defaultAdminIsSeededOnEmptyDatabase() {
        Optional<UserStore.UserRecord> admin = userStore.findById("admin");
        assertThat(admin).isPresent();
        assertThat(admin.get().roles()).contains("admin");
        assertThat(userStore.verifyPassword(admin.get(), "admin")).isTrue();
    }

    @Test
    void userCrudRoundTripsThroughJpa() {
        userStore.createUser("alice-1", "alice", "secret123", List.of("user"));
        UserStore.UserRecord byUsername = userStore.findByUsername("alice").orElseThrow();
        assertThat(byUsername.userId()).isEqualTo("alice-1");

        userStore.updatePassword("alice-1", "new-secret");
        UserStore.UserRecord reloaded = userStore.findById("alice-1").orElseThrow();
        assertThat(userStore.verifyPassword(reloaded, "new-secret")).isTrue();

        userStore.updateRoles("alice-1", List.of("user", "admin"));
        assertThat(userStore.findById("alice-1").orElseThrow().roles())
                .containsExactly("user", "admin");

        assertThat(userStore.deleteUser("alice-1")).isTrue();
        assertThat(userStore.findById("alice-1")).isEmpty();
    }

    @Test
    void agentDefinitionRoundTripsAndCarriesWorkspacePath() {
        userStore.createUser("bob-1", "bob", "secret123", List.of("user"));

        StoredEntry entry =
                new StoredEntry(
                        "demo",
                        "Demo Agent",
                        "demo description",
                        "system prompt body",
                        "qwen-max",
                        12,
                        List.of("read_file", "list_files"),
                        List.of("execute"),
                        "Demo",
                        ":rocket:",
                        null,
                        Boolean.FALSE,
                        List.of("foo-skill"),
                        null,
                        100L,
                        200L,
                        List.of(
                                new AgentShareGrant(
                                        AgentShareGrant.GRANTEE_USER,
                                        "bob-1",
                                        AgentShareGrant.TIER_RUN,
                                        100L,
                                        "bob-1")),
                        "INVOKER",
                        null,
                        "/tmp/agentscope-test/demo",
                        null,
                        null,
                        null);

        StoredEntry saved = agentStore.save("bob-1", entry);
        assertThat(saved.id()).isEqualTo("demo");

        StoredEntry reloaded = agentStore.findById("bob-1", "demo").orElseThrow();
        assertThat(reloaded.name()).isEqualTo("Demo Agent");
        assertThat(reloaded.toolsAllow()).containsExactly("read_file", "list_files");
        assertThat(reloaded.toolsDeny()).containsExactly("execute");
        assertThat(reloaded.skillsAllow()).containsExactly("foo-skill");
        assertThat(reloaded.shares()).hasSize(1);
        assertThat(reloaded.shares().get(0).granteeId()).isEqualTo("bob-1");
        assertThat(reloaded.workspacePath()).isEqualTo("/tmp/agentscope-test/demo");

        List<StoredEntry> all = agentStore.list("bob-1");
        assertThat(all).extracting(StoredEntry::id).containsExactly("demo");

        assertThat(agentStore.delete("bob-1", "demo")).isTrue();
        assertThat(agentStore.findById("bob-1", "demo")).isEmpty();
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
