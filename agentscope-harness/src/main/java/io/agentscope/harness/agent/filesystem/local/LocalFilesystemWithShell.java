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
package io.agentscope.harness.agent.filesystem.local;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 支持不受限制的本地 Shell 命令执行的文件系统。
 *
 * <p>此实现扩展 {@link LocalFilesystem}，加入 Shell 命令执行能力。
 * 命令直接在宿主系统上执行，没有沙箱、进程隔离或安全限制。
 *
 * <p><b>警告：</b>此实现同时授予智能体直接文件系统访问和本机不受限制的 Shell 执行能力。
 * 务必谨慎，仅用于合适环境（本地开发、配有妥善密钥管理的 CI/CD）。
 *
 * <p>【学习提示】文件 API 的路径模式不会把 ProcessBuilder 启动的 Shell 变成隔离进程。
 * 即使读取和写入路径受约束，也不能据此推断命令只能访问相同目录。
 */
public class LocalFilesystemWithShell extends LocalFilesystem implements AbstractSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystemWithShell.class);

    /**
     * Shell 命令执行的默认超时，单位秒。
     */
    public static final int DEFAULT_EXECUTE_TIMEOUT = 120;

    /**
     * 流排空线程的读取缓冲区大小，单位字节。
     */
    private static final int DRAIN_CHUNK_BYTES = 8192;

    /**
     * 进程退出或被销毁后，等待流排空线程结束的兜底时限，单位毫秒。
     * 正常情况下进程退出会关闭流并立即结束排空线程，无需等满此时限。
     */
    private static final long DRAIN_JOIN_TIMEOUT_MILLIS = 5000;

    private final String sandboxId;
    private final int defaultTimeout;
    private final int maxOutputBytes;
    private final Map<String, String> env;

    /**
     * Shell 命令传给 {@link ProcessBuilder#directory(java.io.File)} 的工作目录。
     * 为 {@code null} 时回退到 {@link #getCwd()}（附加本次调用的命名空间前缀）。
     * 将 Shell {@code pwd} 与文件系统根解耦，使覆盖模式调用方能让文件操作仍以智能体
     * 工作区为根，而 Shell 使用用户项目目录。
     */
    private final Path shellCwd;

    /**
     * 使用默认设置创建抽象文件系统。
     *
     * @param rootDir 文件系统和 Shell 操作共同使用的工作目录
     */
    public LocalFilesystemWithShell(Path rootDir) {
        this(rootDir, false, DEFAULT_EXECUTE_TIMEOUT, 100_000, null, false, null);
    }

    /**
     * 与 {@link #LocalFilesystemWithShell(Path)} 相同，但接收路径字符串；
     * {@code null}／空白规则参见 {@link LocalFilesystem#LocalFilesystem(String)}。
     */
    public LocalFilesystemWithShell(String rootDir) {
        this(
                LocalFilesystem.rootDirFromString(rootDir),
                false,
                DEFAULT_EXECUTE_TIMEOUT,
                100_000,
                null,
                false,
                null);
    }

    /**
     * 使用默认设置和命名空间支持创建抽象文件系统。
     *
     * @param rootDir 文件系统和 Shell 操作共同使用的工作目录
     * @param namespaceFactory 限定路径作用域的可选命名空间工厂（{@code null} 表示不使用）
     */
    public LocalFilesystemWithShell(Path rootDir, NamespaceFactory namespaceFactory) {
        this(rootDir, false, DEFAULT_EXECUTE_TIMEOUT, 100_000, null, false, namespaceFactory);
    }

    /**
     * 与 {@link #LocalFilesystemWithShell(Path, NamespaceFactory)} 相同，但接收路径字符串；
     * {@code null}／空白规则参见 {@link LocalFilesystem#LocalFilesystem(String)}。
     */
    public LocalFilesystemWithShell(String rootDir, NamespaceFactory namespaceFactory) {
        this(
                LocalFilesystem.rootDirFromString(rootDir),
                false,
                DEFAULT_EXECUTE_TIMEOUT,
                100_000,
                null,
                false,
                namespaceFactory);
    }

    /**
     * 使用完整配置创建抽象文件系统。
     *
     * @param rootDir 文件系统和 Shell 操作共同使用的工作目录
     * @param virtualMode 为文件系统操作启用虚拟路径模式
     * @param timeout Shell 命令执行的默认最长时间，单位秒
     * @param maxOutputBytes 最多捕获的命令输出字节数
     * @param env Shell 命令环境变量（{@code null} 表示空）
     * @param inheritEnv 是否继承父进程环境变量
     */
    public LocalFilesystemWithShell(
            Path rootDir,
            boolean virtualMode,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv) {
        this(rootDir, virtualMode, timeout, maxOutputBytes, env, inheritEnv, null);
    }

    /**
     * 与 {@link #LocalFilesystemWithShell(Path, boolean, int, int, Map, boolean)} 相同，
     * 但接收路径字符串；{@code null}／空白规则参见 {@link LocalFilesystem#LocalFilesystem(String)}。
     */
    public LocalFilesystemWithShell(
            String rootDir,
            boolean virtualMode,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv) {
        this(
                LocalFilesystem.rootDirFromString(rootDir),
                virtualMode,
                timeout,
                maxOutputBytes,
                env,
                inheritEnv,
                null);
    }

    /**
     * 使用完整配置和命名空间支持创建抽象文件系统。
     *
     * @param rootDir 文件系统和 Shell 操作共同使用的工作目录
     * @param virtualMode 为文件系统操作启用虚拟路径模式
     * @param timeout Shell 命令执行的默认最长时间，单位秒
     * @param maxOutputBytes 最多捕获的命令输出字节数
     * @param env Shell 命令环境变量（{@code null} 表示空）
     * @param inheritEnv 是否继承父进程环境变量
     * @param namespaceFactory 限定路径作用域的可选命名空间工厂（{@code null} 表示不使用）
     */
    public LocalFilesystemWithShell(
            Path rootDir,
            boolean virtualMode,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv,
            NamespaceFactory namespaceFactory) {
        this(
                rootDir,
                virtualMode,
                timeout,
                maxOutputBytes,
                env,
                inheritEnv,
                namespaceFactory,
                null);
    }

    /**
     * 使用完整配置、命名空间支持和自定义 Shell 工作目录创建抽象文件系统。
     *
     * @param rootDir 文件系统操作（读取／写入／编辑／glob 等）的工作目录
     * @param virtualMode 为文件系统操作启用虚拟路径模式
     * @param timeout Shell 命令执行的默认最长时间，单位秒
     * @param maxOutputBytes 最多捕获的命令输出字节数
     * @param env Shell 命令环境变量（{@code null} 表示空）
     * @param inheritEnv 是否继承父进程环境变量
     * @param namespaceFactory 限定路径作用域的可选命名空间工厂（{@code null} 表示不使用）
     * @param shellCwd Shell 命令执行工作目录；为 {@code null} 时回退到 {@code rootDir}
     *     （配置命名空间时附加相应前缀）
     */
    public LocalFilesystemWithShell(
            Path rootDir,
            boolean virtualMode,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv,
            NamespaceFactory namespaceFactory,
            Path shellCwd) {
        this(
                rootDir,
                virtualMode ? LocalFsMode.SANDBOXED : LocalFsMode.UNRESTRICTED,
                null,
                timeout,
                maxOutputBytes,
                env,
                inheritEnv,
                namespaceFactory,
                shellCwd);
    }

    /**
     * 最完整的构造方法：文件系统操作遵循 {@code mode} 和 {@code pathPolicy}
     * （见 {@link LocalFilesystem#LocalFilesystem(Path, LocalFsMode, PathPolicy, int, NamespaceFactory)}）；
     * 设置 Shell 工作目录时命令以 {@code pwd = shellCwd} 运行，否则使用文件系统根。
     *
     * @param rootDir 相对路径操作的文件系统根目录
     * @param mode 路径解析策略（{@code null} 视为 {@link LocalFsMode#UNRESTRICTED}）
     * @param pathPolicy {@link LocalFsMode#ROOTED} 的允许列表；其他模式忽略
     * @param timeout 默认 Shell 超时（秒，必须为正）
     * @param maxOutputBytes 捕获 Shell 输出的字节数上限
     * @param env Shell 命令环境变量（{@code null} 表示空）
     * @param inheritEnv 是否继承父进程环境变量
     * @param namespaceFactory 可选的按用户／会话划分的命名空间工厂
     * @param shellCwd Shell {@code pwd}；{@code null} 时回退到 {@code rootDir}
     *     （配置命名空间时附加相应前缀）
     */
    public LocalFilesystemWithShell(
            Path rootDir,
            LocalFsMode mode,
            PathPolicy pathPolicy,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv,
            NamespaceFactory namespaceFactory,
            Path shellCwd) {
        super(rootDir, mode, pathPolicy, 10, namespaceFactory);

        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }

        this.defaultTimeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.sandboxId = "local-" + UUID.randomUUID().toString().substring(0, 8);
        this.shellCwd = shellCwd != null ? shellCwd.toAbsolutePath().normalize() : null;

        if (inheritEnv) {
            Map<String, String> merged = new java.util.HashMap<>(System.getenv());
            if (env != null) {
                merged.putAll(env);
            }
            this.env = Map.copyOf(merged);
        } else {
            this.env = env != null ? Map.copyOf(env) : Map.of();
        }
    }

    /**
     * 与 {@link #LocalFilesystemWithShell(Path, boolean, int, int, Map, boolean,
     * NamespaceFactory)} 相同，但接收路径字符串；{@code null}／空白规则参见
     * {@link LocalFilesystem#LocalFilesystem(String)}。
     */
    public LocalFilesystemWithShell(
            String rootDir,
            boolean virtualMode,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv,
            NamespaceFactory namespaceFactory) {
        this(
                LocalFilesystem.rootDirFromString(rootDir),
                virtualMode,
                timeout,
                maxOutputBytes,
                env,
                inheritEnv,
                namespaceFactory);
    }

    @Override
    public String id() {
        return sandboxId;
    }

    /**
     * 返回为 Shell {@code execute()} 调用配置的工作目录；
     * Shell 回退到文件系统根（带命名空间前缀）时返回 {@code null}。
     * 供需要在提示词或诊断中展示用户可见项目目录的上层代码使用。
     */
    public Path getShellCwd() {
        return shellCwd;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        if (command == null || command.isBlank()) {
            return new ExecuteResponse("Error: Command must be a non-empty string.", 1, false);
        }

        int effectiveTimeout = timeoutSeconds != null ? timeoutSeconds : defaultTimeout;
        if (effectiveTimeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + effectiveTimeout);
        }

        try {
            Path workDir = resolveExecuteCwd(runtimeContext);
            String osName = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb =
                    (osName.contains("win")
                                    ? new ProcessBuilder("cmd.exe", "/c", command)
                                    : new ProcessBuilder("sh", "-c", command))
                            .directory(workDir.toFile())
                            .redirectErrorStream(false);

            if (!env.isEmpty()) {
                pb.environment().clear();
                pb.environment().putAll(env);
            }

            Process proc = pb.start();

            // stdout/stderr 必须与 waitFor 并发排空：若子进程写入的数据
            // 超过操作系统管道缓冲区（Windows 约 4 KB，Linux 默认 64 KB），而
            // 父进程阻塞在 waitFor 中，双方就会死锁，导致这类命令
            // 被误报为超时（退出码 124）。
            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
            Thread stdoutDrainer = drainAsync(proc.getInputStream(), stdoutBuf);
            Thread stderrDrainer = drainAsync(proc.getErrorStream(), stderrBuf);

            // 【学习提示】排空输出使用后台线程，但当前调用仍阻塞在 waitFor 和后续 join。
            // 此方法是同步 API；接入响应式流程时，应由调用层选择合适的阻塞任务调度器。
            boolean finished = proc.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
            }
            joinQuietly(stdoutDrainer);
            joinQuietly(stderrDrainer);

            Charset outputCharset = outputCharset(osName);
            String stdout = stdoutBuf.toString(outputCharset);
            String stderr = stderrBuf.toString(outputCharset);

            if (!finished) {
                String msg;
                if (timeoutSeconds != null) {
                    msg =
                            "Error: Command timed out after "
                                    + effectiveTimeout
                                    + " seconds (custom timeout). The command may be stuck or"
                                    + " require more time.";
                } else {
                    msg =
                            "Error: Command timed out after "
                                    + effectiveTimeout
                                    + " seconds. For long-running commands, re-run using the"
                                    + " timeout parameter.";
                }
                return new ExecuteResponse(msg, 124, false);
            }

            StringBuilder output = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) {
                output.append(stdout);
            }
            if (stderr != null && !stderr.isBlank()) {
                String[] stderrLines = stderr.strip().split("\n");
                for (String line : stderrLines) {
                    if (!output.isEmpty()) {
                        output.append('\n');
                    }
                    output.append("[stderr] ").append(line);
                }
            }

            String outputStr = output.isEmpty() ? "<no output>" : output.toString();

            boolean truncated = false;
            if (outputStr.length() > maxOutputBytes) {
                outputStr =
                        outputStr.substring(0, maxOutputBytes)
                                + "\n\n... Output truncated at "
                                + maxOutputBytes
                                + " bytes.";
                truncated = true;
            }

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                outputStr = outputStr.stripTrailing() + "\n\nExit code: " + exitCode;
            }

            return new ExecuteResponse(outputStr, exitCode, truncated);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Command execution failed: {}", e.getMessage(), e);
            return new ExecuteResponse(
                    "Error executing command ("
                            + e.getClass().getSimpleName()
                            + "): "
                            + e.getMessage(),
                    1,
                    false);
        }
    }

    private Path resolveExecuteCwd(RuntimeContext rc) {
        if (shellCwd != null) {
            return shellCwd;
        }
        NamespaceFactory nsf = getNamespaceFactory();
        if (nsf == null) {
            return getCwd();
        }
        List<String> ns = nsf.getNamespace(rc);
        if (ns == null || ns.isEmpty()) {
            return getCwd();
        }
        Path namespaced = getCwd();
        for (String segment : ns) {
            namespaced = namespaced.resolve(segment);
        }
        try {
            Files.createDirectories(namespaced);
        } catch (IOException e) {
            log.warn("Failed to create namespace directory {}: {}", namespaced, e.getMessage());
        }
        return namespaced;
    }

    /**
     * 在守护线程上持续把子进程流复制到 {@code buf}，防止子进程因操作系统管道缓冲区已满而阻塞。
     * 读取错误（例如超时销毁进程时关闭流）会使排空线程安静结束。
     */
    private static Thread drainAsync(InputStream in, ByteArrayOutputStream buf) {
        Thread t =
                new Thread(
                        () -> {
                            byte[] chunk = new byte[DRAIN_CHUNK_BYTES];
                            int n;
                            try {
                                while ((n = in.read(chunk)) != -1) {
                                    buf.write(chunk, 0, n);
                                }
                            } catch (IOException ignored) {
                                // 进程被销毁导致流关闭，无需处理。
                            }
                        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(DRAIN_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Charset outputCharset(String osName) {
        return outputCharset(osName, System.getProperty("native.encoding"));
    }

    static Charset outputCharset(String osName, String nativeEncoding) {
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return StandardCharsets.UTF_8;
        }

        if (nativeEncoding != null && Charset.isSupported(nativeEncoding)) {
            return Charset.forName(nativeEncoding);
        }
        return Charset.defaultCharset();
    }
}
