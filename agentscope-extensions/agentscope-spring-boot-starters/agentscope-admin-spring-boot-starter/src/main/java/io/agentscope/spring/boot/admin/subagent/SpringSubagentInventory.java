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

import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 默认 {@link SubagentInventory}，扫描 Spring 上下文中用户声明的 {@link SubagentEntry} 或 {@link SubagentDeclaration} bean。
 *
 * <p>两种形式引用同一名称时，{@code SubagentDeclaration} 优先，因为声明携带更丰富元数据。
 */
public final class SpringSubagentInventory implements SubagentInventory {

    private final ObjectProvider<Map<String, SubagentEntry>> entryBeans;
    private final ObjectProvider<Map<String, SubagentDeclaration>> declarationBeans;

    public SpringSubagentInventory(
            ObjectProvider<Map<String, SubagentEntry>> entryBeans,
            ObjectProvider<Map<String, SubagentDeclaration>> declarationBeans) {
        this.entryBeans = entryBeans;
        this.declarationBeans = declarationBeans;
    }

    @Override
    public List<SubagentDescriptor> list() {
        Map<String, SubagentDescriptor> byName = new LinkedHashMap<>();
        Map<String, SubagentEntry> entries = entryBeans.getIfAvailable();
        if (entries != null) {
            for (SubagentEntry e : entries.values()) {
                if (e == null || e.name() == null) continue;
                byName.put(e.name(), SubagentDescriptor.of(e));
            }
        }
        Map<String, SubagentDeclaration> decls = declarationBeans.getIfAvailable();
        if (decls != null) {
            for (SubagentDeclaration d : decls.values()) {
                if (d == null || d.getName() == null) continue;
                byName.put(d.getName(), SubagentDescriptor.of(d)); // 声明优先
            }
        }
        List<SubagentDescriptor> out = new ArrayList<>(byName.values());
        out.sort(Comparator.comparing(SubagentDescriptor::name));
        return out;
    }
}
