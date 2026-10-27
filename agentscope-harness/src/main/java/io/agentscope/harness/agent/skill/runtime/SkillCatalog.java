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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 {@code onSystemPrompt} 处理可见技能的不可变快照。
 *
 * <p>以 {@code skillId}（AgentSkill 的 {@code name + "_" + source}）为键，保留插入顺序，让提示按组合顺序渲染技能。
 */
public final class SkillCatalog {

    private final Map<String, HarnessSkillEntry> entries;

    private SkillCatalog(Map<String, HarnessSkillEntry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public static SkillCatalog empty() {
        return new SkillCatalog(Collections.emptyMap());
    }

    public static SkillCatalog of(List<HarnessSkillEntry> orderedEntries) {
        LinkedHashMap<String, HarnessSkillEntry> map = new LinkedHashMap<>();
        for (HarnessSkillEntry e : orderedEntries) {
            if (e == null) {
                continue;
            }
            map.put(e.skill().getSkillId(), e);
        }
        return new SkillCatalog(map);
    }

    /**
     * 按 {@code skillId}（name_source）查找；不存在时返回 {@code null}。
     */
    public HarnessSkillEntry get(String skillId) {
        return skillId == null ? null : entries.get(skillId);
    }

    /**
     * 按组合顺序返回全部条目（优先级从低到高）。
     */
    public Collection<HarnessSkillEntry> all() {
        return entries.values();
    }

    /**
     * 按组合顺序返回全部 skillId。
     */
    public List<String> ids() {
        return List.copyOf(entries.keySet());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }
}
