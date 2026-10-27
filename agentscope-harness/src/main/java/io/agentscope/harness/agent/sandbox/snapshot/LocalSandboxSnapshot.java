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
package io.agentscope.harness.agent.sandbox.snapshot;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 将工作区归档作为 tar 文件保存在本地文件系统的快照。
 *
 * <p>归档采用原子写入：先写入同目录中的临时文件（前缀 {@code .}），再通过 {@link StandardCopyOption#ATOMIC_MOVE} 移动到最终路径。这样快照要么完整写入，要么不存在，不会出现部分写入的快照。
 *
 * <p>【学习提示】同目录临时文件加 ATOMIC_MOVE 避免发布半成品；不支持原子移动时会失败，
 * 不会悄然降级。这里没有显式 fsync，不能把原子可见性等同于断电后的持久性保证。
 *
 * <p>安全：{@code id} 必须是单个路径片段，不能包含 {@code /} 或 {@code ..}，以防路径遍历攻击。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LocalSandboxSnapshot implements SandboxSnapshot {

    private final String basePath;
    private final String id;

    /**
     * 创建本地快照。
     *
     * @param basePath 保存快照 tar 文件的目录
     * @param id 此快照的唯一标识符（必须是安全的单个路径片段）
     * @throws IllegalArgumentException {@code id} 包含不安全字符时抛出
     */
    @JsonCreator
    public LocalSandboxSnapshot(
            @JsonProperty("basePath") String basePath, @JsonProperty("id") String id) {
        validateId(id);
        this.basePath = basePath;
        this.id = id;
    }

    /**
     * {@inheritDoc}
     *
     * <p>将归档原子写入 {@code {basePath}/{id}.tar}。
     */
    @Override
    public void persist(InputStream workspaceArchive) throws Exception {
        Path targetPath = Path.of(basePath).resolve(id + ".tar");
        Path tmpPath = targetPath.resolveSibling("." + id + "." + UUID.randomUUID() + ".tmp");

        try {
            Files.createDirectories(targetPath.getParent());
            try (OutputStream out =
                    Files.newOutputStream(
                            tmpPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                workspaceArchive.transferTo(out);
            }
            Files.move(
                    tmpPath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (Exception ignored) {
                // 尽力清理临时文件
            }
            throw new SandboxException.SnapshotException(id, "Failed to persist snapshot", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>打开位于 {@code {basePath}/{id}.tar} 的快照 tar 文件以供读取。
     */
    @Override
    public InputStream restore() throws Exception {
        Path path = Path.of(basePath).resolve(id + ".tar");
        if (!Files.exists(path)) {
            throw new SandboxException.SnapshotException(id);
        }
        try {
            return Files.newInputStream(path);
        } catch (Exception e) {
            throw new SandboxException.SnapshotException(id, "Failed to read snapshot", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return 快照 tar 文件存在时为 {@code true}
     */
    @Override
    public boolean isRestorable() {
        return Files.exists(Path.of(basePath).resolve(id + ".tar"));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return "local";
    }

    /**
     * 返回保存快照 tar 文件的基目录。
     *
     * @return 基路径字符串
     */
    public String getBasePath() {
        return basePath;
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Snapshot id must not be null or blank");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..") || id.contains("\0")) {
            throw new SandboxException(
                    SandboxErrorCode.INVALID_MANIFEST_PATH,
                    "Snapshot id contains unsafe characters: " + id);
        }
    }
}
