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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.harness.agent.sandbox.layout.DirEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * 为工作区投影条目构建确定性的归档载荷。
 *
 * <p>载荷包括：
 * <ul>
 *   <li>投影文件的 tar 归档（供沙箱恢复）</li>
 *   <li>内容哈希，用于跨调用跳过未变化的投影</li>
 * </ul>
 */
public final class WorkspaceProjectionApplier {

    private WorkspaceProjectionApplier() {}

    /**
     * 从规范中的所有 {@link WorkspaceProjectionEntry} 条目构建投影载荷。
     */
    public static ProjectionPayload build(WorkspaceSpec spec) throws Exception {
        if (spec == null || spec.getEntries().isEmpty()) {
            return null;
        }
        List<WorkspaceProjectionEntry> entries = new ArrayList<>();
        collectProjectionEntries(spec.getEntries(), entries);
        if (entries.isEmpty()) {
            return null;
        }

        Map<String, Path> projectedFiles = collectProjectedFiles(entries);
        List<Map.Entry<String, Path>> ordered = new ArrayList<>(projectedFiles.entrySet());
        ordered.sort(Comparator.comparing(Map.Entry::getKey));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(baos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, Path> file : ordered) {
                String rel = file.getKey();
                Path src = file.getValue();
                byte[] content = Files.readAllBytes(src);

                digest.update(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(content);

                TarArchiveEntry entry = new TarArchiveEntry(rel);
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }

        String hash = bytesToHex(digest.digest());
        return new ProjectionPayload(hash, baos.toByteArray(), ordered.size());
    }

    private static void collectProjectionEntries(
            Map<String, WorkspaceEntry> map, List<WorkspaceProjectionEntry> out) {
        for (WorkspaceEntry entry : map.values()) {
            if (entry instanceof WorkspaceProjectionEntry projection) {
                out.add(projection);
            } else if (entry instanceof DirEntry dir && !dir.getChildren().isEmpty()) {
                collectProjectionEntries(dir.getChildren(), out);
            }
        }
    }

    private static Map<String, Path> collectProjectedFiles(List<WorkspaceProjectionEntry> entries)
            throws IOException {
        Map<String, Path> files = new LinkedHashMap<>();
        for (WorkspaceProjectionEntry entry : entries) {
            if (entry.getSourceRoot() == null || entry.getSourceRoot().isBlank()) {
                continue;
            }
            Path sourceRoot = Path.of(entry.getSourceRoot()).toAbsolutePath().normalize();
            for (String root : entry.getIncludeRoots()) {
                if (root == null || root.isBlank()) {
                    continue;
                }
                Path resolved = sourceRoot.resolve(root).normalize();
                if (!resolved.startsWith(sourceRoot) || !Files.exists(resolved)) {
                    continue;
                }
                if (Files.isRegularFile(resolved)) {
                    files.put(normalizePath(root), resolved);
                } else if (Files.isDirectory(resolved)) {
                    try (var walk = Files.walk(resolved)) {
                        walk.filter(Files::isRegularFile)
                                .forEach(
                                        p -> {
                                            String rel =
                                                    normalizePath(
                                                            sourceRoot.relativize(p).toString());
                                            files.put(rel, p);
                                        });
                    }
                }
            }
        }
        return files;
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 沙箱启动时使用的投影结果。
     *
     * @param hash 基于投影文件路径与字节的确定性哈希
     * @param tarBytes 包含投影文件的 tar 归档
     * @param fileCount 投影文件数量
     */
    public record ProjectionPayload(String hash, byte[] tarBytes, int fileCount) {}
}
