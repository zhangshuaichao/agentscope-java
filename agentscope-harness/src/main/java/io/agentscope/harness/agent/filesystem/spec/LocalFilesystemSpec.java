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
package io.agentscope.harness.agent.filesystem.spec;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地文件系统模式（带 Shell 执行）的配置描述。
 *
 * <p>此配置创建 {@link LocalFilesystemWithShell}，以智能体工作区为根，
 * Shell 直接在宿主机上通过 {@code sh -c <command>} 执行。
 * 长期记忆（{@code MEMORY.md}、{@code memory/}）和会话日志保存在同一本地磁盘。
 *
 * <p>适用于不需要分布式共享、且信任智能体执行宿主机 Shell 命令的单进程部署，
 * 例如个人助手、CLI 工具和本地开发循环。
 *
 * <p>若分布式部署需要跨副本共享长期记忆，优先使用
 * {@link RemoteFilesystemSpec}（无 Shell）或沙箱文件系统配置（通过沙箱执行 Shell）。
 */
public class LocalFilesystemSpec {

    private int executeTimeoutSeconds = LocalFilesystemWithShell.DEFAULT_EXECUTE_TIMEOUT;
    private int maxOutputBytes = 100_000;
    private final Map<String, String> env = new LinkedHashMap<>();
    private boolean inheritEnv = false;

    /**
     * 上层 {@link LocalFilesystemWithShell} 的路径解析策略。
     * 默认使用 {@link LocalFsMode#ROOTED}，仅接受位于所配置根目录
     * （项目、工作区及 additionalRoots）之内的智能体绝对路径。
     */
    private LocalFsMode mode = LocalFsMode.ROOTED;

    private IsolationScope isolationScope;

    /**
     * 用户项目根目录（最终 {@link OverlayFilesystem} 的下层）。
     * 智能体从此目录读取项目内容（例如 {@code AGENTS.md}、{@code knowledge/}、{@code skills/}），
     * 修改时通过写时复制写入智能体 {@code workspace}。
     * 它同时作为 {@code execute()} 的 Shell {@code pwd}，使命令输出符合用户预期。
     *
     * <p>调用 {@link #project(Path)} 前为 {@code null}；在 {@link #toFilesystem} 时默认使用
     * {@link System#getProperty(String) System.getProperty("user.dir")}。
     */
    private Path project;

    /**
     * 除 {@code project} 和 {@code workspace} 外，智能体在 {@link LocalFsMode#ROOTED}
     * 模式下可访问的其他宿主机目录。对应 Claude Code CLI 的 {@code --add-dir} 标志。
     */
    private final List<Path> additionalRoots = new ArrayList<>();

    /**
     * 为 {@code true} 时，非工作区路径（即不是 {@code MEMORY.md}、{@code agents/}、
     * {@code skills/} 等工作区元数据）的文件写入操作路由到项目目录，而非工作区。
     * 工作区元数据路径仍写入工作区。
     *
     * <p>默认 {@code false}，保留所有写入均落到工作区的原覆盖行为。
     */
    private boolean projectWritable = false;

    /**
     * 为 {@code true} 时，未单独路由路径的文件操作以共享工作区根解析，
     * 不附加按用户划分的命名空间前缀。宿主应用与智能体因而使用相同物理位置：
     * 宿主通过普通 {@code java.nio} 写入工作区根的文件（例如 {@code uploads/}）
     * 可由 {@code read_file}／{@code list_files} 读取，智能体写入也落在宿主预期位置。
     *
     * <p>默认 {@code false}，保留命名空间行为；其读写不对称与
     * {@link RemoteFilesystemSpec#sharedLocalWorkspace(boolean)} 相同（#3245）。
     *
     * <p><b>不适合多租户工作区：</b>共享模式关闭工作区根的按用户命名空间隔离；
     * 配置到同一工作区目录的用户／会话会读取并覆盖彼此文件。
     * 此选项面向单租户宿主应用集成。
     */
    private boolean sharedLocalWorkspace = false;

    /**
     * 设置默认命令执行超时，单位秒。
     *
     * @param seconds 超时（必须为正）
     * @return 此配置对象
     */
    public LocalFilesystemSpec executeTimeoutSeconds(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + seconds);
        }
        this.executeTimeoutSeconds = seconds;
        return this;
    }

    /**
     * 设置单条 Shell 命令最多捕获的输出字节数。
     *
     * @param bytes 字节数上限（必须为正）
     * @return 此配置对象
     */
    public LocalFilesystemSpec maxOutputBytes(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive, got " + bytes);
        }
        this.maxOutputBytes = bytes;
        return this;
    }

    /**
     * 添加每条 Shell 命令都会设置的环境变量。
     *
     * @param name 变量名
     * @param value 变量值
     * @return 此配置对象
     */
    public LocalFilesystemSpec env(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("env name must not be blank");
        }
        this.env.put(name, value);
        return this;
    }

    /**
     * 控制 Shell 命令是否继承父进程环境。
     * 为 {@code false}（默认）时，仅能看到通过 {@link #env(String, String)} 添加的变量。
     *
     * @param inherit 是否继承父进程环境
     * @return 此配置对象
     */
    public LocalFilesystemSpec inheritEnv(boolean inherit) {
        this.inheritEnv = inherit;
        return this;
    }

    /**
     * 旧选项：{@code true} 映射到 {@link LocalFsMode#SANDBOXED}，
     * {@code false} 映射到 {@link LocalFsMode#UNRESTRICTED}。
     * 优先使用 {@link #mode(LocalFsMode)}，以便也能选择 {@link LocalFsMode#ROOTED}。
     *
     * @param virtual 是否启用虚拟模式
     * @return 此配置对象
     * @deprecated 使用 {@link #mode(LocalFsMode)} 选择完整的三种模式
     */
    @Deprecated
    public LocalFilesystemSpec virtualMode(boolean virtual) {
        return mode(virtual ? LocalFsMode.SANDBOXED : LocalFsMode.UNRESTRICTED);
    }

    /**
     * 设置上层 {@link LocalFilesystemWithShell} 的路径解析策略。
     * 默认 {@link LocalFsMode#ROOTED}。
     *
     * @param mode 策略模式
     * @return 此配置对象
     */
    public LocalFilesystemSpec mode(LocalFsMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        this.mode = mode;
        return this;
    }

    /**
     * 设置隔离作用域，控制文件路径如何按用户、会话或智能体划分命名空间。
     * 默认 {@link IsolationScope#USER}，与 {@link RemoteFilesystemSpec} 及沙箱配置一致。
     *
     * @param scope 隔离作用域
     * @return 此配置对象
     */
    public LocalFilesystemSpec isolationScope(IsolationScope scope) {
        this.isolationScope = scope;
        return this;
    }

    /**
     * 返回已配置的隔离作用域；{@code null} 表示使用默认值。
     */
    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    /**
     * 添加在 {@link LocalFsMode#ROOTED} 下允许智能体通过绝对路径访问的额外宿主机目录。
     * 忽略 {@code null} 条目。
     *
     * @param root 额外允许的根目录
     * @return 此配置对象
     */
    public LocalFilesystemSpec addRoot(Path root) {
        if (root != null) {
            this.additionalRoots.add(root);
        }
        return this;
    }

    /**
     * 启用或禁用项目可写模式。为 {@code true} 时，
     * 智能体对非工作区路径的文件写入操作会路由到项目目录。
     *
     * @param writable 非工作区写入是否进入项目目录
     * @return 此配置对象
     */
    public LocalFilesystemSpec projectWritable(boolean writable) {
        this.projectWritable = writable;
        return this;
    }

    /**
     * 返回是否启用了项目可写模式。
     */
    public boolean isProjectWritable() {
        return projectWritable;
    }

    /**
     * 替换额外宿主机目录列表。参见 {@link #addRoot(Path)}。
     *
     * @param roots 额外根目录（{@code null} 表示清空）
     * @return 此配置对象
     */
    public LocalFilesystemSpec additionalRoots(Collection<Path> roots) {
        this.additionalRoots.clear();
        if (roots != null) {
            for (Path r : roots) {
                if (r != null) {
                    this.additionalRoots.add(r);
                }
            }
        }
        return this;
    }

    /**
     * 设置最终 {@link OverlayFilesystem} 下层使用的用户项目根目录。
     * 智能体 {@code workspace} 不包含 {@code AGENTS.md}、{@code knowledge/}、{@code skills/}
     * 等内容时，读取会回退到此目录；Shell {@code execute()} 的 {@code pwd} 也设为此目录。
     *
     * <p>未设置时默认 {@code System.getProperty("user.dir")}。
     *
     * @param project 项目根路径
     * @return 此配置对象
     */
    public LocalFilesystemSpec project(Path project) {
        this.project = project;
        return this;
    }

    /**
     * 构建实际 {@link OverlayFilesystem}：智能体 {@code workspace} 为上层
     * （可读写，承载 Shell），用户 {@link #project(Path)} 为只读下层。
     * 默认写入落到 {@code workspace}；读取先查 {@code workspace}，再回退到 {@code project}，
     * 为来自项目树的文件提供写时复制语义。
     *
     * <p>【学习提示】上面描述的是默认分支；开启 projectWritable 时，构造的
     * ProjectAwareOverlay 会把非工作区元数据的写入路由到项目目录。
     * 配置对象的默认值与最终构造分支必须一起读，不能把“所有写入进工作区”理解为无条件保证。
     *
     * @param workspace 智能体工作区根目录（成为覆盖上层）
     * @param localNamespaceFactory 可选的命名空间工厂，按用户／会话限定文件夹作用域
     * @return 根据此配置选项装配的 {@link OverlayFilesystem}
     */
    /**
     * 明确配置的项目根目录；{@code null} 时回退到 {@code ${user.dir}}。
     */
    public Path getProject() {
        return project;
    }

    /**
     * 当前配置的路径解析策略模式。
     */
    public LocalFsMode getMode() {
        return mode;
    }

    /**
     * 启用或禁用未单独路由路径使用共享工作区根；参见字段 Javadoc。
     * 启用后，上层与项目层都不附加按用户划分的命名空间前缀。
     *
     * @param shared 未单独路由路径是否以共享工作区根解析
     * @return 此配置对象
     */
    public LocalFilesystemSpec sharedLocalWorkspace(boolean shared) {
        this.sharedLocalWorkspace = shared;
        return this;
    }

    /**
     * 已配置额外根目录的快照。
     */
    public List<Path> getAdditionalRoots() {
        return List.copyOf(additionalRoots);
    }

    public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory localNamespaceFactory) {
        Path effectiveProject =
                project != null ? project : Paths.get(System.getProperty("user.dir"));
        // sharedLocalWorkspace：去掉按用户划分的前缀，使宿主应用在工作区
        // 根的写入与智能体读写使用相同物理位置（#3245）。
        NamespaceFactory effectiveNamespaceFactory =
                sharedLocalWorkspace ? null : localNamespaceFactory;
        List<Path> policyRoots = new ArrayList<>();
        policyRoots.add(effectiveProject);
        policyRoots.add(workspace);
        policyRoots.addAll(additionalRoots);
        PathPolicy pathPolicy = PathPolicy.of(policyRoots);
        LocalFilesystemWithShell upper =
                new LocalFilesystemWithShell(
                        workspace,
                        mode,
                        pathPolicy,
                        executeTimeoutSeconds,
                        maxOutputBytes,
                        env.isEmpty() ? null : Map.copyOf(env),
                        inheritEnv,
                        effectiveNamespaceFactory,
                        effectiveProject);
        LocalFilesystem lower = new LocalFilesystem(effectiveProject, true, 10, null);
        if (projectWritable) {
            LocalFilesystem projectFs =
                    new LocalFilesystem(
                            effectiveProject, mode, pathPolicy, 10, effectiveNamespaceFactory);
            return new ProjectAwareOverlay(
                    (AbstractSandboxFilesystem) upper,
                    lower,
                    projectFs,
                    workspace,
                    effectiveNamespaceFactory);
        }
        return OverlayFilesystem.of(upper, lower);
    }
}
