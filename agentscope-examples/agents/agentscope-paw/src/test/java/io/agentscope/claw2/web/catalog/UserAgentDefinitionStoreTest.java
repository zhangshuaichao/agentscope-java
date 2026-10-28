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
package io.agentscope.claw2.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 测试本地化重构后替代旧 JPA 实现、基于 JSON 文件的 {@link UserAgentDefinitionStore}。
 */
class UserAgentDefinitionStoreTest {

    @TempDir Path tmp;

    @Test
    void saveListFindDelete_roundTrips() throws Exception {
        Path file = tmp.resolve("agents.json");
        UserAgentDefinitionStore store = new UserAgentDefinitionStore(file);

        long now = System.currentTimeMillis();
        UserAgentDefinitionStore.StoredEntry entry =
                new UserAgentDefinitionStore.StoredEntry(
                        "writer",
                        "Writer",
                        "Helps with writing",
                        "You are a writing helper.",
                        null,
                        8,
                        List.of("read_file"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        now,
                        now,
                        null);
        store.save(entry);

        assertThat(store.list())
                .extracting(UserAgentDefinitionStore.StoredEntry::id)
                .containsExactly("writer");
        assertThat(store.findById("writer")).isPresent();
        assertThat(Files.exists(file)).isTrue();

        // 从磁盘重新加载，验证持久化结果。
        UserAgentDefinitionStore reloaded = new UserAgentDefinitionStore(file);
        assertThat(reloaded.findById("writer"))
                .get()
                .extracting(UserAgentDefinitionStore.StoredEntry::sysPrompt)
                .isEqualTo("You are a writing helper.");

        assertThat(reloaded.delete("writer")).isTrue();
        assertThat(reloaded.list()).isEmpty();
    }

    @Test
    void toDefinition_marksCustomAgentsAsNonBuiltin() {
        UserAgentDefinitionStore.StoredEntry entry =
                new UserAgentDefinitionStore.StoredEntry(
                        "id1", "Name", null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, 0L, 0L, null);
        AgentDefinition def = entry.toDefinition();
        assertThat(def.id()).isEqualTo("id1");
        assertThat(def.builtin()).isFalse();
    }
}
