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
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 专门用于将用户记忆同时持久化到 {@code MEMORY.md}
 * 与当日日志（{@code memory/YYYY-MM-DD.md}）的工具。
 *
 * <p>这是智能体写入长期记忆的<b>唯一</b>指定入口。系统提示要求 LLM 使用此工具，
 * 而不是对记忆路径使用 {@code write_file}/{@code edit_file}，
 * 从而消除 LLM 任意选择文件名或跳过每日日志带来的不确定性。
 *
 * <p>写入仅追加；去重与大小裁剪由周期性的
 * {@link io.agentscope.harness.agent.memory.MemoryConsolidator} 处理。
 */
public class MemorySaveTool {

    private final WorkspaceManager workspaceManager;

    public MemorySaveTool(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    @Tool(
            name = "memory_save",
            description =
                    "Persist one or more facts to long-term memory. Use whenever the user asks you"
                        + " to remember something, or when you observe important preferences,"
                        + " decisions, or context worth keeping across conversations. Do NOT use"
                        + " write_file or edit_file on MEMORY.md — always use this tool instead.")
    public ToolResultBlock memorySave(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "content",
                            description =
                                    "Markdown bullet list of facts to remember. Each bullet should"
                                            + " be a concise, self-contained fact. Example:\\n"
                                            + "- User prefers dark mode\\n"
                                            + "- Project deadline is 2026-07-01")
                    String content) {
        if (content == null || content.isBlank()) {
            return ToolResultBlock.error("content is required");
        }

        RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();

        String section = "\n" + content.strip() + "\n";

        workspaceManager.appendUtf8WorkspaceRelative(rc, WorkspaceConstants.MEMORY_MD, section);

        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dailyPath = WorkspaceConstants.MEMORY_DIR + "/" + today + ".md";
        String dailyEntry =
                String.format(
                        "\n## Memory Save — %s\n%s\n", Instant.now().toString(), content.strip());
        workspaceManager.appendUtf8WorkspaceRelative(rc, dailyPath, dailyEntry);

        long count = content.strip().lines().filter(l -> l.stripLeading().startsWith("-")).count();
        if (count == 0) {
            count = 1;
        }
        return ToolResultBlock.success(
                "Saved " + count + " memor" + (count == 1 ? "y" : "ies") + " to MEMORY.md");
    }
}
