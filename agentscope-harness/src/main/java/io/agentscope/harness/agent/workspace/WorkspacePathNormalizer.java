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
package io.agentscope.harness.agent.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 去除当前模式的工作区前缀，将文件路径规范化为工作区相对路径。
 *
 * <p>只注册与当前文件系统模式匹配的前缀，因此不会在本地模式中让沙箱前缀
 * （{@code /workspace/}）误匹配真实主机目录，反之亦然。
 *
 * <p>不匹配任何已注册前缀的路径原样传递，保留在允许访问工作区外文件的模式下使用此类路径的能力。
 *
 * <p>【学习提示】规范化负责统一路径表示，不是授权检查；未匹配路径会原样返回，
 * 实际允许访问哪些位置仍由下游文件系统与路径策略决定。
 */
public final class WorkspacePathNormalizer {

    private final List<String> prefixes;
    private final NamespaceFactory namespaceFactory;

    private WorkspacePathNormalizer(List<String> prefixes, NamespaceFactory namespaceFactory) {
        this.prefixes = List.copyOf(prefixes);
        this.namespaceFactory = namespaceFactory;
    }

    /**
     * 创建去除给定前缀的规范化器。
     *
     * @param workspacePrefix 当前模式的工作区根路径，例如沙箱的 {@code "/workspace"}，或本地的主机工作区绝对路径
     */
    public static WorkspacePathNormalizer of(String workspacePrefix) {
        List<String> list = new ArrayList<>(1);
        String trimmed = trimTrailingSlash(workspacePrefix);
        if (trimmed != null && !trimmed.isEmpty()) {
            list.add(trimmed);
        }
        return new WorkspacePathNormalizer(list, null);
    }

    /**
     * 创建按顺序尝试多个前缀的规范化器。仅适用于当前模式具有多个有效前缀的情况，
     * 例如带 Shell 的本地模式中，项目目录与工作区目录都是有效根目录。
     */
    public static WorkspacePathNormalizer of(String... workspacePrefixes) {
        List<String> list = new ArrayList<>(workspacePrefixes.length);
        for (String p : workspacePrefixes) {
            String trimmed = trimTrailingSlash(p);
            if (trimmed != null && !trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return new WorkspacePathNormalizer(list, null);
    }

    /**
     * 创建同时去除工作区前缀下运行时命名空间的规范化器。
     * 当提示词展示会话级绝对路径，而文件系统在操作时给相对路径应用同一命名空间时，需要此能力。
     *
     * @param workspacePrefix 未带作用域的工作区根路径
     * @param namespaceFactory 当前运行时命名空间的工厂
     */
    public static WorkspacePathNormalizer of(
            String workspacePrefix, NamespaceFactory namespaceFactory) {
        List<String> list = new ArrayList<>(1);
        String trimmed = trimTrailingSlash(workspacePrefix);
        if (trimmed != null && !trimmed.isEmpty()) {
            list.add(trimmed);
        }
        return new WorkspacePathNormalizer(list, namespaceFactory);
    }

    /**
     * 去除当前模式的前缀，将路径规范化为工作区相对形式。
     *
     * <p>使用带命名空间工厂的规范化器时，调用方应使用
     * {@link #normalize(String, RuntimeContext)}，以提供操作上下文。
     * 此重载为兼容性保留，使用空上下文。
     *
     * @param path 原始路径（绝对或相对）
     * @return 工作区相对路径；没有已注册前缀匹配时返回原始路径
     */
    @Deprecated
    public String normalize(String path) {
        return normalize(path, RuntimeContext.empty());
    }

    /**
     * 使用给定运行时上下文的命名空间规范化路径。
     *
     * <p>先检查带命名空间的工作区前缀，再检查不带作用域的前缀。
     * 例如绝对路径 {@code /workspace/session-1/file.txt} 会变为 {@code file.txt}，
     * 而不是 {@code session-1/file.txt}，前提是当前命名空间为 {@code session-1}。
     *
     * @param path 原始路径（绝对或相对）
     * @param runtimeContext 当前操作上下文
     * @return 工作区相对路径；没有已注册前缀匹配时返回原始路径
     */
    public String normalize(String path, RuntimeContext runtimeContext) {
        if (path == null || path.isBlank()) {
            return path;
        }
        if (namespaceFactory != null) {
            List<String> namespace =
                    namespaceFactory.getNamespace(
                            runtimeContext != null ? runtimeContext : RuntimeContext.empty());
            if (namespace != null && !namespace.isEmpty()) {
                String namespacePrefix = String.join("/", namespace);
                for (String prefix : prefixes) {
                    String stripped = tryStrip(path, prefix + "/" + namespacePrefix);
                    if (stripped != null) {
                        return stripped;
                    }
                }
            }
        }
        for (String prefix : prefixes) {
            String stripped = tryStrip(path, prefix);
            if (stripped != null) {
                return stripped;
            }
        }
        return path;
    }

    private static String tryStrip(String path, String prefix) {
        String normalizedPath = path.replace('\\', '/');
        String normalizedPrefix = prefix.replace('\\', '/');
        if (normalizedPath.startsWith(normalizedPrefix + "/")) {
            return normalizedPath.substring(normalizedPrefix.length() + 1);
        }
        if (normalizedPath.equals(normalizedPrefix)) {
            return ".";
        }
        return null;
    }

    private static String trimTrailingSlash(String s) {
        if (s != null && s.length() > 1 && s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
