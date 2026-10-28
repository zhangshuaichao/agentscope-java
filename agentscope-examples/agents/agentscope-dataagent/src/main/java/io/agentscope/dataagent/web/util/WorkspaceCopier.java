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
package io.agentscope.dataagent.web.util;

import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将一个智能体的每用户工作区文件复制到另一个智能体。操作基于 {@link AbstractFilesystem}，
 * 可适用不同后端；当前为 {@link WorkspaceManagerFactory#userDataFs} 提供的逐
 * {@code (userId, agentId)} 沙箱视图。
 * <p>递归 glob 遍历源，逐文件读取并上传到目标。{@code uploadFiles} 隐式创建目录，不保留空目录。
 * <p>刻意排除源根目录的活动日志 {@code activity*.jsonl}，让克隆以全新审计记录开始。
 */
public final class WorkspaceCopier {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCopier.class);

    private WorkspaceCopier() {}

    /**
     * 通过工厂将 {@code (srcOwnerId, srcAgentId)} 的用户数据文件复制到 {@code (dstOwnerId,
     * dstAgentId)}。仅复制用户隔离层；共享内容（AGENTS.md、tools.json、共享 skills/ 等）
     * 在工作区根仅保存一份，不为每个智能体重复复制。
     * @param srcWorkspacePath 用户提供的源智能体工作区路径（可为 null）
     * @param dstWorkspacePath 用户提供的目标智能体工作区路径（可为 null）
     * @return 已复制文件数
     */
    public static int copy(
            WorkspaceManagerFactory factory,
            String srcOwnerId,
            String srcAgentId,
            String srcWorkspacePath,
            String dstOwnerId,
            String dstAgentId,
            String dstWorkspacePath) {

        AbstractFilesystem srcFs = factory.userDataFs(srcOwnerId, srcAgentId, srcWorkspacePath);
        AbstractFilesystem dstFs = factory.userDataFs(dstOwnerId, dstAgentId, dstWorkspacePath);

        String srcPrefix = factory.userDataPathPrefix(srcOwnerId, srcAgentId, srcWorkspacePath);

        GlobResult globRes = srcFs.glob(null, "**/*", null);
        if (!globRes.isSuccess() || globRes.matches() == null) {
            log.info("Nothing to copy from {}/{}", srcOwnerId, srcAgentId);
            return 0;
        }

        List<Map.Entry<String, byte[]>> uploads = new ArrayList<>();
        int skipped = 0;
        for (FileInfo info : globRes.matches()) {
            if (info.isDirectory()) continue;
            String absPath = info.path();
            String rel = stripPrefix(absPath, srcPrefix);
            if (rel == null || rel.isBlank()) continue;
            if (rel.startsWith("activity") && rel.endsWith(".jsonl")) {
                skipped++;
                continue;
            }
            ReadResult rr = srcFs.read(null, rel, 0, Integer.MAX_VALUE);
            if (!rr.isSuccess() || rr.fileData() == null) {
                log.warn("Skipping file during clone (read failed): {} -> {}", absPath, rr.error());
                continue;
            }
            String content = rr.fileData().content();
            byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            uploads.add(new AbstractMap.SimpleEntry<>(rel, bytes));
        }

        if (uploads.isEmpty()) {
            log.info(
                    "Clone {}/{} -> {}/{}: source workspace empty (skipped {} audit files)",
                    srcOwnerId,
                    srcAgentId,
                    dstOwnerId,
                    dstAgentId,
                    skipped);
            return 0;
        }

        dstFs.uploadFiles(null, uploads);
        log.info(
                "Cloned {} files from {}/{} to {}/{} (skipped {} audit files)",
                uploads.size(),
                srcOwnerId,
                srcAgentId,
                dstOwnerId,
                dstAgentId,
                skipped);
        return uploads.size();
    }

    private static String stripPrefix(String absPath, String srcPrefix) {
        if (absPath == null) return null;
        if (absPath.startsWith(srcPrefix + "/")) {
            return absPath.substring(srcPrefix.length() + 1);
        }
        if (absPath.equals(srcPrefix)) return "";
        // 防御处理：某些存储的 glob 可能返回不带前导斜杠的路径。
        String trimmedPrefix = srcPrefix.startsWith("/") ? srcPrefix.substring(1) : srcPrefix;
        if (absPath.startsWith(trimmedPrefix + "/")) {
            return absPath.substring(trimmedPrefix.length() + 1);
        }
        return absPath; // 假定已是相对路径
    }
}
