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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

/**
 * 演示逐用户路径沙箱防护的测试夹具（设计文档 §6.1）：相对调用方工作区根目录解析 {@code file_path}，拒绝越界，并将合法路径重写为沙箱内绝对路径。
 *
 * <p>按安全审查加固：严格检查用户 ID 格式，避免攻击者控制的 ID 成为路径片段；对最近已有祖先重新检查真实路径，防止新文件经符号链接目录写出边界。
 *
 * @author shanhongyu
 */
final class PathSandboxInterceptor implements ToolCallInterceptor {

    /**
     * 租户 ID 是不透明标识符，不是路径片段。
     */
    private static final Pattern SAFE_USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final Function<String, Path> workspaceRootResolver;

    PathSandboxInterceptor(Function<String, Path> workspaceRootResolver) {
        this.workspaceRootResolver = workspaceRootResolver;
    }

    @Override
    public Mono<ToolResultBlock> intercept(
            ToolInvocation invocation, Function<ToolInvocation, Mono<ToolResultBlock>> next) {
        return switch (invocation.toolName()) {
            case "write_text_file" -> guardPath(invocation, next);
            default -> next.apply(invocation);
        };
    }

    /**
     * 依据调用方沙箱根目录验证受保护工具的 {@code file_path} 参数，将合法路径重写为沙箱内绝对路径。
     *
     * @param invocation 调用快照
     * @param next 下游链
     * @return 拒绝结果，或使用重写输入后的下游结果
     */
    private Mono<ToolResultBlock> guardPath(
            ToolInvocation invocation, Function<ToolInvocation, Mono<ToolResultBlock>> next) {
        Object raw = invocation.input().get("file_path");
        if (raw == null) {
            return next.apply(invocation);
        }
        // 缺少身份时默认拒绝，不回退到共享根目录
        String userId =
                invocation.runtimeContext() != null
                        ? invocation.runtimeContext().getUserId()
                        : null;
        if (userId == null || userId.isBlank()) {
            return reject("no authenticated user context; cannot scope file access");
        }
        if (!SAFE_USER_ID.matcher(userId).matches()) {
            return reject("user context is malformed; cannot scope file access");
        }
        Path root = workspaceRootResolver.apply(userId).toAbsolutePath().normalize();
        // resolve 原样返回绝对原始路径，随后由 startsWith 拒绝
        Path resolved = root.resolve(raw.toString()).normalize();
        if (!resolved.startsWith(root) || !staysInsideRealBoundary(resolved, root)) {
            return reject("path must stay inside your workspace; use relative paths");
        }
        Map<String, Object> rewritten = new HashMap<>(invocation.input());
        rewritten.put("file_path", resolved.toString());
        return next.apply(invocation.withInput(rewritten));
    }

    /**
     * 重新检查真实路径：normalize() 不解析符号链接、Windows 8.3 短文件名或 NTFS ADS。目标或根尚不存在时，比较双方最近已有祖先的真实路径；新文件不得通过符号链接目录越界，全新工作区则仍应允许。
     *
     * @param resolved 已通过词法验证并规范化的候选路径
     * @param root 调用方沙箱根目录
     * @return 解析真实路径后仍位于根内时为 true
     */
    private static boolean staysInsideRealBoundary(Path resolved, Path root) {
        try {
            return resolved.toRealPath().startsWith(root.toRealPath());
        } catch (IOException eExist) {
            Path existingTarget = nearestExistingAncestor(resolved);
            Path existingRoot = nearestExistingAncestor(root);
            if (existingTarget == null || existingRoot == null) {
                return false;
            }
            try {
                return existingTarget.toRealPath().startsWith(existingRoot.toRealPath());
            } catch (IOException eAncestor) {
                return false;
            }
        }
    }

    /**
     * 向上遍历直到找到已有祖先。
     *
     * @param path 起始路径
     * @return 最近已有祖先；到达文件系统根时为 {@code null}
     */
    private static Path nearestExistingAncestor(Path path) {
        Path current = path;
        while (current != null) {
            if (Files.exists(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 构建可供模型采取后续动作的策略拒绝结果。
     *
     * @param modelMessage 展示给模型的拒绝原因
     * @return 携带拦截器身份前缀的 DENIED 结果
     */
    private static Mono<ToolResultBlock> reject(String modelMessage) {
        return Mono.just(
                ToolResultBlock.text("PathSandbox: " + modelMessage)
                        .withState(ToolResultState.DENIED));
    }
}
