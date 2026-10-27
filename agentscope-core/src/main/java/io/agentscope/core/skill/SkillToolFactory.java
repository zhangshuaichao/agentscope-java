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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 创建技能访问工具的工厂，使智能体能够动态加载和访问技能。
 *
 * <p>【学习提示】技能是按需加载的指令和资源，不等同于 Java 工具实现。读取技能资源与激活关联工具分组相连，但激活写入当前会话状态；共享工具定义不因此改变，也不能把技能文本当作越过执行权限的凭据。
 */
class SkillToolFactory {

    private static final Logger logger = LoggerFactory.getLogger(SkillToolFactory.class);

    static final String LOAD_TOOL_NAME = "load_skill_through_path";
    private static final String LOAD_TOOL_DESCRIPTION =
            "Load and activate a skill resource by its ID and resource path.\n\n"
                + "**Functionality:**\n"
                + "1. Activates the specified skill for this session (adding its tool groups to"
                + " this session's active set)\n"
                + "2. Returns the requested resource content\n"
                + "\n"
                + "**Path rules:**\n"
                + "- Use path=\"SKILL.md\" to load the skill's markdown documentation (name,"
                + " description, usage instructions).\n"
                + "- Use exact resource paths listed by the skill, such as \"references/guide.md\""
                + " or \"scripts/run.py\".\n"
                + "- Do not use '.', './', the skill directory, or an absolute path.";

    private final SkillRegistry skillRegistry;
    private volatile Toolkit toolkit;

    SkillToolFactory(SkillRegistry skillRegistry, Toolkit toolkit) {
        this.skillRegistry = skillRegistry;
        this.toolkit = toolkit;
    }

    /**
     * 将共享工具包绑定到此技能工具工厂（只读引用）。
     *
     * @param toolkit 要绑定的工具包
     * @throws IllegalArgumentException 当工具包为 null 时
     */
    void bindToolkit(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    /**
     * 检查给定工具包中是否已有 {@code load_skill_through_path} 工具，不论其由哪条路径注册（动态中间件、静态 {@code SkillBox} 或其他调用方）。
     *
     * @param toolkit 要检查的工具包
     * @return 已注册同名加载工具时为 {@code true}
     */
    static boolean isRuntimeLoadToolRegistered(Toolkit toolkit) {
        return toolkit.getTool(LOAD_TOOL_NAME) != null;
    }

    /**
     * 向给定工具包注册共享的、运行时解析的 {@code load_skill_through_path} 工具。用于动态技能路径：构建时仅注册一次，调用时从 {@link RuntimeContext} 解析当前调用的 {@link SkillBox}，因此不会在每次调用时修改共享工具包。
     *
     * @param toolkit 要注册加载工具的共享工具包
     */
    static void registerRuntimeLoadTool(Toolkit toolkit) {
        if (isRuntimeLoadToolRegistered(toolkit)) {
            // 已由其他路径注册（例如静态 skillBox），不要覆盖。
            // 不支持混用静态和动态技能配置，因此明确报告此情况。
            logger.warn(
                    "load_skill_through_path already registered; skipping (a same-named tool is"
                            + " already present on this toolkit)");
            return;
        }
        // 注册为无分组工具，使其始终可见、可调用，且不会被
        // META 范围的 reset_equipped_tools 替换操作移除（与 reset_equipped_tools 本身一样）。
        toolkit.registration().agentTool(createRuntimeLoadTool()).apply();
    }

    /**
     * 创建共享的 {@code load_skill_through_path} 工具，在调用时解析当前调用的 {@link SkillBox}。与 {@link #createSkillAccessToolAgentTool()} 不同，此工具不携带单个 box 的状态，因此可在并发会话之间安全共享同一实例。
     */
    static AgentTool createRuntimeLoadTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return LOAD_TOOL_NAME;
            }

            @Override
            public String getDescription() {
                return LOAD_TOOL_DESCRIPTION;
            }

            @Override
            public Map<String, Object> getParameters() {
                // 构建时尚不知道动态技能，因此省略参数枚举；
                // 改由系统提示公布技能目录。
                return Map.of(
                        "type", "object",
                        "properties",
                                Map.of(
                                        "skillId",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "The unique identifier of the skill."),
                                        "path",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "The exact resource path within the"
                                                                + " skill. Use 'SKILL.md' to load"
                                                                + " the skill instructions. Do not"
                                                                + " use '.', './', directories, or"
                                                                + " absolute paths.")),
                        "required", List.of("skillId", "path"));
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                try {
                    SkillBox box = resolveSkillBox(param);
                    if (box == null) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "load_skill_through_path: no skill context for this"
                                                + " call"));
                    }
                    return Mono.just(ToolResultBlock.text(box.loadSkillResource(param)));
                } catch (IllegalArgumentException e) {
                    logger.warn("Error loading skill resource: {}", e.getMessage());
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                } catch (Exception e) {
                    logger.error("Unexpected error loading skill resource", e);
                    return Mono.just(
                            ToolResultBlock.error(
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : "Unexpected error: " + e.getClass().getSimpleName()));
                }
            }
        };
    }

    private static SkillBox resolveSkillBox(ToolCallParam param) {
        RuntimeContext rc = param.getRuntimeContext();
        return rc != null ? rc.get(SkillBox.class) : null;
    }

    /**
     * 创建 load_skill_through_path 智能体工具。
     *
     * <p>允许智能体通过技能 ID 和资源路径加载并激活技能，支持加载技能文档 SKILL.md，以及脚本、配置和模板等其他资源。
     *
     * @return 加载技能资源（包括 SKILL.md）的 AgentTool
     */
    AgentTool createSkillAccessToolAgentTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return LOAD_TOOL_NAME;
            }

            @Override
            public String getDescription() {
                return LOAD_TOOL_DESCRIPTION;
            }

            @Override
            public Map<String, Object> getParameters() {
                // 获取全部可用技能 ID。
                List<String> availableSkillIds = new ArrayList<>(skillRegistry.getSkillIds());

                return Map.of(
                        "type", "object",
                        "properties",
                                Map.of(
                                        "skillId",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "The unique identifier of the" + " skill.",
                                                        "enum",
                                                        availableSkillIds),
                                        "path",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "The exact resource path within the skill."
                                                                + " Use 'SKILL.md' to load the"
                                                                + " skill instructions. Do not use"
                                                                + " '.', './', directories, or"
                                                                + " absolute paths.")),
                        "required", List.of("skillId", "path"));
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                try {
                    Map<String, Object> input = param.getInput();

                    // 校验参数。
                    String skillId = (String) input.get("skillId");
                    if (skillId == null || skillId.trim().isEmpty()) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "Missing or empty required parameter: skillId"));
                    }

                    String path = (String) input.get("path");
                    if (path == null || path.trim().isEmpty()) {
                        return Mono.just(
                                ToolResultBlock.error("Missing or empty required parameter: path"));
                    }

                    String result = loadSkillResourceImpl(skillId, path, resolveToolContext(param));
                    return Mono.just(ToolResultBlock.text(result));
                } catch (IllegalArgumentException e) {
                    logger.warn("Error loading skill resource: {}", e.getMessage());
                    return Mono.just(ToolResultBlock.error(e.getMessage()));
                } catch (Exception e) {
                    logger.error("Unexpected error loading skill resource", e);
                    return Mono.just(
                            ToolResultBlock.error(
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : "Unexpected error: " + e.getClass().getSimpleName()));
                }
            }
        };
    }

    /**
     * 技能资源加载逻辑的实现。
     *
     * @param skillId 技能唯一标识
     * @param path 资源文件路径
     * @return 格式化后的资源内容，或包含可用资源的错误消息
     * @throws IllegalArgumentException 当技能不存在或资源未找到时
     */
    String loadSkillResourceImpl(String skillId, String path, ToolContextState tcs) {
        AgentSkill skill = validateSkillExists(skillId);

        // 特殊处理 SKILL.md：返回技能的 Markdown 内容。
        if ("SKILL.md".equals(path)) {
            activateSkill(skillId, tcs);
            return buildSkillMarkdownResponse(skillId, skill);
        }

        // 1. 内存映射（预加载文件系统仓库、classpath 仓库、市场预取）。
        Map<String, String> resources = skill.getResources();
        if (resources != null && resources.containsKey(path)) {
            activateSkill(skillId, tcs);
            return buildResourceResponse(skillId, path, resources.get(path));
        }

        // 2. 通过 skill.originDir 回退到磁盘（懒加载文件系统仓库，或其他基于文件系统、
        //    但未将请求路径预加载到内存的技能）。沿用公开路径模式的清理规则：
        //    不允许 '..'、绝对路径或目录。
        Optional<String> sanitized = sanitizeRelativePath(path);
        if (sanitized.isPresent() && skill.getOriginDir().isPresent()) {
            Optional<String> diskContent =
                    readFromOriginDir(skill.getOriginDir().get(), sanitized.get());
            if (diskContent.isPresent()) {
                activateSkill(skillId, tcs);
                return buildResourceResponse(skillId, path, diskContent.get());
            }
        }

        // 3. 未找到：同时枚举两种来源，让模型获得实际可选项。
        throw new IllegalArgumentException(
                buildResourceNotFoundMessage(
                        skillId, path, resources, skill.getOriginDir().orElse(null)));
    }

    private static Optional<String> sanitizeRelativePath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            return Optional.empty();
        }
        if (".".equals(normalized) || "./".equals(normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    /**
     * 在磁盘回退路径中读取 {@code originDir} 下的单个文件。文本内容原样返回；二进制内容采用与 {@code SkillFileSystemHelper.readAndPutResource} 一致的 {@code base64:} 前缀约定，因此无论资源来自内存还是磁盘，加载工具的约定都一致。
     */
    private static Optional<String> readFromOriginDir(Path originDir, String relPath) {
        Path target = originDir.resolve(relPath).normalize();
        if (!target.startsWith(originDir)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (MalformedInputException e) {
            try {
                byte[] bytes = Files.readAllBytes(target);
                return Optional.of("base64:" + Base64.getEncoder().encodeToString(bytes));
            } catch (IOException ex) {
                logger.warn("Failed to read binary resource {}: {}", target, ex.getMessage());
                return Optional.empty();
            }
        } catch (IOException e) {
            logger.warn("Failed to read resource {}: {}", target, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 构建 SKILL.md 内容的响应。
     *
     * @param skillId 技能 ID
     * @param skill 技能实例
     * @return 格式化后的技能 Markdown 响应
     */
    private String buildSkillMarkdownResponse(String skillId, AgentSkill skill) {
        StringBuilder result = new StringBuilder();
        result.append("Successfully loaded skill: ").append(skillId).append("\n\n");
        result.append("Name: ").append(skill.getName()).append("\n");
        result.append("Description: ").append(skill.getDescription()).append("\n");
        result.append("Source: ").append(skill.getSource()).append("\n\n");
        result.append("Content:\n");
        result.append("---\n");
        result.append(skill.getSkillContent());
        result.append("\n---\n");
        return result.toString();
    }

    /**
     * 构建普通资源内容的响应。
     *
     * @param skillId 技能 ID
     * @param path 资源路径
     * @param resourceContent 资源内容
     * @return 格式化后的资源响应
     */
    private String buildResourceResponse(String skillId, String path, String resourceContent) {
        StringBuilder result = new StringBuilder();
        result.append("Successfully loaded resource from skill: ").append(skillId).append("\n");
        result.append("Resource path: ").append(path).append("\n\n");
        result.append("Content:\n");
        result.append("---\n");
        result.append(resourceContent);
        result.append("\n---\n");
        return result.toString();
    }

    /**
     * 未找到资源时，构建包含可用资源路径的错误消息。
     *
     * @param skillId 技能 ID
     * @param path 未找到的请求路径
     * @param resources 可用资源映射
     * @return 包含可用资源的格式化错误消息
     */
    private String buildResourceNotFoundMessage(
            String skillId, String path, Map<String, String> resources, Path originDir) {
        StringBuilder message = new StringBuilder();
        message.append("Resource not found: '")
                .append(path)
                .append("' in skill '")
                .append(skillId)
                .append("'.\n\n");

        // 合并内存键与磁盘条目并去重，使模型能够
        // 在同一处看到两类资源。
        Set<String> resourcePaths = new LinkedHashSet<>();
        resourcePaths.add("SKILL.md");
        if (resources != null) {
            resourcePaths.addAll(resources.keySet());
        }
        if (originDir != null) {
            resourcePaths.addAll(listOriginDirEntries(originDir));
        }

        message.append("Available resources:\n");
        int i = 1;
        for (String entry : resourcePaths) {
            message.append(i++).append(". ").append(entry).append("\n");
        }

        return message.toString();
    }

    private static List<String> listOriginDirEntries(Path originDir) {
        List<String> entries = new ArrayList<>();
        try (var stream = Files.walk(originDir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(
                            p -> {
                                String rel = originDir.relativize(p).toString().replace('\\', '/');
                                if (!rel.isBlank() && !"SKILL.md".equals(rel)) {
                                    entries.add(rel);
                                }
                            });
        } catch (IOException e) {
            logger.debug(
                    "Failed to enumerate {} for not-found listing: {}", originDir, e.getMessage());
        }
        return entries;
    }

    /**
     * 校验技能已注册并返回其实例。
     *
     * @param skillId 技能唯一标识
     * @return 已注册的技能实例
     * @throws IllegalArgumentException 当技能未注册时
     * @throws IllegalStateException 当校验后仍无法加载技能时
     */
    private AgentSkill validateSkillExists(String skillId) {
        if (!skillRegistry.exists(skillId)) {
            throw new IllegalArgumentException(
                    String.format("Skill not found: '%s'. Please check the skill ID.", skillId));
        }
        // 获取技能。
        AgentSkill skill = skillRegistry.getSkill(skillId);
        if (skill == null) {
            throw new IllegalStateException(
                    String.format(
                            "Failed to load skill '%s' after validation. This is an internal"
                                    + " error.",
                            skillId));
        }
        return skill;
    }

    /**
     * 从工具调用的运行时上下文解析当前调用的 {@link ToolContextState}，使技能激活作用于当前会话的激活集合，而不是共享分组管理器。
     *
     * @param param 工具调用参数
     * @return 解析出的工具上下文状态；不可用时为 {@code null}
     */
    ToolContextState resolveToolContext(ToolCallParam param) {
        RuntimeContext rc = param.getRuntimeContext();
        AgentState state = rc != null ? rc.getAgentState() : null;
        return state != null ? state.getToolContext() : null;
    }

    private void activateSkill(String skillId, ToolContextState tcs) {
        // 无会话级上下文：跳过工具分组门控，但不修改共享状态。
        if (tcs == null) {
            logger.warn(
                    "load_skill_through_path: no per-session runtime context; tool-group activation"
                            + " skipped");
            return;
        }

        // 只读发现此技能所属的分组：约定名称分组
        // （skillId_skill_tools），以及通过 activateOnSkill 绑定的 SkillToolGroup。共享分组
        // 定义始终不变。
        List<String> groups = new ArrayList<>();
        String toolsGroupName = skillId + "_skill_tools";
        if (toolkit != null && toolkit.getToolGroup(toolsGroupName) != null) {
            groups.add(toolsGroupName);
        }
        AgentSkill agentSkill = skillRegistry.getSkill(skillId);
        if (agentSkill != null && toolkit != null) {
            for (String group :
                    toolkit.findSkillToolGroupsByActivateOnSkill(agentSkill.getName())) {
                if (!groups.contains(group)) {
                    groups.add(group);
                }
            }
        }

        // 原子合并集合，避免同一会话内并行加载时丢失彼此的激活结果。
        tcs.addActivatedGroups(groups);
    }
}
