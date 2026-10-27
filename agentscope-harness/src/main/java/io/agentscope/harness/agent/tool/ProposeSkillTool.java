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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 让智能体通过一次调用提出全新技能的便捷工具：名称、描述、正文和可选脚本。
 * 内部转换为 {@code SkillManageTool.create + skill_manage write_file*}，
 * 因此沿用标准暂存、验证与扫描管线。
 *
 * <p>与 {@code skill_manage(action="create")} 相比，此工具将正文和前置元数据
 * 分开接收（无需智能体手写 YAML），并支持在同一次调用中上传脚本列表。
 * 适合智能体刚发现可复用方法，希望保存它但不想编写完整 SKILL.md 文本的情况。
 */
public class ProposeSkillTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ProposeSkillTool.class);

    public static final String NAME = "propose_skill";

    private final SkillManageTool delegate;

    public ProposeSkillTool(SkillManageTool delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Propose a brand-new skill in one shot: pass name, description, body, and"
                + " optional scripts. Equivalent to skill_manage(create) + multiple"
                + " skill_manage(write_file) calls but doesn't require you to hand-write"
                + " the YAML frontmatter. Use this when you've just discovered a reusable"
                + " approach and want to commit it without re-deriving the SKILL.md format.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "name",
                Map.of(
                        "type", "string",
                        "description", "Skill name (kebab/snake case)."));
        properties.put(
                "description",
                Map.of(
                        "type", "string",
                        "description",
                                "One-sentence description of when this skill should be used."));
        properties.put(
                "body",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Markdown body of SKILL.md (no frontmatter — added" + " for you)."));
        properties.put(
                "scripts",
                Map.of(
                        "type", "array",
                        "description", "Optional list of {path, content} pairs to add as scripts/.",
                        "items",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "path", Map.of("type", "string"),
                                                        "content", Map.of("type", "string")),
                                        "required", List.of("path", "content"))));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name", "description", "body"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        if (input == null) {
            return Mono.just(ToolResultBlock.error("Missing input."));
        }
        String name = stringOf(input, "name");
        String description = stringOf(input, "description");
        String body = stringOf(input, "body");
        if (name == null
                || description == null
                || body == null
                || name.isBlank()
                || description.isBlank()
                || body.isBlank()) {
            return Mono.just(
                    ToolResultBlock.error(
                            "name, description, and body are required for propose_skill"));
        }
        String skillMd = "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
        // 步骤 1：通过现有 SkillManageTool 路径创建技能
        // （复用验证、暂存及扫描器）。
        Map<String, Object> createInput = new HashMap<>();
        createInput.put("action", "create");
        createInput.put("name", name);
        createInput.put("content", skillMd);
        return delegate.callAsync(
                        ToolCallParam.builder()
                                .toolUseBlock(param.getToolUseBlock())
                                .input(createInput)
                                .agent(param.getAgent())
                                .runtimeContext(param.getRuntimeContext())
                                .build())
                .flatMap(
                        createResult -> {
                            if (textOf(createResult).startsWith("Error:")) {
                                return Mono.just(createResult);
                            }
                            // 步骤 2：可选脚本。依次委托给 skill_manage
                            // write_file（使用相同暂存／扫描／附属元数据流程）。
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> scripts =
                                    (List<Map<String, Object>>) input.get("scripts");
                            if (scripts == null || scripts.isEmpty()) {
                                return Mono.just(createResult);
                            }
                            return uploadScripts(name, scripts, param)
                                    .thenReturn(
                                            ToolResultBlock.text(
                                                    textOf(createResult)
                                                            + "\nUploaded "
                                                            + scripts.size()
                                                            + " script(s)."));
                        })
                .onErrorResume(
                        e -> {
                            log.warn("propose_skill failed: {}", e.getMessage(), e);
                            return Mono.just(
                                    ToolResultBlock.error(
                                            "propose_skill failed: " + e.getMessage()));
                        });
    }

    private Mono<Void> uploadScripts(
            String name, List<Map<String, Object>> scripts, ToolCallParam parent) {
        return reactor.core.publisher.Flux.fromIterable(scripts)
                .concatMap(
                        script -> {
                            String path = stringOf(script, "path");
                            String content = stringOf(script, "content");
                            if (path == null || content == null) {
                                return Mono.empty();
                            }
                            // 智能体忘记前缀时，默认使用 scripts/<filename>.sh。
                            if (!path.contains("/")) {
                                path = "scripts/" + path;
                            }
                            Map<String, Object> in = new HashMap<>();
                            in.put("action", "write_file");
                            in.put("name", name);
                            in.put("file_path", path);
                            in.put("file_content", content);
                            return delegate.callAsync(
                                    ToolCallParam.builder()
                                            .toolUseBlock(parent.getToolUseBlock())
                                            .input(in)
                                            .agent(parent.getAgent())
                                            .runtimeContext(parent.getRuntimeContext())
                                            .build());
                        })
                .then();
    }

    private static String stringOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static String textOf(ToolResultBlock r) {
        if (r == null || r.getOutput() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var b : r.getOutput()) {
            if (b instanceof io.agentscope.core.message.TextBlock t) {
                sb.append(t.getText());
            }
        }
        return sb.toString();
    }
}
