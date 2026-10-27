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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.curator.SkillAuditLog;
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 智能体可调用的工具，使 {@code ReActAgent} 能在其工作区中创建、编辑、
 * 修补和归档技能。移植自 hermes-agent 的 {@code skill_manage} 工具接口，
 * 使已有技能编写提示保持可移植性。
 *
 * <p>根据 {@code action} 参数分派六种操作：
 * <ul>
 *   <li>{@code create}：写入全新的 {@code SKILL.md}（必须包含前置元数据）</li>
 *   <li>{@code edit}：完整重写现有技能的 {@code SKILL.md}</li>
 *   <li>{@code patch}：在 {@code SKILL.md} 或辅助文件中定向查找并替换</li>
 *   <li>{@code write_file}：添加／覆盖 {@code references/} /
 *       {@code templates/} / {@code scripts/} / {@code assets/} 下的单个文件</li>
 *   <li>{@code remove_file}：删除单个辅助文件</li>
 *   <li>{@code delete}：归档整个技能目录（非破坏性）</li>
 * </ul>
 *
 * <p><b>暂存</b>：默认情况下（{@link SkillManageConfig#autoPromote()} = false），
 * {@code create} 写入 {@code skills/_drafts/<name>/}；
 * {@code edit}/{@code patch}/{@code write_file}/{@code remove_file}/{@code delete}
 * 按名称在草稿和主目录中解析技能（冲突时草稿优先）。
 * 设置 {@code autoPromote=true} 可直接写入 {@code skills/}。
 */
public class SkillManageTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SkillManageTool.class);

    public static final String NAME = "skill_manage";

    // 验证常量（对应 hermes tools/skill_manager_tool.py）；名称规则
    // 来自 core，使编写与构造过程拒绝相同的非法名称
    static final int MAX_NAME_LENGTH = SkillUtil.MAX_SKILL_NAME_LENGTH;
    static final int MAX_DESCRIPTION_LENGTH = 1024;
    static final int MAX_SKILL_CONTENT_CHARS = 100_000;
    static final int MAX_SKILL_FILE_BYTES = 1_048_576; // 1 MiB
    static final Pattern VALID_NAME_RE = SkillUtil.VALID_SKILL_NAME_PATTERN;
    static final Set<String> ALLOWED_SUBDIRS =
            Set.of("references", "templates", "scripts", "assets");

    /**
     * 指向正式技能根目录的仓库（例如 {@code skills/}）。
     */
    private final WorkspaceSkillRepository mainRepo;

    /**
     * 指向草稿暂存目录的仓库（例如 {@code skills/_drafts/}）。
     */
    private final WorkspaceSkillRepository draftsRepo;

    /**
     * 可选遥测附属组件；null 禁用来源与计数器写入。
     */
    private final SkillUsageStore usageStore;

    /**
     * 可选审计日志；null 禁用审计。
     */
    private final SkillAuditLog auditLog;

    private final SkillManageConfig config;

    public SkillManageTool(
            WorkspaceSkillRepository mainRepo,
            WorkspaceSkillRepository draftsRepo,
            SkillManageConfig config) {
        this(mainRepo, draftsRepo, config, null, null);
    }

    public SkillManageTool(
            WorkspaceSkillRepository mainRepo,
            WorkspaceSkillRepository draftsRepo,
            SkillManageConfig config,
            SkillUsageStore usageStore) {
        this(mainRepo, draftsRepo, config, usageStore, null);
    }

    public SkillManageTool(
            WorkspaceSkillRepository mainRepo,
            WorkspaceSkillRepository draftsRepo,
            SkillManageConfig config,
            SkillUsageStore usageStore,
            SkillAuditLog auditLog) {
        this.mainRepo = java.util.Objects.requireNonNull(mainRepo, "mainRepo");
        this.draftsRepo = java.util.Objects.requireNonNull(draftsRepo, "draftsRepo");
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.usageStore = usageStore;
        this.auditLog = auditLog;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Author, edit, patch, and archive procedural skills in the agent's workspace. "
                + "Use this when you discover a reusable approach to a task type that future "
                + "calls should be able to apply directly without rediscovery.\n\n"
                + "Six actions (pass via 'action' parameter):\n"
                + "  - create      : write a new SKILL.md (frontmatter must include name + "
                + "description)\n"
                + "  - edit        : full-rewrite the SKILL.md of an existing skill\n"
                + "  - patch       : exact-string find-and-replace in SKILL.md or a support file\n"
                + "  - write_file  : add/overwrite references/, templates/, scripts/, or "
                + "assets/ file\n"
                + "  - remove_file : delete a single support file\n"
                + "  - delete      : archive the entire skill directory (non-destructive, "
                + "moves to .archive/)\n\n"
                + (config.autoPromote()
                        ? "New skills become immediately visible on the next reasoning turn."
                        : "New skills land in skills/_drafts/ and are NOT visible until "
                                + "explicitly promoted. Use this freely — drafts cost nothing.");
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "action",
                Map.of(
                        "type", "string",
                        "enum",
                                List.of(
                                        "create",
                                        "edit",
                                        "patch",
                                        "write_file",
                                        "remove_file",
                                        "delete"),
                        "description", "Which sub-operation to perform."));
        properties.put(
                "name",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Skill name (lowercase letters/digits/dots/underscores/hyphens, "
                                + "max "
                                + MAX_NAME_LENGTH
                                + " chars)."));
        properties.put(
                "content",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Full SKILL.md content (frontmatter + body). Required for "
                                + "create/edit."));
        properties.put(
                "old_string",
                Map.of(
                        "type", "string",
                        "description", "Required for patch: the exact text to replace."));
        properties.put(
                "new_string",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Required for patch: the replacement text (empty string" + " deletes)."));
        properties.put(
                "replace_all",
                Map.of(
                        "type",
                        "boolean",
                        "description",
                        "If true, patch every occurrence; default false (must be" + " unique)."));
        properties.put(
                "file_path",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "For write_file/remove_file: path relative to skill root, must "
                                + "start with references/, templates/, scripts/, or "
                                + "assets/. For patch: optional target file (defaults "
                                + "to SKILL.md)."));
        properties.put(
                "file_content",
                Map.of(
                        "type", "string",
                        "description", "Required for write_file: the file body."));
        properties.put(
                "absorbed_into",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "For delete: name of the umbrella skill that absorbed this one, "
                                + "or empty string for pure pruning. Optional."));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action", "name"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext ctx = param.getRuntimeContext();
        return Mono.fromCallable(() -> dispatchSync(param.getInput(), ctx))
                .onErrorResume(
                        e -> {
                            log.warn("skill_manage failed: {}", e.getMessage(), e);
                            return Mono.just(
                                    ToolResultBlock.error(
                                            "skill_manage failed: " + e.getMessage()));
                        });
    }

    private ToolResultBlock dispatchSync(Map<String, Object> input, RuntimeContext ctx) {
        if (input == null) {
            return ToolResultBlock.error("Missing input parameters.");
        }
        String action = stringOf(input, "action");
        String name = stringOf(input, "name");
        if (action == null || action.isBlank()) {
            return ToolResultBlock.error("Missing required parameter: action");
        }
        if (name == null || name.isBlank()) {
            return ToolResultBlock.error("Missing required parameter: name");
        }
        String nameErr = validateName(name);
        if (nameErr != null) {
            return ToolResultBlock.error(nameErr);
        }
        switch (action) {
            case "create":
                return doCreate(name, stringOf(input, "content"), sessionIdOf(ctx), ctx);
            case "edit":
                return doEdit(name, stringOf(input, "content"), ctx);
            case "patch":
                return doPatch(
                        name,
                        stringOf(input, "old_string"),
                        stringOf(input, "new_string"),
                        stringOf(input, "file_path"),
                        boolOf(input, "replace_all"),
                        ctx);
            case "write_file":
                return doWriteFile(
                        name, stringOf(input, "file_path"), stringOf(input, "file_content"), ctx);
            case "remove_file":
                return doRemoveFile(name, stringOf(input, "file_path"), ctx);
            case "delete":
                return doDelete(name, stringOf(input, "absorbed_into"), ctx);
            default:
                return ToolResultBlock.error("Unknown action: " + action);
        }
    }

    private static String sessionIdOf(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getSessionId();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // 操作
    // ---------------------------------------------------------------------

    private ToolResultBlock doCreate(
            String name, String content, String sessionId, RuntimeContext ctx) {
        if (content == null || content.isBlank()) {
            return ToolResultBlock.error(
                    "Missing 'content' parameter (full SKILL.md including frontmatter).");
        }
        String contentErr = validateContent(content);
        if (contentErr != null) {
            return ToolResultBlock.error(contentErr);
        }
        // 任一仓库已存在同名技能时拒绝。
        if (mainRepo.skillExists(name, ctx) || draftsRepo.skillExists(name, ctx)) {
            return ToolResultBlock.error(
                    "A skill named '" + name + "' already exists. Use action=edit to update it.");
        }
        AgentSkill skill;
        try {
            skill = SkillUtil.createFrom(content, null, "agent");
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to parse frontmatter: " + e.getMessage());
        }
        if (skill == null || skill.getName() == null || !name.equals(skill.getName())) {
            return ToolResultBlock.error(
                    "The 'name' parameter must match the SKILL.md frontmatter 'name' field "
                            + "(got param='"
                            + name
                            + "', frontmatter='"
                            + (skill == null ? null : skill.getName())
                            + "').");
        }
        if (skill.getDescription() == null || skill.getDescription().isBlank()) {
            return ToolResultBlock.error(
                    "SKILL.md frontmatter must include a 'description' field.");
        }
        if (skill.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            return ToolResultBlock.error(
                    "Description exceeds " + MAX_DESCRIPTION_LENGTH + " chars.");
        }

        WorkspaceSkillRepository target = config.autoPromote() ? mainRepo : draftsRepo;
        boolean ok = target.save(List.of(skill), false, ctx);
        if (!ok) {
            return ToolResultBlock.error(
                    "Failed to write skill '" + name + "'. Check logs for details.");
        }
        // 写入后进行静态安全扫描；判定为 DANGEROUS 时回滚，供智能体修订。
        if (config.securityScan()) {
            SkillSecurityScanner.ScanResult scan =
                    SkillSecurityScanner.scan(name, content, skill.getResources());
            if (!SkillSecurityScanner.shouldAllow(
                    SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
                target.delete(name, ctx); // 尽力回滚（归档不合格草稿）
                return ToolResultBlock.error(
                        "Security scan blocked this skill ("
                                + scan.verdict()
                                + "):\n"
                                + scan.reportText());
            }
        }
        // 遥测：记录来源，使可见性过滤器／整理器能够识别此技能。
        if (usageStore != null) {
            try {
                if (config.autoPromote()) {
                    usageStore.markAgentCreated(name, "auto", List.of("prod"));
                } else {
                    usageStore.markAgentDraft(name, sessionId);
                }
            } catch (Exception e) {
                log.debug("usageStore mark on create({}) failed: {}", name, e.getMessage());
            }
        }
        String where = config.autoPromote() ? config.mainDir() : config.draftsDir();
        if (auditLog != null) {
            auditLog.append(
                    SkillAuditLog.manageEntry(
                            "agent",
                            name,
                            "create",
                            config.autoPromote() ? "main" : "draft",
                            "SAFE"));
        }
        return ToolResultBlock.text(
                "Skill '"
                        + name
                        + "' created at "
                        + where
                        + "/"
                        + name
                        + "/SKILL.md."
                        + (config.autoPromote()
                                ? " It will become visible on the next reasoning turn."
                                : " It is a draft and will NOT be auto-loaded until promoted."));
    }

    private ToolResultBlock doEdit(String name, String content, RuntimeContext ctx) {
        if (content == null || content.isBlank()) {
            return ToolResultBlock.error("Missing 'content' parameter (full SKILL.md).");
        }
        String contentErr = validateContent(content);
        if (contentErr != null) {
            return ToolResultBlock.error(contentErr);
        }
        WorkspaceSkillRepository target = locate(name, ctx);
        if (target == null) {
            return ToolResultBlock.error("Skill '" + name + "' not found.");
        }
        AgentSkill skill;
        try {
            skill = SkillUtil.createFrom(content, null, "agent");
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to parse frontmatter: " + e.getMessage());
        }
        if (skill == null || !name.equals(skill.getName())) {
            return ToolResultBlock.error(
                    "The 'name' parameter must match the SKILL.md frontmatter 'name' field.");
        }
        // 保存之前的 SKILL.md，以便扫描判定为 DANGEROUS 时回滚。
        String previous = target.readSkillFile(name, "SKILL.md", ctx);
        boolean ok = target.save(List.of(skill), true /* force = 覆盖 */, ctx);
        if (!ok) {
            return ToolResultBlock.error("Failed to edit skill '" + name + "'.");
        }
        if (config.securityScan()) {
            SkillSecurityScanner.ScanResult scan =
                    SkillSecurityScanner.scan(name, content, skill.getResources());
            if (!SkillSecurityScanner.shouldAllow(
                    SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
                if (previous != null) {
                    target.writeSkillFile(name, "SKILL.md", previous, ctx);
                }
                return ToolResultBlock.error(
                        "Security scan blocked this edit ("
                                + scan.verdict()
                                + "):\n"
                                + scan.reportText());
            }
        }
        bumpPatchSilent(name);
        return ToolResultBlock.text("Skill '" + name + "' SKILL.md replaced.");
    }

    private ToolResultBlock doPatch(
            String name,
            String oldString,
            String newString,
            String filePath,
            boolean replaceAll,
            RuntimeContext ctx) {
        if (oldString == null) {
            return ToolResultBlock.error("Missing 'old_string' for patch.");
        }
        if (newString == null) {
            return ToolResultBlock.error(
                    "Missing 'new_string' for patch (use empty string to delete matched text).");
        }
        WorkspaceSkillRepository target = locate(name, ctx);
        if (target == null) {
            return ToolResultBlock.error("Skill '" + name + "' not found.");
        }
        String relPath;
        if (filePath == null || filePath.isBlank()) {
            relPath = "SKILL.md";
        } else {
            String filePathErr = validateSubFilePath(filePath);
            if (filePathErr != null) {
                return ToolResultBlock.error(filePathErr);
            }
            relPath = filePath;
        }
        String existing = target.readSkillFile(name, relPath, ctx);
        if (existing == null) {
            return ToolResultBlock.error(
                    "File not found: " + relPath + " (in skill '" + name + "')");
        }

        // 模糊匹配阶梯：EXACT → TRAILING_WS_STRIPPED → WHITESPACE_COLLAPSED。
        // 只有严格级别没有匹配时才尝试宽松级别；之后基于选中级别的匹配
        // 检查唯一性，确保非 replace_all 模式安全。
        // 【学习提示】容忍空白差异不等于允许歧义替换：先选首个命中级别，
        // 再检查该级别的全部候选；非 replaceAll 模式只有唯一命中才能修改。
        FuzzyTextMatcher.SearchResult search = FuzzyTextMatcher.search(existing, oldString);
        if (search.isEmpty()) {
            return ToolResultBlock.error(
                    "old_string not found in "
                            + relPath
                            + " at any fuzziness level (tried exact, trailing-whitespace, and"
                            + " collapsed-whitespace). Re-read the file with"
                            + " load_skill_through_path and copy the exact text, or use"
                            + " action=edit to rewrite the file.");
        }
        FuzzyTextMatcher.Level matchedLevel = search.level();
        List<FuzzyTextMatcher.MatchRange> matches = search.matches();

        String updated;
        int replacements;
        if (replaceAll) {
            // 从右向左应用替换，避免使较早位置的偏移失效。
            StringBuilder buf = new StringBuilder(existing);
            for (int i = matches.size() - 1; i >= 0; i--) {
                FuzzyTextMatcher.MatchRange m = matches.get(i);
                buf.replace(m.start(), m.end(), newString);
            }
            updated = buf.toString();
            replacements = matches.size();
        } else {
            if (matches.size() > 1) {
                return ToolResultBlock.error(
                        "old_string is not unique in "
                                + relPath
                                + " (found "
                                + matches.size()
                                + " occurrences at match level "
                                + matchedLevel
                                + "). Add more surrounding context to make it unique, or set"
                                + " replace_all=true.");
            }
            FuzzyTextMatcher.MatchRange m = matches.get(0);
            updated = existing.substring(0, m.start()) + newString + existing.substring(m.end());
            replacements = 1;
        }

        if (updated.length() > MAX_SKILL_CONTENT_CHARS) {
            return ToolResultBlock.error(
                    "Patched content exceeds " + MAX_SKILL_CONTENT_CHARS + " chars.");
        }
        boolean ok = target.writeSkillFile(name, relPath, updated, ctx);
        if (!ok) {
            return ToolResultBlock.error("Failed to write patched file.");
        }
        if (config.securityScan()) {
            SkillSecurityScanner.ScanResult scan =
                    SkillSecurityScanner.scanSingleFile(relPath, updated);
            if (!SkillSecurityScanner.shouldAllow(
                    SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
                target.writeSkillFile(name, relPath, existing, ctx); // 回滚
                return ToolResultBlock.error(
                        "Security scan blocked this patch ("
                                + scan.verdict()
                                + "):\n"
                                + scan.reportText());
            }
        }
        bumpPatchSilent(name);
        // 报告模糊匹配级别，使 LLM 知道何时规范化了空白，
        // 便于智能体决定是否需要重新读取文件验证。
        String levelSuffix =
                matchedLevel == FuzzyTextMatcher.Level.EXACT
                        ? ""
                        : " [fuzzy: " + matchedLevel + "]";
        return ToolResultBlock.text(
                "Patched "
                        + relPath
                        + " in '"
                        + name
                        + "' ("
                        + replacements
                        + " replacement"
                        + (replacements == 1 ? "" : "s")
                        + ")"
                        + levelSuffix
                        + ".");
    }

    private ToolResultBlock doWriteFile(
            String name, String filePath, String fileContent, RuntimeContext ctx) {
        if (filePath == null || filePath.isBlank()) {
            return ToolResultBlock.error("Missing 'file_path' for write_file.");
        }
        String filePathErr = validateSubFilePath(filePath);
        if (filePathErr != null) {
            return ToolResultBlock.error(filePathErr);
        }
        if (fileContent == null) {
            return ToolResultBlock.error("Missing 'file_content' for write_file.");
        }
        if (fileContent.length() > MAX_SKILL_FILE_BYTES) {
            return ToolResultBlock.error(
                    "file_content exceeds " + MAX_SKILL_FILE_BYTES + " bytes.");
        }
        WorkspaceSkillRepository target = locate(name, ctx);
        if (target == null) {
            return ToolResultBlock.error("Skill '" + name + "' not found.");
        }
        String previous = target.readSkillFile(name, filePath, ctx);
        boolean ok = target.writeSkillFile(name, filePath, fileContent, ctx);
        if (!ok) {
            return ToolResultBlock.error("Failed to write " + filePath + ".");
        }
        if (config.securityScan()) {
            SkillSecurityScanner.ScanResult scan =
                    SkillSecurityScanner.scanSingleFile(filePath, fileContent);
            if (!SkillSecurityScanner.shouldAllow(
                    SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
                if (previous != null) {
                    target.writeSkillFile(name, filePath, previous, ctx);
                } else {
                    target.deleteSkillFile(name, filePath, ctx);
                }
                return ToolResultBlock.error(
                        "Security scan blocked write_file ("
                                + scan.verdict()
                                + "):\n"
                                + scan.reportText());
            }
        }
        bumpPatchSilent(name);
        return ToolResultBlock.text("Wrote " + filePath + " in skill '" + name + "'.");
    }

    private ToolResultBlock doRemoveFile(String name, String filePath, RuntimeContext ctx) {
        if (filePath == null || filePath.isBlank()) {
            return ToolResultBlock.error("Missing 'file_path' for remove_file.");
        }
        String filePathErr = validateSubFilePath(filePath);
        if (filePathErr != null) {
            return ToolResultBlock.error(filePathErr);
        }
        WorkspaceSkillRepository target = locate(name, ctx);
        if (target == null) {
            return ToolResultBlock.error("Skill '" + name + "' not found.");
        }
        boolean ok = target.deleteSkillFile(name, filePath, ctx);
        if (!ok) {
            return ToolResultBlock.error("Failed to remove " + filePath + ".");
        }
        bumpPatchSilent(name);
        return ToolResultBlock.text("Removed " + filePath + " from skill '" + name + "'.");
    }

    private ToolResultBlock doDelete(String name, String absorbedInto, RuntimeContext ctx) {
        WorkspaceSkillRepository target = locate(name, ctx);
        if (target == null) {
            return ToolResultBlock.error("Skill '" + name + "' not found.");
        }
        // absorbedInto 验证：非空时，目标必须存在（与 hermes 意图一致，
        // 便于整理器之后区分合并与裁剪）。
        if (absorbedInto != null && !absorbedInto.isBlank()) {
            String trimmed = absorbedInto.trim();
            if (trimmed.equals(name)) {
                return ToolResultBlock.error("absorbed_into cannot equal the skill being deleted.");
            }
            if (!mainRepo.skillExists(trimmed, ctx) && !draftsRepo.skillExists(trimmed, ctx)) {
                return ToolResultBlock.error(
                        "absorbed_into='"
                                + trimmed
                                + "' does not exist. Create or edit the umbrella skill first.");
            }
        }
        boolean ok = target.delete(name, ctx);
        if (!ok) {
            return ToolResultBlock.error(
                    "Failed to archive skill '" + name + "'. Check logs for details.");
        }
        if (usageStore != null) {
            try {
                usageStore.setState(name, SkillUsageRecord.State.ARCHIVED);
            } catch (Exception e) {
                log.debug("usageStore setState ARCHIVED({}) failed: {}", name, e.getMessage());
            }
        }
        String msg = "Skill '" + name + "' archived (moved under .archive/).";
        if (absorbedInto != null) {
            msg =
                    msg
                            + (absorbedInto.isBlank()
                                    ? " Declared as pure pruning (no merge target)."
                                    : " Declared as absorbed into '" + absorbedInto.trim() + "'.");
        }
        return ToolResultBlock.text(msg);
    }

    // ---------------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------------

    /**
     * 尽力执行 {@code bumpPatch}；绝不向调用方抛出异常。
     */
    private void bumpPatchSilent(String name) {
        if (usageStore == null) {
            return;
        }
        try {
            usageStore.bumpPatch(name);
        } catch (Exception e) {
            log.debug("usageStore.bumpPatch({}) failed: {}", name, e.getMessage());
        }
    }

    /**
     * 按名称定位拥有技能的仓库；冲突时草稿优先。
     */
    private WorkspaceSkillRepository locate(String name, RuntimeContext ctx) {
        if (draftsRepo.skillExists(name, ctx)) {
            return draftsRepo;
        }
        if (mainRepo.skillExists(name, ctx)) {
            return mainRepo;
        }
        return null;
    }

    static String validateName(String name) {
        if (name == null || name.isEmpty()) {
            return "Skill name is required.";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "Skill name exceeds " + MAX_NAME_LENGTH + " characters.";
        }
        if (!VALID_NAME_RE.matcher(name).matches()) {
            return "Invalid skill name '"
                    + name
                    + "'. Use lowercase letters, digits, and hyphens only. "
                    + "Must start with a letter or digit.";
        }
        return null;
    }

    static String validateContent(String content) {
        if (content.length() > MAX_SKILL_CONTENT_CHARS) {
            return "SKILL.md content exceeds " + MAX_SKILL_CONTENT_CHARS + " chars.";
        }
        // 必须具有前置元数据形式：首行以 "---" 开头，并包含结束的 "---"。
        String trimmed = content.startsWith("\uFEFF") ? content.substring(1) : content;
        trimmed = trimmed.stripLeading();
        if (!trimmed.startsWith("---")) {
            return "SKILL.md must start with '---' (YAML frontmatter).";
        }
        int second = trimmed.indexOf("\n---", 3);
        if (second < 0) {
            return "SKILL.md must include a closing '---' line after the frontmatter.";
        }
        return null;
    }

    static String validateSubFilePath(String filePath) {
        if (filePath.contains("..") || filePath.startsWith("/")) {
            return "file_path must be relative and may not contain '..' segments.";
        }
        int slash = filePath.indexOf('/');
        if (slash <= 0 || slash == filePath.length() - 1) {
            return "file_path must include a subdirectory and filename, e.g. 'scripts/probe.sh'.";
        }
        String head = filePath.substring(0, slash);
        if (!ALLOWED_SUBDIRS.contains(head)) {
            return "file_path must start with one of " + ALLOWED_SUBDIRS + " (got '" + head + "').";
        }
        return null;
    }

    private static String stringOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean boolOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return false;
    }
}
