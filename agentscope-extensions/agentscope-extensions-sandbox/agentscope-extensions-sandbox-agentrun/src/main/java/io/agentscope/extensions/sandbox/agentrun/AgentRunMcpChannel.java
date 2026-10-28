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
package io.agentscope.extensions.sandbox.agentrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 AgentScope MCP 客户端的 AgentRun 沙箱执行通道。
 *
 * <p>复用 {@link McpClientBuilder#streamableHttpTransport(String)}，附加 AgentRun API 密钥请求头，并暴露沙箱模板为 AgentScope 启用的三个工具名：{@code process_exec_cmd}、{@code read_file}、{@code write_file}。
 */
final class AgentRunMcpChannel implements AutoCloseable {

    /**
     * 执行 shell 风格命令的 MCP 工具名。
     */
    static final String TOOL_EXEC = "process_exec_cmd";

    /**
     * 从沙箱文件系统读取文件的 MCP 工具名。
     */
    static final String TOOL_READ_FILE = "read_file";

    /**
     * 向沙箱文件系统写入文件的 MCP 工具名。
     */
    static final String TOOL_WRITE_FILE = "write_file";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRunSandboxClientOptions opt;
    private final String url;
    private volatile McpClientWrapper client;

    AgentRunMcpChannel(AgentRunSandboxClientOptions opt) {
        this.opt = Objects.requireNonNull(opt, "opt");
        this.url = resolveUrl(opt);
    }

    /**
     * 连接 MCP 客户端。此操作幂等，重复调用不执行额外操作。
     */
    void connect() {
        if (client != null) {
            return;
        }
        McpClientWrapper c =
                McpClientBuilder.create("agentrun-" + opt.getTemplateName())
                        .streamableHttpTransport(url)
                        .header("X-API-Key", requireApiKey())
                        .header("X-Acs-Parent-Id", nullToEmpty(opt.getAccountId()))
                        .timeout(Duration.ofSeconds(Math.max(60, opt.getReadTimeoutSeconds())))
                        .protocolVersions("2024-11-05", "2025-03-26")
                        .buildSync();
        c.initialize().block();
        this.client = c;
    }

    /**
     * 在沙箱内执行 shell 命令的结果。
     */
    static final class ExecResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        ExecResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
        }
    }

    /**
     * 通过 AgentRun 的 {@code process_exec_cmd} MCP 工具运行 {@code command}。
     */
    ExecResult exec(String command, String cwd, int timeoutSeconds) {
        ensureConnected();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("command", command);
        if (cwd != null && !cwd.isBlank()) {
            args.put("cwd", cwd);
        }
        args.put("timeout", Math.max(1, timeoutSeconds));
        McpSchema.CallToolResult result =
                client.callTool(TOOL_EXEC, args)
                        .block(Duration.ofSeconds(Math.max(5, timeoutSeconds) + 30L));
        if (result == null) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.EXEC_TIMEOUT, "AgentRun MCP exec returned null");
        }
        if (Boolean.TRUE.equals(result.isError())) {
            String msg = extractText(result);
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "AgentRun MCP " + TOOL_EXEC + " error: " + msg);
        }
        return parseExecPayload(extractText(result));
    }

    /**
     * 通过 AgentRun 的 {@code read_file} MCP 工具读取文件并返回文本内容。
     */
    String readFile(String absolutePath) {
        ensureConnected();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", absolutePath);
        McpSchema.CallToolResult result =
                client.callTool(TOOL_READ_FILE, args).block(Duration.ofSeconds(120));
        if (result == null || Boolean.TRUE.equals(result.isError())) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "AgentRun MCP "
                            + TOOL_READ_FILE
                            + " failed for "
                            + absolutePath
                            + ": "
                            + (result == null ? "null" : extractText(result)));
        }
        return extractText(result);
    }

    /**
     * 通过 AgentRun 的 {@code write_file} MCP 工具写入文件。
     */
    void writeFile(String absolutePath, String content) {
        ensureConnected();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", absolutePath);
        args.put("content", content != null ? content : "");
        McpSchema.CallToolResult result =
                client.callTool(TOOL_WRITE_FILE, args).block(Duration.ofSeconds(120));
        if (result == null || Boolean.TRUE.equals(result.isError())) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "AgentRun MCP "
                            + TOOL_WRITE_FILE
                            + " failed for "
                            + absolutePath
                            + ": "
                            + (result == null ? "null" : extractText(result)));
        }
    }

    /**
     * 返回此通道使用的 MCP 端点 URL。
     */
    String getUrl() {
        return url;
    }

    @Override
    public void close() {
        McpClientWrapper c = client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
                // 尽力清理
            }
            client = null;
        }
    }

    private void ensureConnected() {
        if (client == null) {
            connect();
        }
    }

    private String requireApiKey() {
        String key = opt.getApiKey();
        if (key == null || key.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun API key is required (set AgentRunSandboxClientOptions#setApiKey)");
        }
        return key;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String resolveUrl(AgentRunSandboxClientOptions opt) {
        String base = opt.getMcpServerUrl();
        if (base == null || base.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun MCP server URL is required (set #setMcpServerUrl)");
        }
        String endpoint = opt.getMcpEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return base;
        }
        // 将 base 视为主机根地址或已包含端点的地址。
        if (base.endsWith(endpoint) || base.contains(endpoint + "?")) {
            return base;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String tail = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return trimmed + tail;
    }

    private static String extractText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent t && t.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /**
     * 解析 AgentRun exec MCP 响应。AgentRun 返回 {@code {"exitCode":0,"stdout":"...","stderr":"..."}} 等 JSON 对象或纯 stdout/stderr 字符串。两种形态均接受，也支持嵌套格式 {@code {"result":{"exitCode":...}}} 及 JSON 载荷前带 Markdown 横幅的响应。
     */
    private static ExecResult parseExecPayload(String text) {
        if (text == null) {
            return new ExecResult(0, "", "");
        }
        String trimmed = text.strip();

        int braceIdx = trimmed.indexOf('{');
        if (braceIdx >= 0) {
            String jsonText = braceIdx == 0 ? trimmed : trimmed.substring(braceIdx);
            try {
                JsonNode node = JSON.readTree(jsonText);
                JsonNode result = node.path("result");
                JsonNode source = result.isObject() ? result : node;
                if (source.has("exitCode") || source.has("exit_code")) {
                    int exit =
                            source.has("exitCode")
                                    ? source.path("exitCode").asInt(0)
                                    : source.path("exit_code").asInt(0);
                    String stdout =
                            source.has("stdout")
                                    ? source.path("stdout").asText("")
                                    : source.path("output").asText("");
                    String stderr = source.path("stderr").asText("");
                    return new ExecResult(exit, stdout, stderr);
                }
            } catch (Exception ignore) {
                // 继续按纯文本处理
            }
        }
        return new ExecResult(0, text, "");
    }
}
