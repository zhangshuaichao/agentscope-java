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
package io.agentscope.core.tool.coding;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 带安全验证的 Shell 命令执行工具。
 *
 * <p>支持命令白名单、用户批准回调、多命令检测、超时（默认 300 秒）、平台特定验证，以及自定义输出解码字符集（默认 UTF-8）。
 *
 * <p><b>字符集支持：</b>可配置 stdout/stderr 输出流解码字符集，适用于非 UTF-8 系统（例如中文 Windows 的 GBK、GB2312）。可在构造时配置，也可逐命令覆盖。
 *
 * <p><b>安全警告：</b>{@code new ShellCommandTool()} 允许执行任意命令。生产环境务必使用白名单 {@code new ShellCommandTool(allowedCommands)}，或带回调的 {@code new ShellCommandTool(allowedCommands, approvalCallback)}。
 *
 * @see CommandValidator
 * @see UnixCommandValidator
 * @see WindowsCommandValidator
 */
public class ShellCommandTool implements AgentTool {

    private static final Logger logger = LoggerFactory.getLogger(ShellCommandTool.class);
    private static final int DEFAULT_TIMEOUT = 300;

    /**
     * 异步读取流的共享线程池，使用可缓存线程池动态伸缩，空闲超时 60 秒。守护线程不会阻止 JVM 关闭。
     */
    private static final ExecutorService STREAM_READER_POOL =
            Executors.newCachedThreadPool(
                    new ThreadFactory() {
                        private final AtomicInteger counter = new AtomicInteger(0);

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t =
                                    new Thread(
                                            r,
                                            "ShellCommand-StreamReader-"
                                                    + counter.incrementAndGet());
                            t.setDaemon(true); // 守护线程不会阻止 JVM 关闭
                            return t;
                        }
                    });

    private final Set<String> allowedCommands;
    private final Function<String, Boolean> approvalCallback;
    private final CommandValidator commandValidator;
    private final Path baseDir;

    /**
     * 用于解码命令输出流 stdout/stderr 的字符集，默认为 UTF-8。
     */
    private final Charset charset;

    public ShellCommandTool() {
        this(null, null, null, createDefaultValidator(), StandardCharsets.UTF_8);
    }

    public ShellCommandTool(Set<String> allowedCommands) {
        this(null, allowedCommands, null, createDefaultValidator(), StandardCharsets.UTF_8);
    }

    /**
     * 使用命令白名单和批准回调的构造器。
     *
     * @param allowedCommands 允许的可执行程序集合
     * @param approvalCallback 请求用户批准的回调
     */
    public ShellCommandTool(
            Set<String> allowedCommands, Function<String, Boolean> approvalCallback) {
        this(
                null,
                allowedCommands,
                approvalCallback,
                createDefaultValidator(),
                StandardCharsets.UTF_8);
    }

    /**
     * 使用基目录、命令白名单和批准回调的构造器。
     *
     * @param baseDir 命令执行基目录（null 表示当前目录）
     * @param allowedCommands 允许的可执行程序集合
     * @param approvalCallback 请求用户批准的回调
     */
    public ShellCommandTool(
            String baseDir,
            Set<String> allowedCommands,
            Function<String, Boolean> approvalCallback) {
        this(
                baseDir,
                allowedCommands,
                approvalCallback,
                createDefaultValidator(),
                StandardCharsets.UTF_8);
    }

    /**
     * 使用命令白名单、批准回调和自定义验证器的构造器。
     *
     * @param allowedCommands 允许的可执行程序集合（null 表示全部允许）
     * @param approvalCallback 请求用户批准的回调
     * @param commandValidator 自定义命令验证器
     */
    public ShellCommandTool(
            Set<String> allowedCommands,
            Function<String, Boolean> approvalCallback,
            CommandValidator commandValidator) {
        this(null, allowedCommands, approvalCallback, commandValidator, StandardCharsets.UTF_8);
    }

    /**
     * 使用基目录、命令白名单、批准回调和自定义验证器的构造器。
     *
     * <p>默认使用 UTF-8 解码命令输出。
     *
     * @param baseDir 命令执行基目录（null 表示当前目录）
     * @param allowedCommands 允许的可执行程序集合（null 表示全部允许）
     * @param approvalCallback 请求用户批准的回调
     * @param commandValidator 自定义命令验证器
     */
    public ShellCommandTool(
            String baseDir,
            Set<String> allowedCommands,
            Function<String, Boolean> approvalCallback,
            CommandValidator commandValidator) {
        this(baseDir, allowedCommands, approvalCallback, commandValidator, StandardCharsets.UTF_8);
    }

    /**
     * 包含字符集在内全部配置选项的完整构造器。
     *
     * <p>可设置所有可用选项，其他构造器均以默认值委托给它。
     *
     * @param baseDir 命令执行基目录（null 表示当前目录）
     * @param allowedCommands 允许的可执行程序集合（null 表示全部允许）
     * @param approvalCallback 请求用户批准的回调
     * @param commandValidator 自定义命令验证器（null 表示平台默认值）
     * @param charset 输出流解码字符集（null 表示 UTF-8）
     */
    public ShellCommandTool(
            String baseDir,
            Set<String> allowedCommands,
            Function<String, Boolean> approvalCallback,
            CommandValidator commandValidator,
            Charset charset) {
        // 使用 ConcurrentHashMap.newKeySet() 支持线程安全且高性能的并发访问
        // 进行防御性复制，防止外部修改
        if (allowedCommands != null && !allowedCommands.isEmpty()) {
            this.allowedCommands = ConcurrentHashMap.newKeySet(allowedCommands.size());
            this.allowedCommands.addAll(allowedCommands);
        } else {
            this.allowedCommands = ConcurrentHashMap.newKeySet();
        }
        this.approvalCallback = approvalCallback;
        this.commandValidator =
                commandValidator != null ? commandValidator : createDefaultValidator();
        this.baseDir = baseDir != null ? Paths.get(baseDir).toAbsolutePath().normalize() : null;
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;

        if (this.baseDir != null) {
            logger.info("ShellCommandTool initialized with base directory: {}", this.baseDir);
        }
        logger.debug("ShellCommandTool initialized with charset: {}", this.charset.name());
    }

    /**
     * 根据操作系统创建默认命令验证器。
     *
     * @return 平台特定命令验证器
     */
    private static CommandValidator createDefaultValidator() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new WindowsCommandValidator();
        } else {
            return new UnixCommandValidator();
        }
    }

    // =============================== 允许命令管理 ===============================

    /**
     * 获取允许命令的不可修改视图。返回集合可线程安全地读取，但不能直接修改。使用 {@link #addAllowedCommand(String)}、{@link #removeAllowedCommand(String)} 或 {@link #clearAllowedCommands()} 修改白名单。
     *
     * @return 允许的可执行程序集合的不可修改视图
     */
    public Set<String> getAllowedCommands() {
        return Collections.unmodifiableSet(allowedCommands);
    }

    /**
     * 以线程安全方式向白名单添加命令。
     *
     * @param command 要添加的可执行程序
     * @return 添加成功为 true，已存在为 false
     */
    public boolean addAllowedCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("Command cannot be null or empty");
        }
        boolean added = allowedCommands.add(command);
        if (added) {
            logger.debug("Added command to whitelist: {}", command);
        }
        return added;
    }

    /**
     * 以线程安全方式从白名单移除命令。
     *
     * @param command 要移除的可执行程序
     * @return 移除成功为 true，不存在为 false
     */
    public boolean removeAllowedCommand(String command) {
        boolean removed = allowedCommands.remove(command);
        if (removed) {
            logger.debug("Removed command from whitelist: {}", command);
        }
        return removed;
    }

    /**
     * 以线程安全方式清空白名单。
     */
    public void clearAllowedCommands() {
        allowedCommands.clear();
        logger.debug("Cleared all commands from whitelist");
    }

    /**
     * 检查命令是否在白名单中。
     *
     * @param command 待检查可执行程序
     * @return 在白名单中为 true
     */
    public boolean isCommandAllowed(String command) {
        return allowedCommands.contains(command);
    }

    /**
     * 获取批准回调，即用于请求用户批准非白名单命令的回调。
     *
     * <p>可用于克隆具有相同配置的 ShellCommandTool。
     *
     * @return 批准回调；未配置时为 null
     */
    public Function<String, Boolean> getApprovalCallback() {
        return approvalCallback;
    }

    /**
     * 获取用于命令安全验证的验证器。
     *
     * <p>可用于克隆具有相同配置的 ShellCommandTool。
     *
     * @return 命令验证器实例
     */
    public CommandValidator getCommandValidator() {
        return commandValidator;
    }

    /**
     * 获取命令执行基目录，即命令实际执行的目录。
     *
     * <p>可用于以不同基目录克隆 ShellCommandTool。
     *
     * @return 基目录路径；使用当前目录时为 null
     */
    public Path getBaseDir() {
        return baseDir;
    }

    /**
     * 获取命令输出流的解码字符集，即 stdout 和 stderr 的解码方式。
     *
     * <p>可用于克隆具有相同配置的 ShellCommandTool，或检查当前字符集设置。
     *
     * @return 输出解码字符集（永不为 null）
     */
    public Charset getCharset() {
        return charset;
    }

    // ========================= AgentTool 接口实现 =========================

    @Override
    public String getName() {
        return "execute_shell_command";
    }

    @Override
    public String getDescription() {
        StringBuilder desc = new StringBuilder();
        desc.append("Execute a shell command with security validation and return the result.");

        // 若配置基目录则添加其信息
        if (baseDir != null) {
            desc.append(" WORKING DIRECTORY: The command will be executed in the directory: ");
            desc.append(baseDir.toString());
            desc.append(
                    ". All relative paths in the command will be resolved from this directory.");
        }

        // 若配置白名单则添加其信息
        if (!allowedCommands.isEmpty()) {
            desc.append(" ALLOWED COMMANDS WHITELIST: [");
            String commandList =
                    new ArrayList<>(allowedCommands).stream().collect(Collectors.joining(", "));
            desc.append(commandList);
            desc.append("]. Only these commands can be executed directly.");
        } else {
            desc.append(" No whitelist configured - all commands require approval.");
        }

        desc.append(" Commands are validated against the whitelist (if configured).");
        desc.append(" Non-whitelisted commands require user approval via callback.");
        desc.append(
                " Multiple command separators (&, |, ;) are detected and blocked for security.");
        desc.append(" Returns output in format:");
        desc.append(" <returncode>code</returncode><stdout>output</stdout><stderr>error</stderr>.");
        desc.append(" If command is rejected, returncode will be -1 with SecurityError in stderr.");

        return desc.toString();
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "command",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "The shell command to execute"),
                                "timeout",
                                        Map.of(
                                                "type",
                                                "integer",
                                                "description",
                                                "The maximum time (in seconds) allowed for the"
                                                        + " command to run (default: 300)"),
                                "charset",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "The charset used to decode command output"
                                                        + " (default: "
                                                        + charset.name()
                                                        + "). Common values: UTF-8, GBK, GB2312,"
                                                        + " ISO-8859-1, etc.")),
                "required", List.of("command"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String command = (String) input.get("command");
        Integer timeout =
                input.containsKey("timeout") ? ((Number) input.get("timeout")).intValue() : null;
        String charsetName = (String) input.get("charset");
        Charset overrideCharset = null;
        if (charsetName != null && !charsetName.trim().isEmpty()) {
            try {
                overrideCharset = Charset.forName(charsetName.trim());
            } catch (IllegalArgumentException e) {
                logger.warn(
                        "Invalid charset name '{}', using default charset: {}",
                        charsetName,
                        charset.name());
            }
        }

        return executeShellCommand(command, timeout, overrideCharset);
    }

    // =============================== 执行 Shell 命令 ===============================

    /**
     * 执行 Shell 命令，在标签中返回退出码、标准输出和标准错误。
     *
     * <p>使用本工具实例的默认字符集。
     *
     * <p>安全特性：
     * <ul>
     * <li>白名单验证：仅白名单命令直接执行</li>
     * <li>多命令检测：防范命令链攻击（&amp;、|、;）</li>
     * <li>批准回调：为非白名单命令请求权限</li>
     * <li>平台特定验证：Windows 与 Unix/Linux/macOS 采用不同规则</li>
     * </ul>
     *
     * @param command 待执行 Shell 命令
     * @param timeout 最长运行时间，单位秒（默认 300）
     * @return 包含 returncode、stdout、stderr 格式化输出的 ToolResultBlock
     */
    public Mono<ToolResultBlock> executeShellCommand(String command, Integer timeout) {
        return executeShellCommand(command, timeout, null);
    }

    /**
     * 执行 Shell 命令，在标签中返回退出码、标准输出和标准错误。
     *
     * <p>安全特性：
     * <ul>
     * <li>白名单验证：仅白名单命令直接执行</li>
     * <li>多命令检测：防范命令链攻击（&amp;、|、;）</li>
     * <li>批准回调：为非白名单命令请求权限</li>
     * <li>平台特定验证：Windows 与 Unix/Linux/macOS 采用不同规则</li>
     * </ul>
     *
     * @param command 待执行 Shell 命令
     * @param timeout 最长运行时间，单位秒（默认 300）
     * @param overrideCharset 可选输出解码字符集（null 表示使用默认值）
     * @return 包含 returncode、stdout、stderr 格式化输出的 ToolResultBlock
     */
    public Mono<ToolResultBlock> executeShellCommand(
            String command, Integer timeout, Charset overrideCharset) {

        Charset effectiveCharset = overrideCharset != null ? overrideCharset : charset;
        int actualTimeout = timeout != null && timeout > 0 ? timeout : DEFAULT_TIMEOUT;
        logger.debug(
                "Executing shell command: '{}' with timeout: {} seconds, charset: {}",
                command,
                actualTimeout,
                effectiveCharset.name());

        // 执行前验证命令
        CommandValidator.ValidationResult validationResult =
                commandValidator.validate(command, allowedCommands);

        if (!validationResult.isAllowed()) {
            logger.info(
                    "Command '{}' validation failed: {}", command, validationResult.getReason());

            // 请求用户批准
            if (!requestUserApproval(command)) {
                String errorMsg =
                        approvalCallback == null
                                ? "SecurityError: "
                                        + validationResult.getReason()
                                        + " and no approval callback is configured."
                                : "SecurityError: Command execution was rejected by user. Reason: "
                                        + validationResult.getReason();
                logger.warn("Command '{}' execution rejected: {}", command, errorMsg);
                return Mono.just(formatResult(-1, "", errorMsg));
            }

            logger.info("Command '{}' approved by user, proceeding with execution", command);
        }

        return Mono.fromCallable(() -> executeCommand(command, actualTimeout, effectiveCharset))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(actualTimeout + 2))
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error executing command '{}': {}", command, e.getMessage(), e);
                            if (e instanceof TimeoutException) {
                                return Mono.just(
                                        formatResult(
                                                -1,
                                                "",
                                                String.format(
                                                        "TimeoutError: The command execution"
                                                                + " exceeded the timeout of %d"
                                                                + " seconds.",
                                                        actualTimeout)));
                            }
                            return Mono.just(formatResult(-1, "", "Error: " + e.getMessage()));
                        });
    }

    /**
     * 使用 ProcessBuilder 执行命令并捕获输出。
     *
     * @param command 待执行命令
     * @param timeoutSeconds 超时秒数
     * @param effectiveCharset 输出流解码字符集
     * @return 带格式化结果的 ToolResultBlock
     */
    private ToolResultBlock executeCommand(
            String command, int timeoutSeconds, Charset effectiveCharset) {
        ProcessBuilder processBuilder;

        // 根据操作系统确定 Shell
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            processBuilder = new ProcessBuilder("sh", "-c", command);
        }

        // 指定 baseDir 时设置工作目录
        if (baseDir != null) {
            processBuilder.directory(baseDir.toFile());
            logger.debug("Setting working directory to: {}", baseDir);
        }

        Process process = null;
        Future<String> stdoutFuture = null;
        Future<String> stderrFuture = null;

        try {
            long startTime = System.currentTimeMillis();
            logger.debug("Starting command execution: {}", command);

            // 启动进程
            process = processBuilder.start();

            // 关键修复：立即启动异步流读取器，防止管道缓冲区
            // 死锁。
            // 子进程写入数据超过管道缓冲区容量（通常
            // 为 4–64 KB）时，
            // 会阻塞并等待父进程读取。如果父进程也阻塞
            // 等待子进程，
            // 例如正在 waitFor() 中，就会产生死锁。通过线程池
            // 后台线程
            // 异步读取输出，持续排空管道缓冲区，
            // 即可避免这种死锁。
            stdoutFuture =
                    STREAM_READER_POOL.submit(
                            new StreamReader(process.getInputStream(), "stdout", effectiveCharset));
            stderrFuture =
                    STREAM_READER_POOL.submit(
                            new StreamReader(process.getErrorStream(), "stderr", effectiveCharset));

            // 带超时等待进程完成
            logger.debug("Waiting for process with timeout: {} seconds", timeoutSeconds);
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            long waitElapsed = System.currentTimeMillis() - startTime;
            logger.debug(
                    "process.waitFor returned: completed={}, elapsed={}ms", completed, waitElapsed);

            if (!completed) {
                // 发生超时
                logger.warn(
                        "Command '{}' exceeded timeout of {} seconds (actual wait: {}ms)",
                        command,
                        timeoutSeconds,
                        waitElapsed);

                // 终止进程
                process.destroyForcibly();

                // 以较短超时从异步读取器获取部分输出
                String stdout = getOutputWithTimeout(stdoutFuture, 1, TimeUnit.SECONDS);
                String stderr = getOutputWithTimeout(stderrFuture, 1, TimeUnit.SECONDS);

                String timeoutMessage =
                        String.format(
                                "TimeoutError: The command execution exceeded the timeout of %d"
                                        + " seconds.",
                                timeoutSeconds);

                // 向 stderr 追加超时消息
                if (stderr != null && !stderr.isEmpty()) {
                    stderr = stderr + "\n" + timeoutMessage;
                } else {
                    stderr = timeoutMessage;
                }

                return formatResult(-1, stdout != null ? stdout : "", stderr);
            }

            // 进程正常完成
            int returnCode = process.exitValue();

            // 从异步读取器获取完整输出
            // 进程已结束，读取器应很快完成
            String stdout = getOutputWithTimeout(stdoutFuture, 5, TimeUnit.SECONDS);
            String stderr = getOutputWithTimeout(stderrFuture, 5, TimeUnit.SECONDS);

            logger.debug("Command '{}' completed with return code: {}", command, returnCode);

            return formatResult(
                    returnCode, stdout != null ? stdout : "", stderr != null ? stderr : "");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Command execution was interrupted: {}", command, e);

            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }

            return formatResult(-1, "", "Error: Command execution was interrupted");

        } catch (IOException e) {
            logger.error(
                    "IOException while executing command '{}': {}", command, e.getMessage(), e);
            return formatResult(-1, "", "Error: " + e.getMessage());

        } finally {
            // 清理进程资源
            if (process != null && process.isAlive()) {
                // 若进程仍存活则销毁
                // 注意：StreamReader 的 try-with-resources 已关闭流
                process.destroyForcibly();
            }
        }
    }

    /**
     * 带超时从 Future 获取输出。
     *
     * <p>安全获取异步流读取器输出，处理超时和错误。Future 超时或失败时将其取消，并返回空字符串。
     *
     * @param future 包含输出字符串的 Future
     * @param timeout 超时值
     * @param unit 超时单位
     * @return 输出字符串；超时或错误时为空字符串
     */
    private String getOutputWithTimeout(Future<String> future, long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            logger.warn("Timeout waiting for stream reader to complete");
            future.cancel(true); // 取消任务
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for stream reader");
            future.cancel(true); // 取消任务
            return "";
        } catch (ExecutionException e) {
            logger.error("Error in stream reader: {}", e.getCause().getMessage(), e.getCause());
            return "";
        }
    }

    /**
     * 用 XML 风格标签格式化执行结果。
     *
     * @param returnCode 进程退出码
     * @param stdout 标准输出
     * @param stderr 标准错误
     * @return 带格式化结果的 ToolResultBlock
     */
    private ToolResultBlock formatResult(int returnCode, String stdout, String stderr) {
        String formattedOutput =
                String.format(
                        "<returncode>%d</returncode><stdout>%s</stdout><stderr>%s</stderr>",
                        returnCode, stdout, stderr);

        return ToolResultBlock.of(TextBlock.builder().text(formattedOutput).build());
    }

    /**
     * 通过回调请求用户批准命令执行。
     *
     * @param command 待批准命令
     * @return 获准为 true，否则为 false
     */
    private boolean requestUserApproval(String command) {
        if (approvalCallback == null) {
            logger.warn("No approval callback configured, rejecting command: {}", command);
            return false;
        }

        try {
            Boolean approved = approvalCallback.apply(command);
            if (approved != null && approved) {
                logger.info("User approved command execution: {}", command);
                return true;
            } else {
                logger.info("User rejected command execution: {}", command);
                return false;
            }
        } catch (Exception e) {
            logger.error(
                    "Error during approval callback for command '{}': {}",
                    command,
                    e.getMessage(),
                    e);
            return false;
        }
    }

    /**
     * 异步读取进程输出流的 Callable 任务，通过持续排空 stdout/stderr 防止管道缓冲区死锁。
     */
    private static class StreamReader implements Callable<String> {
        private final InputStream inputStream;
        private final String streamType;
        private final Charset charset;

        /**
         * 使用指定字符集创建 StreamReader。
         *
         * @param inputStream 待读取输入流
         * @param streamType 日志使用的描述性名称（例如 "stdout"、"stderr"）
         * @param charset 流解码字符集
         */
        StreamReader(InputStream inputStream, String streamType, Charset charset) {
            this.inputStream = inputStream;
            this.streamType = streamType;
            this.charset = charset;
        }

        @Override
        public String call() throws Exception {
            if (inputStream == null) {
                return "";
            }

            logger.debug("StreamReader [{}] started with charset {}", streamType, charset.name());
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(inputStream, charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append("\n");
                    }
                    output.append(line);
                }
            } catch (IOException e) {
                logger.error("Error reading {} stream: {}", streamType, e.getMessage(), e);
                throw e;
            }
            logger.debug("StreamReader [{}] completed, read {} bytes", streamType, output.length());
            return output.toString();
        }
    }
}
