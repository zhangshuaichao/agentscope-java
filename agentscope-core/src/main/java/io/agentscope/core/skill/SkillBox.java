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

import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ExtendedModel;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理一组 {@link AgentSkill} 实例并将其作为工具暴露。
 *
 * 自 2.0.0 起，SkillBox 仅供内部使用；定义和加载技能时应优先使用技能仓库（{@code AgentSkillRepository}）。
 */
public class SkillBox {
    private static final Logger logger = LoggerFactory.getLogger(SkillBox.class);
    private static final String BASE64_PREFIX = "base64:";

    private final SkillRegistry skillRegistry = new SkillRegistry();
    private final AgentSkillPromptProvider skillPromptProvider;
    private final SkillToolFactory skillToolFactory;
    private volatile Toolkit toolkit;
    private Path workDir;
    private Path uploadDir;
    private SkillFileFilter fileFilter;
    private boolean autoUploadSkill = true;

    /**
     * 保护并发 {@code uploadSkillFiles} 写入的分段锁数量。固定大小的锁表取代原先按路径保存锁的映射，避免长生命周期进程为每个物化路径永久积累锁对象。同一路径始终映射到同一锁段，因此互斥性不依赖此数值；冲突只会让额外的文件写入串行化。选择 256 可将极端物化突发下的最坏锁段占用控制在一个等待写入者以下：数字密集路径族的 31 多项式哈希在较小的二次幂模数下更易聚集。这也为计划采用临时文件加重命名后更长的写临界区预留空间，锁表只需数 KB。
     */
    static final int FILE_LOCK_STRIPES = 256;

    private static final Object[] FILE_LOCK_TABLE = createFileLockTable();

    private static Object[] createFileLockTable() {
        Object[] table = new Object[FILE_LOCK_STRIPES];
        for (int i = 0; i < FILE_LOCK_STRIPES; i++) {
            table[i] = new Object();
        }
        return table;
    }

    /**
     * 返回保护给定目标路径写入的锁段。索引为 {@code floorMod(pathHash, FILE_LOCK_STRIPES)}，同一路径始终映射到相同锁段，保持该路径的互斥性；无论写入多少不同路径，锁表大小都固定。无关路径碰撞到同一锁段时仅会依次执行，不影响正确性。
     *
     * <p>调用方不得同时持有多个锁段：获取锁、完成写入、释放后才能锁定另一条路径。以不同顺序嵌套获取锁可能死锁，无关路径的锁段碰撞会扩大风险。
     *
     * @param path 正在写入的目标文件路径
     * @return 此路径用于 {@code synchronized} 的锁段对象
     */
    static Object fileLockFor(Path path) {
        return FILE_LOCK_TABLE[Math.floorMod(path.hashCode(), FILE_LOCK_STRIPES)];
    }

    public SkillBox(Toolkit toolkit) {
        this(toolkit, null);
    }

    /**
     * 使用工具包和自定义技能提示指令创建 SkillBox。
     *
     * @param toolkit 要绑定的工具包
     * @param instruction 自定义指令头（null 或空白使用默认值）
     */
    public SkillBox(Toolkit toolkit, String instruction) {
        this.skillPromptProvider = new AgentSkillPromptProvider(skillRegistry, instruction);
        this.skillToolFactory = new SkillToolFactory(skillRegistry, toolkit);
        this.toolkit = toolkit;
    }

    private SkillBox(SkillBox source, Toolkit toolkit) {
        for (String id : source.getAllSkillIds()) {
            skillRegistry.registerSkill(id, source.getSkill(id));
        }
        skillPromptProvider = source.skillPromptProvider.copyFor(skillRegistry);
        skillToolFactory = new SkillToolFactory(skillRegistry, toolkit);
        this.toolkit = toolkit;
        workDir = source.workDir;
        uploadDir = source.uploadDir;
        fileFilter = source.fileFilter;
        autoUploadSkill = source.autoUploadSkill;
    }

    /**
     * 创建属于智能体自身、绑定到给定工具包的注册表、提示提供者和加载器。
     * 已注册的技能值及调用方拥有的资源目录仍然共享。
     *
     * @param toolkit 智能体的工具包
     * @return 独立的技能 box
     */
    public SkillBox copyForToolkit(Toolkit toolkit) {
        return new SkillBox(this, java.util.Objects.requireNonNull(toolkit, "toolkit"));
    }

    /**
     * 获取已注册技能的系统提示。
     *
     * <p>提示提供可用技能的信息，智能体可在执行过程中动态加载和使用这些技能。
     *
     * @return 技能系统提示；无技能时为空字符串
     */
    public String getSkillPrompt() {
        return skillPromptProvider.getSkillSystemPrompt();
    }

    /**
     * 获取经给定 {@link SkillFilter} 过滤后的技能系统提示。
     *
     * @param filter 决定包含哪些技能的过滤器（null 视为全部）
     * @return 技能系统提示；无技能通过过滤时为空字符串
     */
    public String getSkillPrompt(SkillFilter filter) {
        return skillPromptProvider.getSkillSystemPrompt(filter);
    }

    /**
     * 控制技能提示暴露全部元数据字段，还是仅暴露核心字段。
     *
     * <p>禁用时，技能提示仅包含 {@code name}、{@code description} 和 {@code skill-id}。
     *
     * @param exposeAllMetadata {@code true} 暴露全部元数据，{@code false} 仅暴露核心字段
     */
    public void setExposeAllSkillMetadata(boolean exposeAllMetadata) {
        skillPromptProvider.setExposeAllMetadata(exposeAllMetadata);
    }

    /**
     * 创建用于注册技能并提供可选配置的流式构建器。
     *
     * <p>用法示例：
     * <pre>{@code
     * // Register skill
     * skillBox.registration()
     *     .skill(skill)
     *     .apply();
     *
     * // Register skill with tool
     * skillBox.registration()
     *     .skill(skill) // same reference skill will not be registered again
     *     .tool(toolObject)
     *     .apply();
     * }</pre>
     *
     * @return 新的 ToolRegistration 构建器
     */
    public SkillRegistration registration() {
        return new SkillRegistration(this);
    }

    /**
     * 将共享工具包绑定到此技能 box 及其内部技能工具工厂。
     *
     * @param toolkit 要绑定的工具包
     * @throws IllegalArgumentException 当工具包为 null 时
     */
    public void bindToolkit(Toolkit toolkit) {
        if (toolkit == null) {
            throw new IllegalArgumentException("Toolkit cannot be null");
        }
        this.toolkit = toolkit;
        this.skillToolFactory.bindToolkit(toolkit);
    }

    // ==================== 技能管理 ====================

    /**
     * 注册智能体技能。
     *
     * <p>智能体可使用技能访问工具动态加载技能。加载技能会返回其内容；工具分组的激活反映在会话级激活分组状态中，而不保存在此 box 上。
     *
     * <p><b>注册与覆盖：</b>
     * <ul>
     * <li>首次注册：按技能 ID 保存技能。</li>
     * <li>再次注册相同技能对象：同 ID 仍指向该对象。</li>
     * <li>注册同 ID 的不同技能对象：覆盖旧引用；此处不保存历史版本快照。</li>
     * </ul>
     *
     * <p><b>用法示例：</b>
     * <pre>{@code
     * AgentSkill mySkill = new AgentSkill("my-skill", "Description", "Content", null);
     *
     * skillBox.registerSkill(mySkill);
     * skillBox.registerSkill(mySkill); // do nothing
     * }</pre>
     *
     * @param skill 要注册的智能体技能
     * @throws IllegalArgumentException 当 skill 为 null 时
     */
    public void registerSkill(AgentSkill skill) {
        if (skill == null) {
            throw new IllegalArgumentException("AgentSkill cannot be null");
        }

        String skillId = skill.getSkillId();
        skillRegistry.registerSkill(skillId, skill);
        logger.info("Registered skill '{}'", skillId);
    }

    /**
     * 获取全部技能 ID。
     * @return 全部技能 ID
     */
    public Set<String> getAllSkillIds() {
        return skillRegistry.getSkillIds();
    }

    /**
     * 加载给定工具调用参数标识的技能资源。这是共享 {@code load_skill_through_path} 工具使用的包级入口：技能激活写入会话级工具上下文，绝不写入共享分组管理器。
     *
     * @param param 携带 {@code skillId} 和 {@code path} 的工具调用参数
     * @return 格式化资源内容，或描述失败的消息
     */
    String loadSkillResource(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String skillId = (String) input.get("skillId");
        if (skillId == null || skillId.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing or empty required parameter: skillId");
        }
        String path = (String) input.get("path");
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing or empty required parameter: path");
        }
        return skillToolFactory.loadSkillResourceImpl(
                skillId, path, skillToolFactory.resolveToolContext(param));
    }

    /**
     * 按 ID 获取技能（最新版本）。
     *
     * @param skillId 技能 ID
     * @return 技能实例；未找到时为 null
     * @throws IllegalArgumentException 当 skillId 为 null 时
     */
    public AgentSkill getSkill(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        return skillRegistry.getSkill(skillId);
    }

    /**
     * 完全移除技能。
     *
     * @param skillId 技能 ID
     * @throws IllegalArgumentException 当 skillId 为 null 时
     */
    public void removeSkill(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        skillRegistry.removeSkill(skillId);
        logger.info("Removed skill '{}'", skillId);
    }

    /**
     * 检查技能是否存在。
     *
     * @param skillId 技能 ID
     * @return 存在时为 true，否则为 false
     * @throws IllegalArgumentException 当 skillId 为 null 时
     */
    public boolean exists(String skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be null");
        }
        return skillRegistry.exists(skillId);
    }

    /**
     * 用于注册技能并提供可选配置的流式构建器。
     *
     * <p>以清晰、类型安全的方式组合各项注册选项，避免方法数量膨胀。
     */
    public static class SkillRegistration {
        private final SkillBox skillBox;
        private Toolkit toolkit;
        private AgentSkill skill;
        private Object toolObject;
        private AgentTool agentTool;
        private McpClientWrapper mcpClientWrapper;
        private SubAgentProvider<?> subAgentProvider;
        private SubAgentConfig subAgentConfig;
        private Map<String, Map<String, Object>> presetParameters;
        private ExtendedModel extendedModel;
        private List<String> enableTools;
        private List<String> disableTools;

        public SkillRegistration(SkillBox skillBox) {
            this.skillBox = skillBox;
        }

        /**
         * 设置要注册的技能。
         *
         * @param skill 要注册的技能
         * @return 当前构建器，支持链式调用
         */
        public SkillRegistration skill(AgentSkill skill) {
            this.skill = skill;
            return this;
        }

        public SkillRegistration toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * 设置要注册的工具对象（扫描 @Tool 方法）。
         *
         * @param toolObject 包含 @Tool 注解方法的对象
         * @return 当前构建器，支持链式调用
         */
        public SkillRegistration tool(Object toolObject) {
            this.toolObject = toolObject;
            return this;
        }

        /**
         * 设置要注册的 AgentTool 实例。
         *
         * @param agentTool AgentTool 实例
         * @return 当前构建器，支持链式调用
         */
        public SkillRegistration agentTool(AgentTool agentTool) {
            this.agentTool = agentTool;
            return this;
        }

        /**
         * 设置要注册的 MCP 客户端。
         *
         * @param mcpClientWrapper MCP 客户端包装器
         * @return 当前构建器，支持链式调用
         */
        public SkillRegistration mcpClient(McpClientWrapper mcpClientWrapper) {
            this.mcpClientWrapper = mcpClientWrapper;
            return this;
        }

        /**
         * 使用默认配置将子智能体注册为工具。
         *
         * <p>工具名称和描述从智能体属性推导。实际参数为必填 message 和可选 session_id，与自定义配置重载一致。
         *
         * <p>示例：
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(() -> ReActAgent.builder()
         *         .name("ResearchAgent")
         *         .model(model)
         *         .build())
         *     .apply();
         * }</pre>
         *
         * @param provider 创建智能体实例的工厂（每次调用都会执行）
         * @return 当前构建器，支持链式调用
         */
        public SkillRegistration subAgent(SubAgentProvider<?> provider) {
            return subAgent(provider, null);
        }

        /**
         * 使用自定义配置将子智能体注册为工具。
         *
         * <p>子智能体通过会话状态管理支持多轮对话。工具暴露两个参数：{@code message}（必填）和 {@code session_id}（可选，用于继续已有对话）。
         *
         * <p>自定义工具名称和描述的示例：
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(
         *         () -> ReActAgent.builder().name("Expert").model(model).build(),
         *         SubAgentConfig.builder()
         *             .toolName("ask_expert")
         *             .description("Ask the domain expert a question")
         *             .build())
         *     .apply();
         * }</pre>
         *
         * <p>使用持久化会话支持跨进程对话的示例：
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(
         *         () -> ReActAgent.builder().name("Assistant").model(model).build(),
         *         SubAgentConfig.builder()
         *             .stateStore(new JsonFileAgentStateStore(Path.of("sessions")))
         *             .forwardEvents(true)
         *             .build())
         *     .apply();
         * }</pre>
         *
         * @param provider 创建智能体实例的工厂（每次调用都会执行）
         * @param config 子智能体工具配置；null 使用默认值（从智能体名称推导工具名，使用 InMemoryAgentStateStore 保存状态，并转发事件）
         * @return 当前构建器，支持链式调用
         * @see SubAgentConfig
         * @see SubAgentConfig#defaults()
         */
        public SkillRegistration subAgent(SubAgentProvider<?> provider, SubAgentConfig config) {
            if (this.toolObject != null
                    || this.agentTool != null
                    || this.mcpClientWrapper != null) {
                throw new IllegalStateException(
                        "Cannot set multiple registration types. Use only one of: tool(),"
                                + " agentTool(), mcpClient(), or subAgent().");
            }
            this.subAgentProvider = provider;
            this.subAgentConfig = config;
            return this;
        }

        /**
         * 设置从 MCP 客户端启用的工具列表。
         *
         * <p>仅在使用 mcpClient() 时适用。未指定时启用全部工具。
         *
         * @param enableTools 要启用的工具名称列表
         * @return 当前构建器，支持链式调用
         */
        public SkillRegistration enableTools(List<String> enableTools) {
            this.enableTools = enableTools;
            return this;
        }

        /**
         * 设置从 MCP 客户端禁用的工具列表。
         *
         * <p>仅在使用 mcpClient() 时适用。
         *
         * @param disableTools 要禁用的工具名称列表
         * @return 当前构建器，支持链式调用
         */
        public SkillRegistration disableTools(List<String> disableTools) {
            this.disableTools = disableTools;
            return this;
        }

        /**
         * 设置工具执行时自动注入的预设参数。
         *
         * <p>这些参数不在 JSON 模式中暴露。
         *
         * <p>映射以工具名称为键、参数映射为值：
         * <pre>{@code
         * Map.of(
         *     "toolName1", Map.of("param1", "value1", "param2", "value2"),
         *     "toolName2", Map.of("param1", "value3")
         * )
         * }</pre>
         *
         * @param presetParameters 工具名称到预设参数的映射
         * @return 当前构建器，支持链式调用
         */
        public SkillRegistration presetParameters(
                Map<String, Map<String, Object>> presetParameters) {
            this.presetParameters = presetParameters;
            return this;
        }

        /**
         * 设置用于动态模式扩展的扩展模型。
         *
         * @param extendedModel 扩展模型
         * @return 当前构建器，支持链式调用
         */
        public SkillRegistration extendedModel(ExtendedModel extendedModel) {
            this.extendedModel = extendedModel;
            return this;
        }

        /**
         * 使用全部已配置选项执行注册。
         *
         * @throws IllegalStateException 当未设置 skill()，或需要 toolkit() 但未设置时
         */
        public void apply() {
            if (skill == null) {
                throw new IllegalStateException("Must call skill() before apply()");
            }
            skillBox.registerSkill(skill);

            if (toolObject != null
                    || agentTool != null
                    || mcpClientWrapper != null
                    || subAgentProvider != null) {
                if (toolkit == null && (toolkit = skillBox.toolkit) == null) {
                    throw new IllegalStateException(
                            "Must bind toolkit or call toolkit() before apply()");
                }
                String skillToolGroup = skill.getSkillId() + "_skill_tools";
                if (toolkit.getToolGroup(skillToolGroup) == null) {
                    toolkit.createToolGroup(skillToolGroup, skillToolGroup, false);
                }
                toolkit.registration()
                        .group(skillToolGroup)
                        .presetParameters(presetParameters)
                        .extendedModel(extendedModel)
                        .enableTools(enableTools)
                        .disableTools(disableTools)
                        .agentTool(agentTool)
                        .tool(toolObject)
                        .mcpClient(mcpClientWrapper)
                        .subAgent(subAgentProvider, subAgentConfig)
                        .apply();
            }
        }
    }

    // ==================== 技能内置工具 ====================

    /**
     * 向给定工具包注册技能访问工具。
     *
     * <p>注册以下工具：
     * <ul>
     * <li>load_skill_through_path：加载技能资源或 SKILL.md 内容。未找到资源时，自动返回以 SKILL.md 为首项的可用资源列表。</li>
     * </ul>
     *
     * @throws IllegalArgumentException 当 toolkit 为 null 时
     */
    public void registerSkillLoadTool() {
        if (toolkit == null) {
            throw new IllegalArgumentException("Toolkit cannot be null");
        }

        if (toolkit.getTool(SkillToolFactory.LOAD_TOOL_NAME) != null) {
            // 已由其他路径注册（例如动态技能中间件），避免
            // 静默覆盖。不支持混用静态 skillBox() 和动态 skillRepositories()，
            // 因此明确报告以便诊断。
            logger.warn(
                    "load_skill_through_path already registered; skipping (a same-named tool is"
                            + " already present on this toolkit)");
            return;
        }

        // 注册为无分组工具，使其始终可见、可调用，且不会被
        // META 范围的 reset_equipped_tools 替换操作移除。
        toolkit.registration().agentTool(skillToolFactory.createSkillAccessToolAgentTool()).apply();

        logger.info("Registered skill load tools to toolkit");
    }

    /**
     * 设置是否自动上传技能文件。
     *
     * @param autoUploadSkill true 表示自动上传技能文件
     */
    public void setAutoUploadSkill(boolean autoUploadSkill) {
        this.autoUploadSkill = autoUploadSkill;
    }

    /**
     * 检查是否自动上传技能文件。
     *
     * @return 自动上传时为 true
     */
    public boolean isAutoUploadSkill() {
        return autoUploadSkill;
    }

    /**
     * 获取代码执行的工作目录。
     *
     * @return 工作目录路径；使用临时目录时可为 null
     */
    public Path getCodeExecutionWorkDir() {
        return workDir;
    }

    /**
     * 为资源上传和代码执行设置稳定工作目录。调用方可让多个 SkillBox 实例共享一个 workDir（例如 {@code DynamicSkillMiddleware} 每次调用都会重建 box，但 workDir 在智能体生命周期内保持稳定，因此 {@code uploadSkillFiles} 不再每轮生成新的 {@code agentscope-code-execution-*} 临时目录）。
     *
     * @param workDir 用于写入 {@code skills/<skillId>/} 子树的目录；{@code null}（默认）保留旧的按实例创建临时目录行为
     */
    public void setWorkDir(Path workDir) {
        this.workDir = workDir;
        // uploadDir 在 ensureUploadDirExists() 中从 workDir 延迟推导；清空
        // 缓存子目录，让下次 ensureUploadDirExists() 根据新的 workDir 重新计算。
        this.uploadDir = null;
    }

    /**
     * 暴露技能提示提供者，使调用方（例如 {@code DynamicSkillMiddleware}）无需深入 SkillBox 内部，即可切换 {@code codeExecutionEnable}、替换指令模板或查询解析后的 uploadDir。
     */
    public AgentSkillPromptProvider getSkillPromptProvider() {
        return skillPromptProvider;
    }

    /**
     * 获取技能文件的上传目录。
     *
     * @return 上传目录路径；未配置时为 null
     */
    public Path getUploadDir() {
        return uploadDir;
    }

    /**
     * 确保工作目录存在，必要时创建。
     *
     * @return 工作目录路径
     * @throws RuntimeException 当目录创建失败时
     */
    private Path ensureWorkDirExists() {
        if (this.workDir == null) {
            // 创建临时目录。
            try {
                this.workDir = Files.createTempDirectory("agentscope-code-execution-");

                SkillFileSystemHelper.registerTempDirectoryCleanup(workDir);

                logger.info("Created temporary working directory: {}", workDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create temporary working directory", e);
            }
        } else {
            // 目录不存在时创建。
            if (!Files.exists(workDir)) {
                try {
                    Files.createDirectories(workDir);
                    logger.info("Created working directory: {}", workDir);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create working directory", e);
                }
            }
        }

        return this.workDir;
    }

    /**
     * 确保上传目录存在，必要时创建。
     *
     * @return 上传目录路径
     */
    private Path ensureUploadDirExists() {
        if (uploadDir == null) {
            Path resolvedWorkDir = ensureWorkDirExists();
            uploadDir = resolvedWorkDir.resolve("skills");
        }

        if (!Files.exists(uploadDir)) {
            try {
                Files.createDirectories(uploadDir);
                logger.info("Created upload directory: {}", uploadDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create upload directory", e);
            }
        }

        skillPromptProvider.setUploadDir(uploadDir);
        return uploadDir;
    }

    /**
     * 按配置的过滤器将技能文件上传到上传目录。
     *
     * <p>上传目录的解析规则：
     * <ul>
     * <li>已配置 uploadDir 时直接使用。</li>
     * <li>否则使用 workDir/skills（workDir 可能是临时目录）。</li>
     * </ul>
     *
     * <p>安全检查：技能目录（由技能 ID 推导，其中来源部分可自由填写）及每条资源路径都必须通过 {@link SkillUtil#resolveContainedPath(Path, String)}。绝对路径或含路径穿越的片段可能逃出上传目录，因此会跳过并记录警告。技能名称本身已在构造时校验。
     *
     * <p>文件已存在时会被覆盖。
     */
    public void uploadSkillFiles() {
        Path targetDir = ensureUploadDirExists();
        SkillFileFilter filter = fileFilter != null ? fileFilter : SkillFileFilter.acceptAll();
        int fileCount = 0;

        for (String skillId : getAllSkillIds()) {
            AgentSkill skill = getSkill(skillId);

            // 设置了 originDir 的技能已位于磁盘上的已知绝对路径，
            // 提示提供者将其作为 <files-root> 暴露。再物化到
            // workDir 只会产生 LLM 不会引用的冗余副本。
            if (skill.getOriginDir().isPresent()) {
                continue;
            }

            Set<String> resourcePaths = skill.getResourcePaths();

            if (resourcePaths.isEmpty()) {
                continue;
            }

            Optional<Path> skillDir = SkillUtil.resolveContainedPath(targetDir, skillId);
            if (skillDir.isEmpty()) {
                logger.warn(
                        "Refusing to materialize skill '{}': resolved directory escapes the"
                                + " target directory '{}'",
                        skillId,
                        targetDir);
                continue;
            }

            for (String resourcePath : resourcePaths) {
                if (!filter.accept(resourcePath)) {
                    continue;
                }

                String content = skill.getResource(resourcePath);
                if (content == null) {
                    logger.warn("Resource not found: {} in skill {}", resourcePath, skillId);
                    continue;
                }

                Optional<Path> containedPath =
                        SkillUtil.resolveContainedPath(skillDir.get(), resourcePath);
                if (containedPath.isEmpty()) {
                    logger.warn("Skipping file with invalid path: {}", resourcePath);
                    continue;
                }
                Path targetPath = containedPath.get();

                try {
                    if (targetPath.getParent() != null) {
                        Files.createDirectories(targetPath.getParent());
                    }

                    Object lock = fileLockFor(targetPath);
                    synchronized (lock) {
                        if (content.startsWith(BASE64_PREFIX)) {
                            String encoded = content.substring(BASE64_PREFIX.length());
                            byte[] decoded = Base64.getDecoder().decode(encoded);
                            Files.write(targetPath, decoded);
                        } else {
                            Files.writeString(targetPath, content, StandardCharsets.UTF_8);
                        }
                    }

                    logger.debug("Uploaded file: {}", targetPath);
                    fileCount++;
                } catch (IOException | IllegalArgumentException e) {
                    logger.error("Failed to upload file {}: {}", resourcePath, e.getMessage());
                }
            }
        }

        logger.info("Uploaded {} skill files to: {}", fileCount, targetDir);
    }

    private static class DefaultSkillFileFilter implements SkillFileFilter {
        private final Set<String> includeFolders;
        private final Set<String> includeExtensions;

        private DefaultSkillFileFilter(Set<String> includeFolders, Set<String> includeExtensions) {
            this.includeFolders = includeFolders != null ? includeFolders : Set.of();
            this.includeExtensions = includeExtensions != null ? includeExtensions : Set.of();
        }

        @Override
        public boolean accept(String resourcePath) {
            if (resourcePath == null || resourcePath.isBlank()) {
                return false;
            }

            String normalizedPath = resourcePath.replace("\\", "/");

            if (!includeFolders.isEmpty()) {
                for (String folder : includeFolders) {
                    if (normalizedPath.startsWith(folder)) {
                        return true;
                    }
                }
            }

            if (!includeExtensions.isEmpty()) {
                for (String extension : includeExtensions) {
                    if (normalizedPath.endsWith(extension)) {
                        return true;
                    }
                }
            }

            return false;
        }
    }
}
