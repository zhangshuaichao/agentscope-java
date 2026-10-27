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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.skill.SkillResources;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * harness 原生的 {@code load_skill_through_path} {@link AgentTool}。
 *
 * <p>除 {@code SKILL.md} 以外的任意 {@code path}，查询顺序如下：
 * <ol>
 * <li>{@code skill.getResources().get(path)}：内存映射（第 1/3 层宿主仓库，以及预加载全部资源的市场仓库）。
 * <li>{@code entry.lazyResources().read(path)}：实现 {@link io.agentscope.harness.agent.skill.LazyResourceCapable} 的来源（例如 {@code WorkspaceSkillRepository}）所提供的文件系统回退。
 * <li>未找到：返回错误，并附上 {@code SKILL.md}、内存键及懒加载列表合并去重后的枚举。
 * </ol>
 *
 * <p>已注册工具从每次{@link ToolCallParam#getRuntimeContext() tool call's RuntimeContext}解析过滤目录，无需重新注册即可隔离并发调用和会话。只有已弃用的调用方完全未提供 RuntimeContext 时，才查阅旧目录引用。
 */
@SuppressWarnings("deprecation")
public final class SkillLoadTool implements AgentTool {

    public static final String TOOL_NAME = "load_skill_through_path";
    private static final String SKILL_FILE = "SKILL.md";

    private static final Logger log = LoggerFactory.getLogger(SkillLoadTool.class);

    private final AtomicReference<SkillCatalog> legacyCatalogRef;
    private final boolean exposeLegacyCatalogInSchema;

    /**
     * 创建上下文限定的加载器，其无上下文兼容目录为空。
     */
    public SkillLoadTool() {
        this(new AtomicReference<>(SkillCatalog.empty()), false);
    }

    /**
     * 创建使用旧无上下文目录引用的加载器。
     *
     * <p>保留此构造方法以兼容源码和二进制。工具调用携带 RuntimeContext 时绝不使用该引用，避免绕过请求级技能过滤器。
     *
     * @deprecated 优先使用 {@link #SkillLoadTool()}，并通过 {@link SkillRuntime#install(SkillCatalog, io.agentscope.core.agent.RuntimeContext, io.agentscope.core.tool.Toolkit)} 绑定目录。
     */
    @Deprecated(since = "2.2.0")
    public SkillLoadTool(AtomicReference<SkillCatalog> catalogRef) {
        this(catalogRef, true);
    }

    SkillLoadTool(
            AtomicReference<SkillCatalog> legacyCatalogRef, boolean exposeLegacyCatalogInSchema) {
        if (legacyCatalogRef == null) {
            throw new IllegalArgumentException("catalogRef must not be null");
        }
        this.legacyCatalogRef = legacyCatalogRef;
        this.exposeLegacyCatalogInSchema = exposeLegacyCatalogInSchema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Load and return a skill resource by its skill id and a path relative to the"
                + " skill root.\n\n"
                + "Path rules:\n"
                + "- Use path=\"SKILL.md\" to load the skill's markdown documentation.\n"
                + "- Use exact resource paths listed by the skill, e.g."
                + " \"references/guide.md\" or \"scripts/run.py\".\n"
                + "- Do not use '.', './', the skill directory itself, or an absolute path.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> skillIdSchema;
        if (exposeLegacyCatalogInSchema) {
            SkillCatalog snapshot = legacyCatalogRef.get();
            List<String> ids = snapshot != null ? snapshot.ids() : List.of();
            skillIdSchema =
                    Map.of(
                            "type", "string",
                            "description", "Unique skill identifier.",
                            "enum", ids);
        } else {
            skillIdSchema =
                    Map.of(
                            "type",
                            "string",
                            "description",
                            "Unique skill identifier listed in the current <available_skills>"
                                    + " block.");
        }
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "skillId",
                                skillIdSchema,
                                "path",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "Exact resource path within the skill."
                                                + " Use 'SKILL.md' for the skill's"
                                                + " instructions; do not use '.',"
                                                + " './', directories, or absolute"
                                                + " paths.")),
                "required", List.of("skillId", "path"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            Map<String, Object> input = param.getInput();
            String skillId = stringOrNull(input.get("skillId"));
            String path = stringOrNull(input.get("path"));
            if (skillId == null || skillId.isEmpty()) {
                return Mono.just(
                        ToolResultBlock.error("Missing or empty required parameter: skillId"));
            }
            if (path == null || path.isEmpty()) {
                return Mono.just(
                        ToolResultBlock.error("Missing or empty required parameter: path"));
            }

            SkillCatalog catalog = catalogFor(param);
            HarnessSkillEntry entry = catalog.get(skillId);
            if (entry == null) {
                return Mono.just(
                        ToolResultBlock.error(
                                "Skill not found: '"
                                        + skillId
                                        + "'. Check the available skills list."));
            }

            return Mono.just(loadOne(entry, path));
        } catch (Exception e) {
            log.error("load_skill_through_path failed", e);
            return Mono.just(ToolResultBlock.error(e.getMessage()));
        }
    }

    private SkillCatalog catalogFor(ToolCallParam param) {
        if (param.getRuntimeContext() != null) {
            return SkillRuntime.catalogFor(param.getRuntimeContext());
        }
        SkillCatalog legacy = legacyCatalogRef.get();
        return legacy != null ? legacy : SkillCatalog.empty();
    }

    private ToolResultBlock loadOne(HarnessSkillEntry entry, String path) {
        AgentSkill skill = entry.skill();

        // 1. 特殊处理：SKILL.md 通过格式化方法返回完整技能文档。
        if (SKILL_FILE.equals(path)) {
            return ToolResultBlock.text(formatSkillMarkdown(entry));
        }

        // 2. 内存映射（第 1/3 层宿主仓库及预加载全部资源的市场）。
        Map<String, String> mem = skill.getResources();
        if (mem != null && mem.containsKey(path)) {
            return ToolResultBlock.text(formatResource(entry, path, mem.get(path)));
        }

        // 3. 懒加载回退（WorkspaceSkillRepository 及其他 LazyResourceCapable 来源）。
        if (entry.lazyResources() != null) {
            Optional<String> lazy = entry.lazyResources().read(path);
            if (lazy.isPresent()) {
                return ToolResultBlock.text(formatResource(entry, path, lazy.get()));
            }
        }

        // 4. 未找到：枚举两种来源，让模型看到实际可选项。
        return ToolResultBlock.error(formatNotFound(entry, path));
    }

    // ---------------------------------------------------------------------
    // 格式化辅助方法
    // ---------------------------------------------------------------------

    /**
     * 用于分隔文件内容与外围工具结果头部的围栏。
     * 使用四个反引号，因此即使正文中包含更常见的三个反引号代码围栏，也不会混淆。
     */
    private static final String CONTENT_FENCE = "````";

    private String formatSkillMarkdown(HarnessSkillEntry entry) {
        AgentSkill skill = entry.skill();
        StringBuilder sb = new StringBuilder();
        sb.append("Successfully loaded skill: ").append(skill.getSkillId()).append("\n\n");
        sb.append("Name: ").append(skill.getName()).append("\n");
        sb.append("Description: ").append(skill.getDescription()).append("\n");
        sb.append("Source: ").append(skill.getSource()).append("\n");
        if (entry.filesRoot() != null && !entry.filesRoot().isBlank()) {
            sb.append("Files root: ").append(entry.filesRoot()).append("\n");
        }
        // 输出完整 SKILL.md 文档（YAML frontmatter 加正文），让 LLM 读取的视图
        // 与 skill_manage(action=patch) 操作的字节一致。只返回正文
        // （旧行为）会让 LLM 在 frontmatter 边界附近使用 old_string 时
        // 补丁失败，因为解析器会消耗那里的空白。
        String fullMarkdown =
                MarkdownSkillParser.generate(skill.getMetadata(), skill.getSkillContent());
        sb.append("\nContent (").append("SKILL.md").append("):\n");
        sb.append(CONTENT_FENCE).append("markdown\n");
        sb.append(fullMarkdown);
        if (!fullMarkdown.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append(CONTENT_FENCE).append("\n");
        return sb.toString();
    }

    private String formatResource(HarnessSkillEntry entry, String path, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("Successfully loaded resource from skill: ")
                .append(entry.skill().getSkillId())
                .append("\n");
        sb.append("Resource path: ").append(path).append("\n");
        if (entry.filesRoot() != null && !entry.filesRoot().isBlank()) {
            sb.append("Files root: ").append(entry.filesRoot()).append("\n");
        }
        sb.append("\nContent (").append(path).append("):\n");
        sb.append(CONTENT_FENCE).append("\n");
        sb.append(content);
        if (content == null || !content.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append(CONTENT_FENCE).append("\n");
        return sb.toString();
    }

    private String formatNotFound(HarnessSkillEntry entry, String missingPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("Resource not found: '")
                .append(missingPath)
                .append("' in skill '")
                .append(entry.skill().getSkillId())
                .append("'.\n\n");

        LinkedHashSet<String> available = new LinkedHashSet<>();
        available.add(SKILL_FILE);

        Map<String, String> mem = entry.skill().getResources();
        if (mem != null) {
            available.addAll(mem.keySet());
        }

        SkillResources lazy = entry.lazyResources();
        if (lazy != null) {
            try {
                available.addAll(lazy.list());
            } catch (Exception e) {
                log.debug(
                        "lazyResources.list() failed for '{}': {}",
                        entry.skill().getSkillId(),
                        e.getMessage());
            }
        }

        sb.append("Available resources:\n");
        int i = 1;
        List<String> ordered = new ArrayList<>(available);
        for (String p : ordered) {
            sb.append(i++).append(". ").append(p).append("\n");
        }
        return sb.toString();
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
