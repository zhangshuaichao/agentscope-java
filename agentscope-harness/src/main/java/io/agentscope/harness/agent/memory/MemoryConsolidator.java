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
package io.agentscope.harness.agent.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 使用 LLM 将每日记忆账本整合到经整理的 {@code MEMORY.md}。
 *
 * <p>此组件负责两层记忆模型的第二层：
 * <ul>
 * <li><b>第一层：每日账本</b>：{@code memory/YYYY-MM-DD.md} 文件由
 * {@link MemoryFlushManager} 写入，仅追加，每次压缩时的提取写入形成一个小节。</li>
 * <li><b>第二层：整理后的 MEMORY.md</b>：由本类管理。定期读取上次整合水位之后修改的
 * 每日账本及现有 MEMORY.md，请 LLM 合并、去重和裁剪，再以结果覆盖 MEMORY.md。</li>
 * </ul>
 *
 * <p>小型状态文件（{@code memory/.consolidation_state}）记录上次成功整合的时间戳。
 * 每日文件的 {@code modifiedAt} 若不晚于该时间戳，就会被跳过，以降低 token 用量，
 * 并避免用过时内容再次改写 MEMORY.md。
 *
 * <p>所有文件 I/O 都经由{@link AbstractFilesystem} 执行，该抽象从 {@link WorkspaceManager} 获取，
 * 因此本类不依赖具体后端，可用于本地、沙箱与远程文件系统，而不直接使用 {@code java.nio.file.Files}。
 */
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    /** 位于 {@code memory/} 内的隐藏状态文件，用于记录上次整合的时间点。 */
    public static final String STATE_FILE = ".consolidation_state";

    /**
     * 整合步骤的默认提示词。公开暴露，以便调用方在构建
     * {@link io.agentscope.harness.agent.memory.MemoryConfig} 时扩展，例如追加项目专用指南。
     * 提示词恰好包含两个 {@code %d} 占位符（最大 token 数、最大字符数），在调用时填入。
     */
    public static final String DEFAULT_CONSOLIDATION_PROMPT =
            """
            You are a memory consolidation assistant. You own the curated long-term memory \
            file MEMORY.md. Your job is to merge new daily ledger entries into MEMORY.md while \
            keeping it concise, deduplicated, and high-signal.

            You are given two inputs:
            1. The current MEMORY.md content (the existing curated long-term memory).
            2. New daily ledger entries that have been appended since the last consolidation.

            Rules:
            - MEMORY.md is the single source of truth for cross-day, cross-session knowledge. \
            Keep it stable and authoritative.
            - Daily ledger entries are stream-of-consciousness flush logs — they may be noisy, \
            redundant with MEMORY.md, or redundant with each other. Promote only what is \
            durable and reusable.
            - Deduplicate: if a new entry restates something MEMORY.md already covers, skip it.
            - Merge related facts: combine entries about the same topic into cohesive paragraphs \
            with clear section headers.
            - Update or remove stale information when new entries supersede it.
            - Keep total output within %d tokens (approximately %d characters); prioritize \
            recent and frequently-referenced information when trimming.

            Output the COMPLETE new MEMORY.md content (not just a diff). Use markdown.\
            """;

    private static final List<String> WATERMARK_NAMESPACE = List.of("memory", "consolidation");
    private static final String WATERMARK_KEY = "watermark";
    private static final int MAX_CAS_RETRIES = 5;

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final String consolidationPrompt;
    private final int maxMemoryTokens;
    private final BaseStore baseStore;

    public MemoryConsolidator(WorkspaceManager workspaceManager, Model model) {
        this(workspaceManager, model, DEFAULT_CONSOLIDATION_PROMPT, 4000, null);
    }

    public MemoryConsolidator(WorkspaceManager workspaceManager, Model model, int maxMemoryTokens) {
        this(workspaceManager, model, DEFAULT_CONSOLIDATION_PROMPT, maxMemoryTokens, null);
    }

    /**
     * @param consolidationPrompt 整合 LLM 调用的提示词模板，必须恰好包含两个 {@code %d}
     *     占位符（最大 token 数、最大字符数）。{@code null} 时回退到
     *     {@link #DEFAULT_CONSOLIDATION_PROMPT}。
     */
    public MemoryConsolidator(
            WorkspaceManager workspaceManager,
            Model model,
            String consolidationPrompt,
            int maxMemoryTokens) {
        this(workspaceManager, model, consolidationPrompt, maxMemoryTokens, null);
    }

    /**
     * @param baseStore 用于整合水位的可选分布式存储；设置后优先从该存储读取水位，
     *     写入采用 CAS，并提供文件系统回退路径
     */
    public MemoryConsolidator(
            WorkspaceManager workspaceManager,
            Model model,
            String consolidationPrompt,
            int maxMemoryTokens,
            BaseStore baseStore) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.consolidationPrompt =
                consolidationPrompt != null ? consolidationPrompt : DEFAULT_CONSOLIDATION_PROMPT;
        this.maxMemoryTokens = maxMemoryTokens;
        this.baseStore = baseStore;
    }

    /**
     * 执行整合：读取上次水位后修改的每日文件及当前 MEMORY.md，使用 LLM 合并，
     * 覆盖 MEMORY.md，并在成功后推进水位。
     *
     * <p>上次运行后若没有每日文件被修改，则不执行任何操作。
     */
    public Mono<Void> consolidate(RuntimeContext rc) {
        Instant watermark = readWatermark(rc);
        Instant runStart = Instant.now();

        String currentMemory = workspaceManager.readMemoryMd(rc);
        String dailyEntries = readDailyEntries(rc, watermark);

        if (dailyEntries.isBlank()) {
            log.debug("No fresh daily entries since {} — skipping consolidation", watermark);
            return Mono.empty();
        }

        int maxChars = maxMemoryTokens * 4;
        String systemPrompt = String.format(consolidationPrompt, maxMemoryTokens, maxChars);

        StringBuilder userContent = new StringBuilder();
        userContent.append("Current MEMORY.md:\n");
        userContent.append(currentMemory.isBlank() ? "(empty)" : currentMemory);
        userContent
                .append("\n\nNew daily ledger entries to merge")
                .append(watermark == Instant.EPOCH ? "" : " (since " + watermark + ")")
                .append(":\n");
        userContent.append(dailyEntries);

        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(systemPrompt).build())
                        .build());
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(userContent.toString()).build())
                        .build());

        return model.stream(messages, null, null)
                .reduce(
                        new StringBuilder(),
                        (sb, chatResponse) -> {
                            if (chatResponse.getContent() != null) {
                                for (var block : chatResponse.getContent()) {
                                    if (block instanceof TextBlock tb && tb.getText() != null) {
                                        sb.append(tb.getText());
                                    }
                                }
                            }
                            return sb;
                        })
                .flatMap(
                        sb -> {
                            String consolidated = sb.toString().strip();
                            if (consolidated.isBlank()) {
                                log.warn("Consolidation produced empty output, skipping");
                                return Mono.empty();
                            }
                            writeConsolidatedMemory(rc, consolidated);
                            writeWatermark(rc, runStart);
                            log.info(
                                    "MEMORY.md consolidated ({} chars), watermark advanced to {}",
                                    consolidated.length(),
                                    runStart);
                            return Mono.empty();
                        });
    }

    /**
     * 读取修改时间严格晚于给定水位的每日记忆文件。
     * 水位为 {@link Instant#EPOCH} 时返回全部每日文件（首次运行）。
     *
     * <p>所有 I/O 均通过 {@link AbstractFilesystem}，同样适用于本地、沙箱和 Store 后端。
     */
    private String readDailyEntries(RuntimeContext rc, Instant watermark) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return "";
        }

        GlobResult glob = fs.glob(rc, "*.md", "memory");
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return "";
        }

        List<FileInfo> eligible = new ArrayList<>();
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String name = fileName(fi.path());
            if (name.equals(STATE_FILE) || name.equals("archive") || !name.endsWith(".md")) {
                continue;
            }
            if (isModifiedAfter(fi, watermark)) {
                eligible.add(fi);
            }
        }
        eligible.sort(Comparator.comparing(fi -> fileName(fi.path())));

        StringBuilder sb = new StringBuilder();
        for (FileInfo fi : eligible) {
            String rel = toRelative(fi.path());
            String content = workspaceManager.readManagedWorkspaceFileUtf8(rc, rel);
            if (content != null && !content.isBlank()) {
                sb.append("### ").append(fileName(fi.path())).append("\n");
                sb.append(content.strip()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private static boolean isModifiedAfter(FileInfo fi, Instant watermark) {
        String modifiedAt = fi.modifiedAt();
        if (modifiedAt == null || modifiedAt.isBlank()) {
            return true; // 保守处理：修改时间未知时仍纳入。
        }
        try {
            return Instant.parse(modifiedAt).isAfter(watermark);
        } catch (Exception e) {
            return true; // 解析错误时也保守纳入。
        }
    }

    /** 从路径字符串中提取文件名（最后一个路径段）。 */
    private static String fileName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String stripped = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = stripped.lastIndexOf('/');
        return idx >= 0 ? stripped.substring(idx + 1) : stripped;
    }

    /**
     * 将绝对文件系统路径（例如 {@code /memory/2025-01-01.md}）转换为
     * 工作区相对路径（{@code memory/2025-01-01.md}），供
     * {@link WorkspaceManager#readManagedWorkspaceFileUtf8} 使用。
     */
    private static String toRelative(String path) {
        if (path == null) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private void writeConsolidatedMemory(RuntimeContext rc, String content) {
        workspaceManager.writeUtf8WorkspaceRelative(rc, "MEMORY.md", content);
    }

    static final String STATE_REL_PATH = "memory/" + STATE_FILE;

    /** 读取上次整合的时间点；没有记录时返回 {@link Instant#EPOCH}。 */
    Instant readWatermark(RuntimeContext rc) {
        BaseStore store = this.baseStore;
        if (store != null) {
            try {
                StoreItem item = store.get(WATERMARK_NAMESPACE, WATERMARK_KEY);
                if (item != null && item.value() != null) {
                    Object ts = item.value().get("ts");
                    if (ts instanceof Number n) {
                        return Instant.ofEpochMilli(n.longValue());
                    }
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Failed to read consolidation watermark from store: {} — falling back to"
                                + " file",
                        e.getMessage());
            }
        }
        return readWatermarkFromFile(rc);
    }

    private Instant readWatermarkFromFile(RuntimeContext rc) {
        try {
            String value = workspaceManager.readManagedWorkspaceFileUtf8(rc, STATE_REL_PATH);
            if (value == null || value.isBlank()) {
                return Instant.EPOCH;
            }
            return Instant.parse(value.strip());
        } catch (Exception e) {
            log.warn(
                    "Failed to read consolidation watermark at {}: {} — treating as EPOCH",
                    STATE_REL_PATH,
                    e.getMessage());
            return Instant.EPOCH;
        }
    }

    private void writeWatermark(RuntimeContext rc, Instant ts) {
        if (baseStore != null) {
            writeWatermarkToStore(ts);
        }
        try {
            workspaceManager.writeUtf8WorkspaceRelative(rc, STATE_REL_PATH, ts.toString());
        } catch (Exception e) {
            log.warn(
                    "Failed to write consolidation watermark at {}: {}",
                    STATE_REL_PATH,
                    e.getMessage());
        }
    }

    private void writeWatermarkToStore(Instant ts) {
        Map<String, Object> value = new HashMap<>();
        value.put("ts", ts.toEpochMilli());
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            StoreItem item;
            try {
                item = baseStore.get(WATERMARK_NAMESPACE, WATERMARK_KEY);
            } catch (RuntimeException e) {
                log.warn("Failed to read consolidation watermark for CAS: {}", e.getMessage());
                return;
            }
            long expectedVersion = item != null ? item.version() : 0L;
            try {
                if (baseStore.putIfVersion(
                        WATERMARK_NAMESPACE, WATERMARK_KEY, value, expectedVersion)) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to write consolidation watermark to store: {}", e.getMessage());
                return;
            }
        }
        log.warn(
                "Failed to advance consolidation watermark in store after {} CAS retries",
                MAX_CAS_RETRIES);
    }
}
