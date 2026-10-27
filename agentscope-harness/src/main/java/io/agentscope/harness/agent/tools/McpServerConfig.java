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
package io.agentscope.harness.agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 位于 {@code mcpServers.<name>} 下的单个 MCP 服务器配置项，保存在 {@code tools.json} 中。
 *
 * <p>采用标准 MCP 客户端配置结构，使工作区可在 AgentScope 与其他支持 MCP 的工具之间迁移，
 * 只需极少修改。字段语义直接映射到 {@link io.agentscope.core.tool.mcp.McpClientBuilder}。
 *
 * <p>{@code transport} 用于区分传输方式：
 * <ul>
 * <li>{@code stdio}：使用 {@link #command}、{@link #args} 和 {@link #env}。</li>
 * <li>{@code sse}：使用 {@link #url}、{@link #headers} 和 {@link #queryParams}。</li>
 * <li>{@code http}：可流式 HTTP，使用与 {@code sse} 相同的字段。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig {

    /** 传输类型：{@code stdio}、{@code sse} 或 {@code http}。 */
    @JsonProperty("transport")
    private String transport;

    /** stdio：可执行命令。 */
    @JsonProperty("command")
    private String command;

    /** stdio：命令参数。 */
    @JsonProperty("args")
    private List<String> args;

    /** stdio：传给新启动进程的环境变量。 */
    @JsonProperty("env")
    private Map<String, String> env;

    /** sse / http：服务器 URL。 */
    @JsonProperty("url")
    private String url;

    /** sse / http：每次请求附带的 HTTP 请求头。 */
    @JsonProperty("headers")
    private Map<String, String> headers;

    /** sse / http：合并到请求 URL 中的查询参数。 */
    @JsonProperty("queryParams")
    private Map<String, String> queryParams;

    /** 可选的服务器工具导入允许列表。为 {@code null} 或空时，注册该服务器公布的全部工具。 */
    @JsonProperty("enableTools")
    private List<String> enableTools;

    /** 采用 ISO-8601 时长格式的单请求超时；{@code null} 保留构建器默认值。 */
    @JsonProperty("timeout")
    private Duration timeout;

    /** 采用 ISO-8601 时长格式的客户端初始化超时；{@code null} 保留默认值。 */
    @JsonProperty("initializationTimeout")
    private Duration initializationTimeout;

    private boolean defaultToolsEnabled = true;
    private List<String> disableTools;
    private boolean prefixToolNames;
    private boolean required;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.function.Consumer<McpConnectionException> connectionFailureHandler;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.util.function.Consumer<McpConnectionException> getConnectionFailureHandler() {
        return connectionFailureHandler;
    }

    public void setConnectionFailureHandler(
            java.util.function.Consumer<McpConnectionException> handler) {
        connectionFailureHandler = handler;
    }

    public boolean isDefaultToolsEnabled() {
        return defaultToolsEnabled;
    }

    public void setDefaultToolsEnabled(boolean value) {
        defaultToolsEnabled = value;
    }

    public List<String> getDisableTools() {
        return disableTools;
    }

    public void setDisableTools(List<String> value) {
        disableTools = value;
    }

    public boolean isPrefixToolNames() {
        return prefixToolNames;
    }

    public void setPrefixToolNames(boolean value) {
        prefixToolNames = value;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean value) {
        required = value;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, String> queryParams) {
        this.queryParams = queryParams;
    }

    public List<String> getEnableTools() {
        return enableTools;
    }

    public void setEnableTools(List<String> enableTools) {
        this.enableTools = enableTools;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getInitializationTimeout() {
        return initializationTimeout;
    }

    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }
}
