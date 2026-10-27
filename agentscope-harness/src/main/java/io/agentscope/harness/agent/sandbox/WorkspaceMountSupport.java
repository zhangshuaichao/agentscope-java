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

import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.DirEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 处理 {@link io.agentscope.harness.agent.sandbox.layout.BindMountEntry} 的辅助方法。
 */
public final class WorkspaceMountSupport {

    private WorkspaceMountSupport() {}

    /**
     * 按稳定迭代顺序返回规范树中每个绑定挂载的工作区相对路径（POSIX 风格，无前导斜杠）。
     */
    public static List<String> bindMountRelativePaths(WorkspaceSpec spec) {
        if (spec == null || spec.getEntries().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        walk(spec.getEntries(), "", out);
        return out;
    }

    public static boolean hasBindMounts(WorkspaceSpec spec) {
        return !bindMountRelativePaths(spec).isEmpty();
    }

    /**
     * 顶层条目键对应的沙箱内绝对挂载路径（斜杠已规范化）。
     */
    public static String containerMountPath(String workspaceRoot, String entryKey) {
        String root =
                workspaceRoot.endsWith("/")
                        ? workspaceRoot.substring(0, workspaceRoot.length() - 1)
                        : workspaceRoot;
        String key = entryKey.startsWith("/") ? entryKey.substring(1) : entryKey;
        key = key.replace('\\', '/');
        return root + "/" + key;
    }

    /**
     * 为每个绑定挂载路径生成 {@code tar} 命令行的 {@code --exclude} 参数（{@code ./relative}），防止快照 tar 遍历挂载内容。
     */
    public static List<String> tarExcludeArgsForBindMounts(WorkspaceSpec spec) {
        List<String> args = new ArrayList<>();
        for (String rel : bindMountRelativePaths(spec)) {
            String normalized = rel.replace('\\', '/');
            if (normalized.isEmpty()) {
                continue;
            }
            args.add("--exclude=./" + normalized);
        }
        return args;
    }

    /**
     * 为严格位于 {@code workspaceRoot} 下的沙箱内绝对挂载路径（如云卷挂载）生成 {@code tar} 命令行的 {@code --exclude} 参数（{@code ./relative}）。
     *
     * <p>等于根目录或位于根目录外的挂载不产生参数：根目录挂载表示整个工作区位于卷上（调用方会直接跳过持久化）；目录外挂载本就不会被 {@code tar -C root .} 遍历。
     *
     * @param workspaceRoot 创建 tar 归档所用的工作区绝对根路径
     * @param absoluteMountPaths 沙箱内绝对挂载路径
     * @return 根目录下挂载的 {@code --exclude} 参数，保持输入顺序
     */
    public static List<String> tarExcludeArgsForAbsolutePaths(
            String workspaceRoot, List<String> absoluteMountPaths) {
        if (workspaceRoot == null
                || workspaceRoot.isBlank()
                || absoluteMountPaths == null
                || absoluteMountPaths.isEmpty()) {
            return List.of();
        }
        String root = normalizeVirtualPath(workspaceRoot);
        List<String> args = new ArrayList<>();
        for (String raw : absoluteMountPaths) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String mount = normalizeVirtualPath(raw);
            if (!mount.startsWith("/") || mount.equals(root) || !isBeneath(root, mount)) {
                continue;
            }
            args.add("--exclude=./" + mount.substring(root.equals("/") ? 1 : root.length() + 1));
        }
        return args;
    }

    private static boolean isBeneath(String root, String mount) {
        if (root.equals("/")) {
            return mount.length() > 1;
        }
        return mount.startsWith(root + "/");
    }

    /**
     * 沙箱工作区根目录或挂载路径使用的绝对 POSIX 路径共享规范化器：去除首尾空白，将反斜杠转为斜杠，合并重复斜杠，去除末尾斜杠（根目录 {@code /} 自身除外）。空白或空输入得到 {@code /}。
     *
     * @param raw 原始路径，可为 null 或空白
     * @return 规范化绝对路径
     */
    public static String normalizeVirtualPath(String raw) {
        // 先合并重复斜杠，再移除单个末尾斜杠；反过来处理
        // 会使 "//mnt//data//" 之类的输入残留末尾斜杠。
        String normalized =
                stripTrailingSlash(
                        raw == null ? "" : raw.strip().replace('\\', '/').replaceAll("/+", "/"));
        return normalized.isEmpty() ? "/" : normalized;
    }

    private static String stripTrailingSlash(String s) {
        if (s.length() > 1 && s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static void walk(Map<String, WorkspaceEntry> entries, String prefix, List<String> out) {
        for (Map.Entry<String, WorkspaceEntry> e : entries.entrySet()) {
            String rel =
                    prefix.isEmpty()
                            ? e.getKey().replace('\\', '/')
                            : prefix + "/" + e.getKey().replace('\\', '/');
            WorkspaceEntry v = e.getValue();
            if (v instanceof BindMountEntry) {
                out.add(rel);
            } else if (v instanceof DirEntry dir
                    && dir.getChildren() != null
                    && !dir.getChildren().isEmpty()) {
                walk(dir.getChildren(), rel, out);
            }
        }
    }

    /**
     * 按条目键索引的顶层 {@link BindMountEntry} 实例，供仅应用扁平清单条目的存储使用（Docker {@code -v}、Kubernetes HostPath）。
     */
    public static Map<String, BindMountEntry> topLevelBindMounts(WorkspaceSpec spec) {
        Map<String, BindMountEntry> m = new LinkedHashMap<>();
        if (spec == null) {
            return m;
        }
        for (Map.Entry<String, WorkspaceEntry> e : spec.getEntries().entrySet()) {
            if (e.getValue() instanceof BindMountEntry b) {
                m.put(e.getKey(), b);
            }
        }
        return m;
    }

    /**
 * 用于 Docker / Kubernetes HostPath 的规范化宿主绝对路径。
 */
    public static String normalizedHostPath(String hostPath) {
        if (hostPath == null || hostPath.isBlank()) {
            return "";
        }
        return Path.of(hostPath).toAbsolutePath().normalize().toString();
    }
}
