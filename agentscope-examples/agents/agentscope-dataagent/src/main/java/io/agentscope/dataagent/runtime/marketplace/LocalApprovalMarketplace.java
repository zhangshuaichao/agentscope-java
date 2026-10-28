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
package io.agentscope.dataagent.runtime.marketplace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于文件系统的 {@link DataAgentMarketplace}，从磁盘逐智能体分区读取技能，
 * 约定位于 {@code ${dataagentHome}/shared/agents/<agentId>/skills/}。
 * 确切根目录由调用方选择，见 {@link io.agentscope.dataagent.web.config.DataAgentConfig}。
 * <p>这是管理员审批贡献流程的进程内实现：批准的贡献写入同一逐智能体分区，
 * 该智能体的所有租户均可见，因为每个 {@code (userId, agentId)} 沙箱都将此分区投影为容器下层。
 * <p>市场 API 只读；写入由 {@code MarketContributionService} 在管理员批准后通过独立路径完成。
 * <p>布局（以 {@code data-agent} 为例；图中附属资源按相对路径作为扁平键）：
 * <pre>
 * ${dataagentHome}/shared/agents/data-agent/skills/
 *   sql-analysis/
 *     SKILL.md
 *     templates/intro.md     ← side resources flat-keyed by their relative path
 *   chart-rendering/
 *     SKILL.md
 * </pre>
 * <p>图示对照：templates/intro.md 等辅助资源以相对路径作为扁平键保存。
 * <p>【学习提示】共享层发布会影响该智能体的全部租户，审批边界不同于仅写入个人覆盖层。
 * 审批通过是发布决策，不自动证明技能中的每项工具操作都获授权。
 */
public final class LocalApprovalMarketplace implements DataAgentMarketplace {

    private static final Logger log = LoggerFactory.getLogger(LocalApprovalMarketplace.class);
    public static final String TYPE = "local";

    private final String id;
    private final Path skillsRoot;

    public LocalApprovalMarketplace(String id, Path skillsRoot) {
        this.id = Objects.requireNonNull(id, "id");
        this.skillsRoot = Objects.requireNonNull(skillsRoot, "skillsRoot");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayLocation() {
        return skillsRoot.toAbsolutePath().toString();
    }

    @Override
    public boolean writable() {
        return false;
    }

    @Override
    public List<MarketSkillSummary> list() {
        if (!Files.isDirectory(skillsRoot)) {
            return List.of();
        }
        List<MarketSkillSummary> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(skillsRoot)) {
            entries.filter(Files::isDirectory)
                    .sorted()
                    .forEach(
                            dir -> {
                                Path skillMd = dir.resolve("SKILL.md");
                                if (!Files.isRegularFile(skillMd)) return;
                                String name = dir.getFileName().toString();
                                String description = firstNonBlankLine(skillMd);
                                out.add(new MarketSkillSummary(name, description, null));
                            });
        } catch (IOException e) {
            log.warn(
                    "LocalApprovalMarketplace '{}' failed to list skills under {}: {}",
                    id,
                    skillsRoot,
                    e.getMessage());
        }
        return out;
    }

    @Override
    public MarketSkillContent fetch(String name) {
        if (name == null || name.isBlank()) return null;
        Path dir = skillsRoot.resolve(name);
        if (!Files.isDirectory(dir) || !dir.normalize().startsWith(skillsRoot.normalize())) {
            return null;
        }
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) return null;

        String markdown;
        try {
            markdown = Files.readString(skillMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> resources = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(skillMd))
                    .sorted()
                    .forEach(
                            p -> {
                                String rel = dir.relativize(p).toString().replace('\\', '/');
                                try {
                                    resources.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                                } catch (IOException ignored) {
                                    // 尽力读取：跳过无法读取的附属文件（二进制等）
                                }
                            });
        } catch (IOException e) {
            log.warn(
                    "LocalApprovalMarketplace '{}' partial fetch of '{}': {}",
                    id,
                    name,
                    e.getMessage());
        }
        return new MarketSkillContent(
                name, firstNonBlankLine(skillMd), markdown, Collections.unmodifiableMap(resources));
    }

    @Override
    public void close() {
        // 没有需要释放的持久资源。
    }

    private static String firstNonBlankLine(Path skillMd) {
        try {
            for (String line : Files.readAllLines(skillMd, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("---")) continue;
                return t.length() > 200 ? t.substring(0, 200) : t;
            }
        } catch (IOException e) {
            // 继续执行后续分支
        }
        return "";
    }
}
