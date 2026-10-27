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
package io.agentscope.harness.agent.skill.runtime;

import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import java.nio.file.Path;

/**
 * 根据技能的 {@link StageResult} 和当前 Shell 模式，解析 {@code <available_skills>} 与 {@code load_skill_through_path} 响应中输出的绝对 {@code filesRoot} 路径。
 *
 * <p>支持三种策略模式：
 * <ul>
 * <li>{@link Mode#NO_SHELL}：未注册 {@code ShellExecuteTool}。所有技能均返回 {@code null}，因此提示完全省略 {@code <files-root>} 和代码执行指令块。
 * <li>{@link Mode#SANDBOX}：沙箱文件系统，使用沙箱内部前缀（{@code /workspace/}）。
 * <li>{@link Mode#LOCAL_WITH_SHELL}：{@code LocalFilesystemWithShell}，使用宿主工作区的绝对根路径。
 * </ul>
 */
public final class ShellPathPolicy {

    public enum Mode {
        NO_SHELL,
        SANDBOX,
        LOCAL_WITH_SHELL
    }

    public static final String SANDBOX_WORKSPACE_PREFIX = "/workspace";

    private final Mode mode;
    private final Path workspaceRoot;
    private final String sandboxPrefix;

    private ShellPathPolicy(Mode mode, Path workspaceRoot, String sandboxPrefix) {
        this.mode = mode;
        this.workspaceRoot = workspaceRoot;
        this.sandboxPrefix = sandboxPrefix;
    }

    /**
     * 无可用 Shell；所有 {@code filesRoot} 都解析为 {@code null}。
     */
    public static ShellPathPolicy noShell() {
        return new ShellPathPolicy(Mode.NO_SHELL, null, null);
    }

    /**
     * 沙箱模式：路径位于默认 {@code /workspace/} 前缀下。
     */
    public static ShellPathPolicy sandbox() {
        return sandbox(SANDBOX_WORKSPACE_PREFIX);
    }

    /**
     * 使用自定义工作区前缀的沙箱模式。沙箱后端将工作区挂载到非默认位置时使用（例如 AgentRun 的 {@code /home/agentscope/workspace}）。
     *
     * @param workspacePrefix 沙箱内部工作区根目录的绝对路径
     */
    public static ShellPathPolicy sandbox(String workspacePrefix) {
        return new ShellPathPolicy(
                Mode.SANDBOX,
                null,
                workspacePrefix != null ? workspacePrefix : SANDBOX_WORKSPACE_PREFIX);
    }

    /**
     * 本地带 Shell 模式：使用宿主机绝对路径。
     */
    public static ShellPathPolicy localWithShell(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot required for LOCAL_WITH_SHELL");
        }
        return new ShellPathPolicy(Mode.LOCAL_WITH_SHELL, workspaceRoot, null);
    }

    public Mode mode() {
        return mode;
    }

    /**
     * 返回指定技能的绝对 {@code filesRoot}；Shell 不可用或技能没有 Shell 可达形式时返回 {@code null}。
     *
     * @param skillName 技能的 {@code name}
     * @param stage {@link MarketplaceStager#stage} 的暂存结果
     */
    public String resolve(String skillName, StageResult stage) {
        if (mode == Mode.NO_SHELL || stage == null || stage instanceof StageResult.None) {
            return null;
        }
        if (stage instanceof StageResult.WorkspaceNative) {
            return joinSkills(skillName);
        }
        if (stage instanceof StageResult.Cached cached) {
            return joinCache(cached.scopeSegment(), cached.sourceNamespace(), cached.skillName());
        }
        return null;
    }

    private String joinSkills(String skillName) {
        return switch (mode) {
            case SANDBOX -> escapeSpaces(sandboxPrefix + "/skills/" + skillName);
            case LOCAL_WITH_SHELL ->
                    escapeSpaces(
                            workspaceRoot
                                    .resolve("skills")
                                    .resolve(skillName)
                                    .toAbsolutePath()
                                    .toString());
            case NO_SHELL -> null;
        };
    }

    private String joinCache(String scopeSegment, String sourceNs, String skillName) {
        return switch (mode) {
            case SANDBOX ->
                    escapeSpaces(
                            sandboxPrefix
                                    + "/"
                                    + MarketplaceStager.CACHE_DIR
                                    + "/"
                                    + scopeSegment
                                    + "/"
                                    + sourceNs
                                    + "/"
                                    + skillName);
            case LOCAL_WITH_SHELL ->
                    escapeSpaces(
                            workspaceRoot
                                    .resolve(MarketplaceStager.CACHE_DIR)
                                    .resolve(scopeSegment)
                                    .resolve(sourceNs)
                                    .resolve(skillName)
                                    .toAbsolutePath()
                                    .toString());
            case NO_SHELL -> null;
        };
    }

    /**
     * 用反斜杠转义空格（例如 {@code "a b" -> "a\ b"}），使值能不加引号地嵌入 POSIX Shell 命令而不发生单词拆分。
     *
     * <p>这不是通用的 Shell 转义函数。
     */
    static String escapeSpaces(String value) {
        return value.indexOf(' ') >= 0 ? value.replace(" ", "\\ ") : value;
    }
}
