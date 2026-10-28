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
package io.agentscope.spring.boot.admin.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class SpringSubagentInventoryTest {

    @Test
    void emptyWhenNoBeans() {
        SpringSubagentInventory inv =
                new SpringSubagentInventory(emptyMapProvider(), emptyMapProvider());
        assertThat(inv.list()).isEmpty();
    }

    @Test
    void emitsEntriesFromSpringEntryBeans() {
        SubagentFactory factory = mock(SubagentFactory.class);
        SubagentEntry entry = new SubagentEntry("code-reviewer", "Reviews code.", factory);
        SpringSubagentInventory inv =
                new SpringSubagentInventory(provider(Map.of("entryA", entry)), emptyMapProvider());

        var list = inv.list();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("code-reviewer");
        assertThat(list.get(0).description()).isEqualTo("Reviews code.");
        assertThat(list.get(0).source()).isEqualTo("spring-bean");
    }

    @Test
    void deduplicatesByName() {
        SubagentFactory factory = mock(SubagentFactory.class);
        // 两个同名 SubagentEntry bean 必须合并为一个描述项。
        // starter 使用 LinkedHashMap 按插入顺序处理，但 Map.of() 迭代顺序
        // 未定义，因此仅检查发生去重，且保留描述来自
        // 其中一个输入。
        SubagentEntry entry1 = new SubagentEntry("dup", "first", factory);
        SubagentEntry entry2 = new SubagentEntry("dup", "second", factory);
        SpringSubagentInventory inv =
                new SpringSubagentInventory(
                        provider(Map.of("a", entry1, "b", entry2)), emptyMapProvider());

        var list = inv.list();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).description()).isIn("first", "second");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<Map<String, T>> provider(Map<String, T> beans) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("backing", beans);
        return (ObjectProvider<Map<String, T>>) (ObjectProvider<?>) bf.getBeanProvider(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<Map<String, T>> emptyMapProvider() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        return (ObjectProvider<Map<String, T>>) (ObjectProvider<?>) bf.getBeanProvider(Map.class);
    }
}
