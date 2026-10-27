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
package io.agentscope.core.skill;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 生成技能系统提示，帮助智能体了解可用技能。
 *
 * <p>提示包含 LLM 可动态加载和使用的技能信息。
 *
 * <p><b>用法示例：</b>
 * <pre>{@code
 * AgentSkillPromptProvider provider = new AgentSkillPromptProvider(registry);
 * String prompt = provider.getSkillSystemPrompt();
 * }</pre>
 */
public class AgentSkillPromptProvider {
    private static final String INDENT = "  ";
    private static final Pattern XML_TAG_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private final SkillRegistry skillRegistry;
    private final String instruction;
    private boolean exposeAllMetadata = true;
    private boolean codeExecutionEnabled;
    private String uploadDir;
    private String codeExecutionInstruction;

    public static final String DEFAULT_AGENT_SKILL_INSTRUCTION =
            """
            ## Available Skills

            <usage>
            Skills provide specialized capabilities and domain knowledge. Use them when they match your current task.

            How to use skills:
            - Load skill: load_skill_through_path(skillId="<skill-id>", path="SKILL.md")
            - The skill will be activated and its documentation loaded with detailed instructions
            - Additional resources (scripts, assets, references) can be loaded using the same tool with different paths

            Example:
            1. User asks to analyze data → find a matching skill below (e.g. <skill-id>data-analysis_builtin</skill-id>)
            2. Load it: load_skill_through_path(skillId="data-analysis_builtin", path="SKILL.md")
            3. Follow the instructions returned by the skill

            Metadata is rendered as XML under each <skill> element:
            - scalar metadata becomes a simple child element
            - nested maps become nested XML elements
            - lists become repeated <item> elements
            - <skill-id> is always appended for tool loading
            </usage>

            <available_skills>

            """;

    // 模板中的每个 %s 占位符都会替换为 uploadDir 的绝对路径。
    public static final String DEFAULT_CODE_EXECUTION_INSTRUCTION =
            """

            ## Code Execution

            <code_execution>
            You have access to the execute_shell_command tool. When a task can be accomplished by running\s
            a pre-deployed skill script, you MUST execute it yourself using execute_shell_command rather\s
            than describing or suggesting commands to the user.

            Skills root directory: %s
            Each skill's files are located under a subdirectory named by its <skill-id>:
              %s/<skill-id>/scripts/
              %s/<skill-id>/assets/

            Workflow:
            1. After loading a skill, use ls to explore its directory structure and discover available scripts/assets
            2. Once you find the right script, execute it immediately with its absolute path
            3. If execution fails, diagnose and retry — do not fall back to describing the command

            Rules:
            - Always use absolute paths when executing scripts
            - If a script exists for the task, run it directly — do not rewrite its logic inline
            - If asset/data files exist for the task, read them directly — do not recreate them

            Example:
              # Explore what scripts are available for a skill
              execute_shell_command(command="ls %s/data-analysis_builtin/scripts/")

              # Run an existing script with absolute path
              execute_shell_command(command="python3 %s/data-analysis_builtin/scripts/analyze.py")
            </code_execution>
            """;

    /**
     * 当所有可见技能都携带自己的 {@code <files-root>} 时使用的代码执行指令（例如 {@link io.agentscope.core.skill.repository.FileSystemSkillRepository}）。无需替换单一根路径，LLM 直接从 {@code <available_skills>} 块读取每个技能的 {@code <files-root>}。
     */
    public static final String DEFAULT_PER_SKILL_CODE_EXECUTION_INSTRUCTION =
            """

            ## Code Execution

            <code_execution>
            You have access to the execute_shell_command tool. Each skill in <available_skills>
            includes a <files-root> element giving the absolute path to that skill's files.

            Workflow:
            1. After loading a skill, look at its <files-root> in <available_skills>
            2. List its files:    ls <files-root>/
            3. Run scripts:       python3 <files-root>/scripts/<script-name>
            4. Always use absolute paths derived from <files-root>; never invent paths
            5. If a script exists for the task, run it directly — do not rewrite its logic inline
            </code_execution>
            """;

    /**
     * 创建技能提示提供者。
     *
     * @param registry 包含已注册技能的注册表
     */
    public AgentSkillPromptProvider(SkillRegistry registry) {
        this(registry, null);
    }

    /**
     * 使用自定义指令创建技能提示提供者。
     *
     * @param registry 包含已注册技能的注册表
     * @param instruction 自定义指令头（null 或空白使用默认值）
     */
    public AgentSkillPromptProvider(SkillRegistry registry, String instruction) {
        this.skillRegistry = registry;
        this.instruction =
                instruction == null || instruction.isBlank()
                        ? DEFAULT_AGENT_SKILL_INSTRUCTION
                        : instruction;
    }

    AgentSkillPromptProvider copyFor(SkillRegistry registry) {
        AgentSkillPromptProvider copy = new AgentSkillPromptProvider(registry, instruction);
        copy.exposeAllMetadata = exposeAllMetadata;
        copy.codeExecutionEnabled = codeExecutionEnabled;
        copy.uploadDir = uploadDir;
        copy.codeExecutionInstruction = codeExecutionInstruction;
        return copy;
    }

    /**
     * 获取包含全部技能的智能体技能系统提示。
     *
     * @return 技能系统提示；无技能时为空字符串
     */
    public String getSkillSystemPrompt() {
        return getSkillSystemPrompt(SkillFilter.all());
    }

    /**
     * 获取经给定 {@link SkillFilter} 过滤后的技能系统提示。
     *
     * <p>过滤器按{@link AgentSkill#getName() skill name}匹配，而不是复合注册 ID，因此 {@code only("x")} / {@code disable("x")} 作用于 {@code x} 的全部来源变体，而 {@code "x_custom"} 等复合 ID 不会匹配。
     *
     * @param filter 决定包含哪些技能的过滤器（null 视为全部）
     * @return 技能系统提示；无技能通过过滤时为空字符串
     */
    public String getSkillSystemPrompt(SkillFilter filter) {
        SkillFilter effectiveFilter = filter != null ? filter : SkillFilter.all();

        Set<String> skillIds = skillRegistry.getSkillIds();
        if (skillIds.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean hasSkills = false;
        int visibleCount = 0;
        int withOriginDir = 0;

        for (String skillId : skillIds) {
            AgentSkill skill = skillRegistry.getSkill(skillId);
            if (skill == null || !effectiveFilter.isAllowed(skill.getName())) {
                continue;
            }
            if (!hasSkills) {
                sb.append(instruction);
                hasSkills = true;
            }
            appendSkill(sb, skill);
            visibleCount++;
            if (skill.getOriginDir().isPresent()) {
                withOriginDir++;
            }
        }

        if (!hasSkills) {
            return "";
        }

        sb.append("</available_skills>");

        if (codeExecutionEnabled) {
            sb.append(resolveCodeExecutionBlock(visibleCount, withOriginDir));
        }

        return sb.toString();
    }

    /**
     * 选择代码执行提示块。
     *
     * <ul>
     * <li>若通过 {@link #setCodeExecutionInstruction(String)} 设置了自定义模板，则原样使用（同时存在 {@code %s} 与 {@code uploadDir} 时执行替换）。
     * <li>若所有可见技能都有 {@code <files-root>}，则使用 {@link #DEFAULT_PER_SKILL_CODE_EXECUTION_INSTRUCTION}，无需替换。
     * <li>否则回退到 {@link #DEFAULT_CODE_EXECUTION_INSTRUCTION}（单一 uploadDir）。此分支中若 {@code uploadDir} 为 null，则返回空值，因为模板必须有实际绝对路径才有意义。
     * </ul>
     */
    private String resolveCodeExecutionBlock(int visibleCount, int withOriginDir) {
        if (codeExecutionInstruction != null) {
            return uploadDir != null
                    ? codeExecutionInstruction.replace("%s", uploadDir)
                    : codeExecutionInstruction;
        }
        if (visibleCount > 0 && withOriginDir == visibleCount) {
            return DEFAULT_PER_SKILL_CODE_EXECUTION_INSTRUCTION;
        }
        if (uploadDir != null) {
            return DEFAULT_CODE_EXECUTION_INSTRUCTION.replace("%s", uploadDir);
        }
        return "";
    }

    /**
     * 设置技能系统提示是否包含代码执行指令。
     *
     * @param codeExecutionEnabled {@code true} 表示追加代码执行指令
     */
    public void setCodeExecutionEnable(boolean codeExecutionEnabled) {
        this.codeExecutionEnabled = codeExecutionEnabled;
    }

    /**
     * 设置上传目录，其绝对路径将替换代码执行指令模板中的每个 {@code %s} 占位符。
     *
     * @param uploadDir 上传目录路径；{@code null} 表示禁用路径替换
     */
    public void setUploadDir(Path uploadDir) {
        this.uploadDir = uploadDir != null ? uploadDir.toAbsolutePath().toString() : null;
    }

    /**
     * 设置自定义代码执行指令模板。
     *
     * <p>模板中的每个 {@code %s} 占位符都会替换为 {@code uploadDir} 的绝对路径。传入 {@code null} 或空白时回退到 {@link #DEFAULT_CODE_EXECUTION_INSTRUCTION}。
     *
     * @param codeExecutionInstruction 自定义模板；{@code null} 或空白表示使用默认值
     */
    public void setCodeExecutionInstruction(String codeExecutionInstruction) {
        this.codeExecutionInstruction =
                codeExecutionInstruction == null || codeExecutionInstruction.isBlank()
                        ? null
                        : codeExecutionInstruction;
    }

    /**
     * 设置是否向 LLM 暴露全部元数据字段。
     *
     * <p>禁用时，技能提示仅渲染 {@code name}、{@code description} 和 {@code skill-id}。
     *
     * @param exposeAllMetadata {@code true} 暴露全部元数据，{@code false} 仅暴露核心字段
     */
    public void setExposeAllMetadata(boolean exposeAllMetadata) {
        this.exposeAllMetadata = exposeAllMetadata;
    }

    private void appendSkill(StringBuilder sb, AgentSkill skill) {
        sb.append("<skill>\n");
        for (Map.Entry<String, Object> entry : getPromptMetadata(skill).entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            appendXmlNode(sb, entry.getKey(), entry.getValue(), 1);
        }
        appendXmlNode(sb, "skill-id", skill.getSkillId(), 1);
        // 每个技能的 files-root：仅在启用代码执行且技能
        // 具有 originDir（文件系统来源）时输出，使 LLM 能构造绝对 Shell 路径，
        // 无需经过 uploadSkillFiles 或猜测目录布局。
        if (codeExecutionEnabled) {
            skill.getOriginDir()
                    .ifPresent(
                            originDir -> appendXmlNode(sb, "files-root", originDir.toString(), 1));
        }
        sb.append("</skill>\n\n");
    }

    private Map<String, Object> getPromptMetadata(AgentSkill skill) {
        if (exposeAllMetadata) {
            return skill.getMetadata();
        }

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", skill.getName());
        metadata.put("description", skill.getDescription());
        return metadata;
    }

    private void appendXmlNode(StringBuilder sb, String key, Object value, int indentLevel) {
        if (value == null) {
            return;
        }

        String indent = INDENT.repeat(indentLevel);
        boolean validTagName = isValidXmlTagName(key);
        String openTag = validTagName ? "<" + key + ">" : "<entry key=\"" + escapeXml(key) + "\">";
        String closeTag = validTagName ? "</" + key + ">" : "</entry>";

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
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                appendXmlNode(
                        sb, String.valueOf(entry.getKey()), entry.getValue(), indentLevel + 1);
            }
        } else if (value instanceof Collection<?> collectionValue) {
            for (Object item : collectionValue) {
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
        return value != null && XML_TAG_NAME_PATTERN.matcher(value).matches();
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
