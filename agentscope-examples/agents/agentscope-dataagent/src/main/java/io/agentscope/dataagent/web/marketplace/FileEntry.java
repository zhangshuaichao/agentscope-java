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

/** 贡献载荷中单个文件的内容及相对路径。<p>载荷始终为 {@code FileEntry} 列表，以 JSON 存于 {@code dataagent_contribution.payload}。单文件目标（subagent / memory / agents_md / knowledge）恰好一项，多文件技能包（SKILL.md 及附属资源）至少一项。<p>{@code relPath} 相对于目标类型共享目录解释。例如 {@code targetType=skill}、{@code targetPath="cohort-builder"} 且 {@code relPath="scripts/run.py"} 时，落在 {@code shared/agents/<agentId>/skills/cohort-builder/scripts/run.py}。{@code relPath} 为空时表示默认文件（技能为 {@code SKILL.md}，其他类型为目标文件本身）。 */
public record FileEntry(String relPath, String content) {
    public FileEntry {
        if (relPath == null) {
            relPath = "";
        }
        if (content == null) {
            content = "";
        }
    }
}
