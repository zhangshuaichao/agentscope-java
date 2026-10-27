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

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从 {@link SkillCatalog} 渲染 harness 的 {@code <available_skills>} 系统提示块。
 *
 * <p>默认使用精简视图：每个条目对应一个 {@code <skill>} 元素，包含 {@code <name>}、{@code <description>}、{@code <skill-id>}，以及存在时的 {@code <files-root>}。使用 {@code exposeAllMetadata=true} 构建时，仍可启用完整 frontmatter 视图（将全部 YAML 元数据渲染为 XML）。
 *
 * <p>与旧 {@code AgentSkillPromptProvider} 的差异：
 * <ul>
 * <li>每个 {@code <skill>} 可包含 {@code <files-root>} 子元素，给出技能文件的绝对路径。中间件根据 Shell 可用性和技能来源决定是否填充。
 * <li>仅当目录中至少一个条目具有非 null 的 {@code filesRoot} 时才输出 {@code <code_execution>} 节（即 Shell 可用且至少一个技能文件可达）。新指令要求 LLM 使用各技能的 {@code <files-root>}，而不是单一硬编码根路径。
 * <li>非 SKILL.md 路径的资源回退由 {@link SkillLoadTool} 实现，无需在提示中说明。
 * </ul>
 */
@SuppressWarnings("deprecation")
public final class SkillPromptBuilder {

    private static final String INDENT = "  ";
    private static final Pattern XML_TAG_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    public static final String DEFAULT_HEADER =
            """
            ## Available Skills

            <usage>
            Skills provide specialized capabilities and domain knowledge. Use them when they match your current task.

            How to use a skill:
            - Load: load_skill_through_path(skillId="<skill-id>", path="SKILL.md") loads its documentation with detailed instructions
            - Other resources (scripts, assets, references) load with the same tool and other paths
            - <files-root>, when present, gives the absolute path to that skill's files
            </usage>

            <available_skills>

            """;

    public static final String DEFAULT_CODE_EXECUTION_INSTRUCTION =
            """

            ## Code Execution

            <code_execution>
            You have access to the execute tool. For skills that provide a <files-root>
            in <available_skills>, it gives the absolute path to their files.
            Skills without <files-root> provide documentation through load_skill_through_path;
            no shell-accessible resource directory is declared for them.

            Workflow:
            1. After loading a skill, check whether it provides <files-root> in <available_skills>
               Continue with file listing and script execution only when that field is present
            2. List its files:    ls <files-root>/
            3. Run scripts:       python3 <files-root>/scripts/<script-name>
            4. Always use absolute paths derived from <files-root>; never invent paths
            5. If a script exists for the task, run it directly — do not rewrite its logic inline
            </code_execution>
            """;

    private final String header;
    private final String codeExecutionInstruction;
    private final boolean exposeAllMetadata;

    public SkillPromptBuilder() {
        this(null, null, false);
    }

    public SkillPromptBuilder(
            String header, String codeExecutionInstruction, boolean exposeAllMetadata) {
        this.header = (header == null || header.isBlank()) ? DEFAULT_HEADER : header;
        this.codeExecutionInstruction =
                (codeExecutionInstruction == null || codeExecutionInstruction.isBlank())
                        ? DEFAULT_CODE_EXECUTION_INSTRUCTION
                        : codeExecutionInstruction;
        this.exposeAllMetadata = exposeAllMetadata;
    }

    /**
     * 渲染提示块。无技能通过过滤时返回空字符串，便于调用方直接拼接。
     *
     * @param catalog 单次调用快照（非 null）
     * @param filter 按技能名称应用的可见性过滤器（非 null；可用 {@link SkillFilter#all()}）
     * @return 提示文本；无可见内容时为空字符串
     */
    public String render(SkillCatalog catalog, SkillFilter filter) {
        if (catalog == null || catalog.isEmpty()) {
            return "";
        }
        SkillFilter effective = filter != null ? filter : SkillFilter.all();

        StringBuilder sb = new StringBuilder();
        boolean any = false;
        boolean anyWithFilesRoot = false;

        for (HarnessSkillEntry entry : catalog.all()) {
            String skillName = entry.skill().getName();
            if (!effective.isAllowed(skillName)) {
                continue;
            }
            if (!any) {
                sb.append(header);
                any = true;
            }
            appendSkill(sb, entry);
            if (entry.filesRoot() != null && !entry.filesRoot().isBlank()) {
                anyWithFilesRoot = true;
            }
        }

        if (!any) {
            return "";
        }
        sb.append("</available_skills>");

        // 至少一个可见技能能通过 Shell 访问时，才输出代码执行节。
        // 完全没有 filesRoot 意味着未注册 Shell 工具（或全部技能都不可达），
        // 此时输出执行指令会误导 LLM。
        if (anyWithFilesRoot) {
            sb.append(codeExecutionInstruction);
        }

        return sb.toString();
    }

    /**
     * 供中间件在无额外过滤器时使用的便捷重载。
     */
    public String render(SkillCatalog catalog) {
        return render(catalog, SkillFilter.all());
    }

    private void appendSkill(StringBuilder sb, HarnessSkillEntry entry) {
        AgentSkill skill = entry.skill();
        sb.append("<skill>\n");
        for (Map.Entry<String, Object> e : metadataView(skill).entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            appendXmlNode(sb, e.getKey(), e.getValue(), 1);
        }
        appendXmlNode(sb, "skill-id", skill.getSkillId(), 1);
        if (entry.filesRoot() != null && !entry.filesRoot().isBlank()) {
            appendXmlNode(sb, "files-root", entry.filesRoot(), 1);
        }
        sb.append("</skill>\n\n");
    }

    private Map<String, Object> metadataView(AgentSkill skill) {
        if (exposeAllMetadata) {
            return skill.getMetadata();
        }
        java.util.LinkedHashMap<String, Object> trimmed = new java.util.LinkedHashMap<>();
        trimmed.put("name", skill.getName());
        trimmed.put("description", skill.getDescription());
        return trimmed;
    }

    private void appendXmlNode(StringBuilder sb, String key, Object value, int indentLevel) {
        if (value == null) {
            return;
        }
        String indent = INDENT.repeat(indentLevel);
        boolean validTag = isValidXmlTagName(key);
        String openTag = validTag ? "<" + key + ">" : "<entry key=\"" + escapeXml(key) + "\">";
        String closeTag = validTag ? "</" + key + ">" : "</entry>";

        if (isScalarValue(value)) {
            sb.append(indent)
                    .append(openTag)
                    .append(escapeXml(String.valueOf(value)))
                    .append(closeTag)
                    .append("\n");
            return;
        }

        sb.append(indent).append(openTag).append("\n");
        if (value instanceof Map<?, ?> mapValue) {
            for (Map.Entry<?, ?> e : mapValue.entrySet()) {
                appendXmlNode(sb, String.valueOf(e.getKey()), e.getValue(), indentLevel + 1);
            }
        } else if (value instanceof Collection<?> collValue) {
            for (Object item : collValue) {
                appendXmlNode(sb, "item", item, indentLevel + 1);
            }
        } else {
            sb.append(INDENT.repeat(indentLevel + 1))
                    .append(escapeXml(String.valueOf(value)))
                    .append("\n");
        }
        sb.append(indent).append(closeTag).append("\n");
    }

    private boolean isScalarValue(Object value) {
        return !(value instanceof Map<?, ?>) && !(value instanceof Collection<?>);
    }

    private boolean isValidXmlTagName(String value) {
        return value != null && XML_TAG_NAME.matcher(value).matches();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
