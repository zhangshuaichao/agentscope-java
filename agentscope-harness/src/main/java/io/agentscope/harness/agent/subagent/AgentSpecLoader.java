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
package io.agentscope.harness.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从工作区 {@code subagents/} 目录中带 YAML 前置元数据的 Markdown 文件
 * 加载 {@link SubagentDeclaration} 实例。
 *
 * <p><strong>文件命名</strong>：文件名（不含 {@code .md} 扩展名）成为子智能体的
 * {@code name}／智能体 ID。前置元数据不得包含 {@code name} 字段。
 *
 * <p><strong>扫描策略</strong>：只扫描指定目录的<em>直接</em>子项（非递归），
 * 避免意外加载碰巧存放在同一父目录下的定义工作区内部文件。
 *
 * <p>文件格式：
 *
 * <pre>
 * ---
 * description: Reviews code for security and performance issues.
 * workspace:
 *   mode: isolated          # isolated（隔离）或 shared（共享），默认 isolated
 *   path: ./defs/reviewer   # 可选，相对 mainWorkspace 或绝对路径
 * model: qwen3-max          # 可选模型覆盖值
 * maxIters: 12              # 可选，默认 10
 * tools: [read_file, grep_files, edit_file]   # 可选允许列表
 * compaction:              # 缺省/null 继承，false 禁用，true 默认配置
 *   triggerMessages: 20
 *   keepMessages: 5
 *   keepTokens: 0         # 使用按消息数量保留的窗口
 * ---
 *
 * # 内联正文，仅 workspace.path 缺省时使用
 * You are a code reviewer...
 * </pre>
 *
 * <p>YAML 示例中文说明：description 描述代码安全与性能审查职责；workspace.mode 可选 isolated（隔离，默认）或 shared（共享）；path 是可选定义目录，可相对主工作区或为绝对路径。model 覆盖模型，maxIters 为可选最大迭代数（示例为 12，默认 10），tools 是允许工具列表。compaction 缺省或 null 表示继承，false 禁用，true 使用默认值；triggerMessages=20 表示触发消息数，keepMessages=5 保留尾部消息，keepTokens=0 使用消息数量窗口。仅当 workspace.path 缺省时读取下方内联正文，其示例意思是“你是一名代码审查员”。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code description} 为必填项。
 *   <li>存在 {@code workspace.path} 时，Markdown 正文必须为空白；运行时从
 *       {@code &lt;workspace.path&gt;/AGENTS.md} 读取子智能体系统提示。
 *   <li>不存在 {@code workspace.path} 时，Markdown 正文（若有）成为内联
 *       {@link SubagentDeclaration#getInlineAgentsBody()}。
 * </ul>
 */
public final class AgentSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentSpecLoader.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private AgentSpecLoader() {}

    /**
     * 非递归扫描 {@code subagentsDir} 中的 {@code .md} 文件，并逐个解析为
     * {@link SubagentDeclaration}。
     *
     * @param subagentsDir 待扫描的 {@code subagents/} 目录
     * @param mainWorkspace 用于解析相对 {@code workspace.path} 值的父工作区；
     *     可以为 {@code null}（相对路径将保持为相对形式）
     * @return 已解析的声明列表；绝不为 {@code null}
     */
    public static List<SubagentDeclaration> loadFromDirectory(
            Path subagentsDir, Path mainWorkspace) {
        if (subagentsDir == null || !Files.isDirectory(subagentsDir)) {
            return Collections.emptyList();
        }
        List<SubagentDeclaration> decls = new ArrayList<>();
        try (Stream<Path> paths = Files.list(subagentsDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(
                            path -> {
                                try {
                                    SubagentDeclaration decl = loadFromFile(path, mainWorkspace);
                                    if (decl != null) {
                                        decls.add(decl);
                                        log.debug(
                                                "Loaded subagent declaration '{}' from {}",
                                                decl.getName(),
                                                path);
                                    }
                                } catch (Exception e) {
                                    log.warn(
                                            "Failed to load subagent declaration from {}: {}",
                                            path,
                                            e.getMessage());
                                }
                            });
        } catch (IOException e) {
            log.warn("Failed to list subagents directory {}: {}", subagentsDir, e.getMessage());
        }
        return decls;
    }

    /**
     * 通过 {@link AbstractFilesystem} 使用空运行时上下文加载子智能体声明。
     * 使用文件系统 glob 扫描 {@code subagents/} 中的 {@code *.md} 文件。
     *
     * <p>声明按调用用户命名空间隔离时，请使用
     * {@link #loadFromFilesystem(AbstractFilesystem, RuntimeContext, Path)}。
     *
     * @param filesystem 文件系统层（透明应用命名空间）
     * @param mainWorkspace 用于解析相对工作区路径的父工作区；可以为 {@code null}
     * @return 已解析的声明列表；绝不为 {@code null}
     */
    public static List<SubagentDeclaration> loadFromFilesystem(
            AbstractFilesystem filesystem, Path mainWorkspace) {
        return loadFromFilesystem(filesystem, RuntimeContext.empty(), mainWorkspace);
    }

    /**
     * 通过 {@link AbstractFilesystem} 加载子智能体声明，遵循所提供
     * 运行时上下文的命名空间隔离。
     *
     * @param filesystem 文件系统层（透明应用命名空间）
     * @param runtimeContext 用于解析命名空间内声明的调用上下文
     * @param mainWorkspace 用于解析相对工作区路径的父工作区；可以为 {@code null}
     * @return 已解析的声明列表；绝不为 {@code null}
     */
    public static List<SubagentDeclaration> loadFromFilesystem(
            AbstractFilesystem filesystem, RuntimeContext runtimeContext, Path mainWorkspace) {
        if (filesystem == null) {
            return Collections.emptyList();
        }
        RuntimeContext ctx = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        GlobResult glob = filesystem.glob(ctx, "*.md", "subagents");
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return Collections.emptyList();
        }

        List<SubagentDeclaration> decls = new ArrayList<>();
        List<FileInfo> sorted = new ArrayList<>(glob.matches());
        sorted.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.path(), b.path()));

        for (FileInfo fi : sorted) {
            String path = fi.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                ReadResult rr = filesystem.read(ctx, path, 0, 0);
                if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                    continue;
                }
                String filename =
                        path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                String name = stripMdExtension(filename);
                SubagentDeclaration decl = parse(rr.fileData().content(), name, mainWorkspace);
                if (decl != null) {
                    decls.add(decl);
                    log.debug("Loaded subagent declaration '{}' from filesystem: {}", name, path);
                }
            } catch (Exception e) {
                log.warn("Failed to load subagent declaration from {}: {}", path, e.getMessage());
            }
        }
        return decls;
    }

    /**
     * 解析单个 Markdown 声明文件。
     *
     * @param filePath 待解析的 {@code .md} 文件
     * @param mainWorkspace 用于解析相对 {@code workspace.path} 值的工作区根目录；
     *     可以为 {@code null}
     * @return 已解析的声明；文件格式错误或缺少必填字段时返回 {@code null}
     */
    public static SubagentDeclaration loadFromFile(Path filePath, Path mainWorkspace)
            throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        String nameFromFile = stripMdExtension(filePath.getFileName().toString());
        return parse(content, nameFromFile, mainWorkspace);
    }

    /**
     * 将带 YAML 前置元数据的 Markdown 内容解析为 {@link SubagentDeclaration}。
     *
     * @param markdown 完整文件内容
     * @param name 子智能体名称（由文件名派生，不含 {@code .md}）
     * @param mainWorkspace 用于解析相对 {@code workspace.path} 值的工作区根目录；
     *     可以为 {@code null}
     * @return 已解析的声明；内容格式错误时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public static SubagentDeclaration parse(String markdown, String name, Path mainWorkspace) {
        String content =
                markdown != null && markdown.startsWith("\uFEFF")
                        ? markdown.substring(1)
                        : markdown;
        if (content == null || content.isBlank() || !content.startsWith("---")) {
            return null;
        }
        int endIdx = content.indexOf("---", 3);
        if (endIdx == -1) {
            log.warn("Agent declaration front matter not closed with --- in '{}'", name);
            return null;
        }

        String frontMatterStr = content.substring(3, endIdx).trim();
        String body = content.substring(endIdx + 3).trim();

        Map<String, Object> fm;
        try {
            fm = YAML_MAPPER.readValue(frontMatterStr, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse YAML front matter for '{}': {}", name, e.getMessage());
            return null;
        }
        if (fm == null || fm.isEmpty()) {
            return null;
        }

        String description = asString(fm.get("description"));
        if (description == null || description.isBlank()) {
            log.warn(
                    "Subagent declaration '{}' missing required 'description' in front matter",
                    name);
            return null;
        }

        // ---- workspace 部分 ----
        WorkspaceMode mode = WorkspaceMode.ISOLATED;
        Path workspacePath = null;

        Object workspaceObj = fm.get("workspace");
        if (workspaceObj instanceof Map<?, ?> wsMap) {
            String modeStr = asString(wsMap.get("mode"));
            if (modeStr != null) {
                if ("shared".equalsIgnoreCase(modeStr)) {
                    mode = WorkspaceMode.SHARED;
                } else if ("isolated".equalsIgnoreCase(modeStr)) {
                    mode = WorkspaceMode.ISOLATED;
                } else {
                    log.warn(
                            "Unknown workspace.mode '{}' in declaration '{}', defaulting to"
                                    + " isolated",
                            modeStr,
                            name);
                }
            }
            String pathStr = asString(wsMap.get("path"));
            if (pathStr != null && !pathStr.isBlank()) {
                workspacePath = resolvePath(pathStr, mainWorkspace);
            }
        }

        // ---- 正文／内联系统提示验证 ----
        if (workspacePath != null && !body.isBlank()) {
            log.warn(
                    "Subagent declaration '{}' has both workspace.path and a non-empty body;"
                            + " body will be ignored — put the system prompt in"
                            + " {}/AGENTS.md instead",
                    name,
                    workspacePath);
            body = "";
        }

        // ---- 可选字段 ----
        String model = asString(fm.get("model"));

        // steps（优先）> maxIters（已弃用别名）。两者通过构建器默认设为 10。
        int steps = 10;
        Object stepsObj = fm.get("steps");
        if (stepsObj instanceof Number sn) {
            steps = sn.intValue();
        } else {
            Object maxItersObj = fm.get("maxIters");
            if (maxItersObj instanceof Number n) {
                steps = n.intValue();
            }
        }

        Double temperature = asDouble(fm.get("temperature"));
        Double topP = asDouble(fm.get("top_p"));
        if (topP == null) {
            topP = asDouble(fm.get("topP"));
        }
        String variant = asString(fm.get("variant"));

        SubagentDeclaration.Mode declMode = parseDeclarationMode(asString(fm.get("mode")), name);
        boolean hidden = asBoolean(fm.get("hidden"), false);
        Boolean exposeToUser = asNullableBoolean(fm.get("expose_to_user"));
        if (exposeToUser == null) {
            exposeToUser = asNullableBoolean(fm.get("exposeToUser"));
        }
        Boolean enablePendingToolRecovery =
                asNullableBoolean(fm.get("enable_pending_tool_recovery"));
        if (enablePendingToolRecovery == null) {
            enablePendingToolRecovery = asNullableBoolean(fm.get("enablePendingToolRecovery"));
        }

        List<String> tools = parseToolNames(asString(fm.get("tools")));
        List<String> skills = parseToolNames(asString(fm.get("skills")));

        SubagentDeclaration.Builder builder =
                SubagentDeclaration.builder()
                        .name(name)
                        .description(description)
                        .workspaceMode(mode)
                        .model(model)
                        .steps(steps)
                        .temperature(temperature)
                        .topP(topP)
                        .variant(variant)
                        .mode(declMode)
                        .hidden(hidden)
                        .exposeToUser(exposeToUser)
                        .enablePendingToolRecovery(enablePendingToolRecovery)
                        .tools(tools.isEmpty() ? null : tools)
                        .skills(skills.isEmpty() ? null : skills);

        try {
            applyCompaction(fm.get("compaction"), builder);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring invalid compaction config for '{}': {}", name, e.getMessage());
        }

        if (workspacePath != null) {
            builder.workspace(workspacePath);
        } else {
            builder.inlineAgentsBody(body.isEmpty() ? null : body);
        }

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------------------------

    private static void applyCompaction(Object value, SubagentDeclaration.Builder declaration) {
        if (value == null) {
            return;
        }
        if (Boolean.FALSE.equals(value)) {
            declaration.disableCompaction();
            return;
        }
        CompactionConfig.Builder config = CompactionConfig.builder();
        if (Boolean.TRUE.equals(value)) {
            declaration.compaction(config.build());
            return;
        }
        if (!(value instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException("compaction must be a boolean or mapping");
        }
        for (Map.Entry<?, ?> field : fields.entrySet()) {
            String key = String.valueOf(field.getKey());
            Object setting = field.getValue();
            switch (key) {
                case "triggerMessages" -> config.triggerMessages(compactionInt(key, setting));
                case "triggerTokens" -> config.triggerTokens(compactionInt(key, setting));
                case "reserved" -> config.reserved(compactionInt(key, setting));
                case "keepMessages" -> config.keepMessages(compactionInt(key, setting));
                case "keepTokens" -> config.keepTokens(compactionInt(key, setting));
                case "keepTokensMin" -> config.keepTokensMin(compactionInt(key, setting));
                case "keepTokensMax" -> config.keepTokensMax(compactionInt(key, setting));
                case "keepTokensRatio" -> {
                    if (!(setting instanceof Number number)
                            || !Double.isFinite(number.doubleValue())) {
                        throw new IllegalArgumentException(key + " must be a finite number");
                    }
                    if (number.doubleValue() <= 0 || number.doubleValue() > 1) {
                        throw new IllegalArgumentException(
                                key + " must be greater than 0 and at most 1");
                    }
                    config.keepTokensRatio(number.doubleValue());
                }
                case "summaryPrompt" -> {
                    if (!(setting instanceof String prompt)) {
                        throw new IllegalArgumentException(key + " must be a string");
                    }
                    if (!prompt.contains("{messages}")) {
                        throw new IllegalArgumentException(
                                key + " must contain the {messages} placeholder");
                    }
                    config.summaryPrompt(prompt);
                }
                case "flushBeforeCompact" -> {
                    if (!(setting instanceof Boolean enabled)) {
                        throw new IllegalArgumentException(key + " must be a boolean");
                    }
                    config.flushBeforeCompact(enabled);
                }
                default -> throw new IllegalArgumentException("Unknown compaction field: " + key);
            }
        }
        CompactionConfig built = config.build();
        if (built.getKeepTokensMin() > built.getKeepTokensMax()) {
            throw new IllegalArgumentException("keepTokensMin must not exceed keepTokensMax");
        }
        declaration.compaction(built);
    }

    private static int compactionInt(String key, Object value) {
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            int setting = number.intValue();
            int minimum = "keepTokens".equals(key) ? -1 : 0;
            if (setting < minimum) {
                throw new IllegalArgumentException(key + " must be at least " + minimum);
            }
            return setting;
        }
        throw new IllegalArgumentException(key + " must be a 32-bit integer");
    }

    private static String stripMdExtension(String filename) {
        if (filename.endsWith(".md")) {
            return filename.substring(0, filename.length() - 3);
        }
        return filename;
    }

    private static Path resolvePath(String pathStr, Path mainWorkspace) {
        Path p = Path.of(pathStr);
        if (p.isAbsolute()) {
            return p;
        }
        if (mainWorkspace != null) {
            Path resolved = mainWorkspace.resolve(p).normalize();
            return resolved;
        }
        return p;
    }

    private static String asString(Object v) {
        return v != null ? v.toString().trim() : null;
    }

    private static boolean asBoolean(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim();
        if (s.isEmpty()) return def;
        return Boolean.parseBoolean(s);
    }

    /**
     * 解析三态布尔值：键缺失或空白时返回 {@code null}（不指定），
     * 否则返回解析出的布尔值。用于区分“未设置”与显式 {@code false} 的前置元数据标志。
     */
    private static Boolean asNullableBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        return Boolean.parseBoolean(s);
    }

    private static SubagentDeclaration.Mode parseDeclarationMode(String s, String name) {
        if (s == null || s.isBlank()) return SubagentDeclaration.Mode.ALL;
        switch (s.toLowerCase()) {
            case "primary":
                return SubagentDeclaration.Mode.PRIMARY;
            case "subagent":
                return SubagentDeclaration.Mode.SUBAGENT;
            case "all":
                return SubagentDeclaration.Mode.ALL;
            default:
                log.warn(
                        "Unknown mode '{}' in subagent declaration '{}', defaulting to all",
                        s,
                        name);
                return SubagentDeclaration.Mode.ALL;
        }
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse numeric value '{}' — treating as unset", s);
            return null;
        }
    }

    private static List<String> parseToolNames(String toolsStr) {
        if (toolsStr == null || toolsStr.isBlank()) {
            return List.of();
        }
        // 同时支持逗号分隔字符串和 YAML 列表表示形式 "[a, b, c]"
        String cleaned = toolsStr.stripLeading();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return Stream.of(cleaned.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
