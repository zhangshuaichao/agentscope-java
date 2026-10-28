/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.agui.common;

import io.agentscope.core.tool.ToolMergeMode;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AG-UI 集成的配置属性。
 *
 * <p>可在 application.yml 或 application.properties 中配置：
 *
 * <pre>
 * agentscope:
 *   agui:
 *     path-prefix: /agui
 *     cors-enabled: true
 *     cors-allowed-origins:
 *       - "*"
 *     run-timeout: 10m
 *     default-tool-merge-mode: MERGE_EXTERNAL_PRIORITY
 *     default-agent-id: default
 *     agent-id-header: X-Agent-Id
 *     enable-path-routing: true
 *     enable-reasoning: false
 *     emit-token-usage: false
 *     emit-run-finished-after-error: false
 *     interrupt-on-disconnect: true
 * </pre>
 */
@ConfigurationProperties(prefix = "agentscope.agui")
public class AguiProperties {

    /** AG-UI 端点的路径前缀。 */
    private String pathPrefix = "/agui";

    /** 是否启用 CORS。 */
    private boolean corsEnabled = true;

    /** CORS 允许的来源。 */
    private List<String> corsAllowedOrigins = List.of("*");

    /** 智能体运行超时时间。 */
    private Duration runTimeout = Duration.ofMinutes(10);

    /** 默认工具合并模式。 */
    private ToolMergeMode defaultToolMergeMode = ToolMergeMode.MERGE_EXTERNAL_PRIORITY;

    /** 是否发出状态事件。 */
    private boolean emitStateEvents = true;

    /** 是否发出工具调用参数事件。 */
    private boolean emitToolCallArgs = true;

    /** 是否发出 token 用量事件。 */
    private boolean emitTokenUsage = false;

    /**
     * 是否启用推理／思考内容输出。
     *
     * <p>启用后，ThinkingBlock 内容按 AG-UI Reasoning 草案转换为 REASONING_* 事件。默认为 false，以保持向后兼容并默认不暴露思考内容；这不替代应用自身的隐私合规措施。
     */
    private boolean enableReasoning = false;

    /**
     * 是否在 {@code RUN_ERROR} 后发出 {@code RUN_FINISHED}。
     *
     * <p>默认为 {@code false}（标准 AG-UI 行为）。旧客户端若期望错误后仍收到结束事件，可设为 {@code true}。
     */
    private boolean emitRunFinishedAfterError = false;

    /** 请求未指定智能体时使用的默认 ID。 */
    private String defaultAgentId = "default";

    /** 是否按 threadId 在后端管理对话记忆。启用后，存储按线程会话维护智能体实例，从而跨请求保留对话历史。 */
    private boolean serverSideMemory = false;

    /** 保留在内存中的最大线程会话数；仅在 serverSideMemory 开启时使用。 */
    private int maxThreadSessions = 1000;

    /** 会话空闲超时，单位为分钟。超过此时长未活动的会话会被移除；0 表示不过期。仅在 serverSideMemory 开启时使用。 */
    private int sessionTimeoutMinutes = 30;

    /** 读取智能体 ID 的 HTTP 请求头名称，客户端可通过此请求头传入 ID。 */
    private String agentIdHeader = "X-Agent-Id";

    /** 是否启用智能体 ID 的路径变量路由。启用后可请求 /agui/run/{agentId}。 */
    private boolean enablePathRouting = true;

    /** SSE emitter 的超时时间，单位为毫秒，用于限制 SSE 连接保持打开的时长。 */
    private long sseTimeout = 600000L;

    /** 客户端断开连接时是否中断智能体。 */
    private boolean interruptOnDisconnect = true;

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public boolean isCorsEnabled() {
        return corsEnabled;
    }

    public void setCorsEnabled(boolean corsEnabled) {
        this.corsEnabled = corsEnabled;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    public Duration getRunTimeout() {
        return runTimeout;
    }

    public void setRunTimeout(Duration runTimeout) {
        this.runTimeout = runTimeout;
    }

    public ToolMergeMode getDefaultToolMergeMode() {
        return defaultToolMergeMode;
    }

    public void setDefaultToolMergeMode(ToolMergeMode defaultToolMergeMode) {
        this.defaultToolMergeMode = defaultToolMergeMode;
    }

    public boolean isEmitStateEvents() {
        return emitStateEvents;
    }

    public void setEmitStateEvents(boolean emitStateEvents) {
        this.emitStateEvents = emitStateEvents;
    }

    public boolean isEmitToolCallArgs() {
        return emitToolCallArgs;
    }

    public void setEmitToolCallArgs(boolean emitToolCallArgs) {
        this.emitToolCallArgs = emitToolCallArgs;
    }

    public boolean isEmitTokenUsage() {
        return emitTokenUsage;
    }

    public void setEmitTokenUsage(boolean emitTokenUsage) {
        this.emitTokenUsage = emitTokenUsage;
    }

    public boolean isEnableReasoning() {
        return enableReasoning;
    }

    public void setEnableReasoning(boolean enableReasoning) {
        this.enableReasoning = enableReasoning;
    }

    public boolean isEmitRunFinishedAfterError() {
        return emitRunFinishedAfterError;
    }

    public void setEmitRunFinishedAfterError(boolean emitRunFinishedAfterError) {
        this.emitRunFinishedAfterError = emitRunFinishedAfterError;
    }

    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(String defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public boolean isServerSideMemory() {
        return serverSideMemory;
    }

    public void setServerSideMemory(boolean serverSideMemory) {
        this.serverSideMemory = serverSideMemory;
    }

    public int getMaxThreadSessions() {
        return maxThreadSessions;
    }

    public void setMaxThreadSessions(int maxThreadSessions) {
        this.maxThreadSessions = maxThreadSessions;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public String getAgentIdHeader() {
        return agentIdHeader;
    }

    public void setAgentIdHeader(String agentIdHeader) {
        this.agentIdHeader = agentIdHeader;
    }

    public boolean isEnablePathRouting() {
        return enablePathRouting;
    }

    public void setEnablePathRouting(boolean enablePathRouting) {
        this.enablePathRouting = enablePathRouting;
    }

    public long getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(long sseTimeout) {
        this.sseTimeout = sseTimeout;
    }

    public boolean isInterruptOnDisconnect() {
        return interruptOnDisconnect;
    }

    public void setInterruptOnDisconnect(boolean interruptOnDisconnect) {
        this.interruptOnDisconnect = interruptOnDisconnect;
    }
}
