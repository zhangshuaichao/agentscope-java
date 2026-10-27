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
package io.agentscope.core.tool.subagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * 将子智能体包装为支持多轮对话的 AgentTool 实现。
 *
 * <p>允许其他智能体将此智能体作为工具调用，通过会话管理支持多轮对话。每个会话维护自己的智能体实例和状态。
 *
 * <p>通过 {@link SubAgentProvider} 为每个新会话创建新的智能体实例，以保证线程安全。
 *
 * <p>工具暴露两个参数：
 * <ul>
 * <li>{@code session_id}：可选，省略时开启新会话，提供时继续已有会话。
 * <li>{@code message}：必填，发送给智能体的消息。
 * </ul>
 */
public class SubAgentTool implements AgentTool {

    private static final Logger logger = LoggerFactory.getLogger(SubAgentTool.class);

    /**
     * 会话 ID 的参数名称。
     */
    private static final String PARAM_SESSION_ID = "session_id";

    /**
     * 消息的参数名称。
     */
    private static final String PARAM_MESSAGE = "message";

    private final String name;
    private final String description;
    private final SubAgentProvider<?> agentProvider;
    private final SubAgentConfig config;

    /**
     * 创建 SubAgentTool。
     *
     * @param agentProvider 创建智能体实例的提供者
     * @param config 工具配置
     */
    public SubAgentTool(SubAgentProvider<?> agentProvider, SubAgentConfig config) {
        // 创建示例智能体以推导名称和描述。
        Agent sampleAgent = agentProvider.provide();

        this.agentProvider = agentProvider;
        this.config = config != null ? config : SubAgentConfig.defaults();
        this.name = resolveToolName(sampleAgent, this.config);
        this.description = resolveDescription(sampleAgent, this.config);

        logger.debug("Created SubAgentTool: name={}, description={}", name, description);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return buildSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return executeConversation(param);
    }

    /**
     * 与子智能体执行对话，并管理会话生命周期。
     *
     * <p>【学习提示】会话连续性来自状态存储，而不是复用同一个智能体对象：每次订阅都会调用提供者创建实例，续接时再加载状态。这里没有按会话 ID 加锁；新实例隔离并不等于同一会话的并发状态更新自动安全。
     *
     * <p>此方法处理：
     * <ul>
     * <li>为新对话生成 AgentStateStore ID。
     * <li>继续会话时加载智能体状态。
     * <li>执行消息（根据配置使用流式或非流式模式）。
     * <li>执行后持久化智能体状态。
     * </ul>
     *
     * @param param 包含输入和发射器的工具调用参数
     * @return 发出工具结果块的 Mono
     */
    private Mono<ToolResultBlock> executeConversation(ToolCallParam param) {
        return Mono.deferContextual(
                (ctxView) -> {
                    try {
                        Map<String, Object> input = param.getInput();

                        // 获取或创建会话 ID。
                        String sessionId = (String) input.get(PARAM_SESSION_ID);
                        boolean isNewSession = sessionId == null;
                        if (isNewSession) {
                            sessionId = UUID.randomUUID().toString();
                        }

                        // 获取消息。
                        String message = (String) input.get(PARAM_MESSAGE);
                        if (message == null || message.isEmpty()) {
                            return Mono.just(ToolResultBlock.error("Message is required"));
                        }

                        // 为此会话创建智能体。
                        final String finalSessionId = sessionId;
                        Agent agent = agentProvider.provide();

                        // 若继续已有会话，则加载已有状态。
                        if (!isNewSession) {
                            loadAgentState(finalSessionId, agent);
                        }

                        // 构建用户消息。
                        Msg userMsg =
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text(message).build())
                                        .build();
                        RuntimeContext runtimeContext = param.getRuntimeContext();

                        logger.debug(
                                "AgentStateStore {} with agent '{}': {}",
                                isNewSession ? "started" : "continued",
                                agent.getName(),
                                message.substring(0, Math.min(50, message.length())));

                        // 获取用于事件转发的发射器。
                        ToolEmitter emitter = param.getEmitter();

                        // 执行并在完成后保存状态。
                        Mono<ToolResultBlock> result;
                        if (config.isForwardEvents()) {
                            result =
                                    executeWithStreaming(
                                            agent,
                                            userMsg,
                                            finalSessionId,
                                            emitter,
                                            runtimeContext);
                        } else {
                            result =
                                    executeWithoutStreaming(
                                            agent, userMsg, finalSessionId, runtimeContext);
                        }

                        // 订阅被取消时中断子智能体
                        // （例如 ToolExecutor 超时后以新订阅触发重试）。
                        // 否则，失去调用方的智能体仍会消耗 LLM token、SSH
                        // 连接和线程池资源，直到自然结束。
                        //
                        // 使用 doFinally(CANCEL) 而不是 doOnCancel，因为 doOnCancel
                        // 也会在常规错误传播的清理阶段触发，从而
                        // 尝试中断已经失败的智能体。
                        //
                        // 使用 interrupt(RuntimeContext) 而不是已弃用的
                        // interrupt(InterruptSource)，因为后者只查找
                        // `defaultSessionId`，它可能不同于本次调用
                        // 实际运行的会话。
                        result =
                                result.doFinally(
                                        signal -> {
                                            if (signal == SignalType.CANCEL) {
                                                interruptAgent(agent, runtimeContext);
                                            }
                                        });

                        // 执行后保存状态。
                        return result.doOnSuccess(r -> saveAgentState(finalSessionId, agent));
                    } catch (Exception e) {
                        logger.error("Error in session setup: {}", e.getMessage(), e);
                        return Mono.just(
                                ToolResultBlock.error(
                                        "AgentStateStore setup failed: " + e.getMessage()));
                    }
                });
    }

    /**
     * 工具调用被取消时（例如超时触发重试）中断子智能体，防止其成为默默消耗资源的孤立智能体。
     */
    private void interruptAgent(Agent agent, RuntimeContext ctx) {
        if (agent instanceof ReActAgent ra) {
            ra.interrupt(ctx);
            logger.warn(
                    "Sub-agent '{}' (id={}) was interrupted because its tool call subscription "
                            + "was cancelled.",
                    ra.getName(),
                    ra.getAgentId());
        }
    }

    /**
     * 从 {@link SubAgentConfig#getStateStore()} 加载 {@code sessionId} 标识的对话所对应的子智能体状态，并合并到当前智能体的 {@link AgentState}。错误会记录日志，但不中断执行。
     */
    private void loadAgentState(String sessionId, Agent agent) {
        if (!(agent instanceof ReActAgent ra) || ra.sessionLogEnabled()) {
            return;
        }
        AgentStateStore subSession = config.getStateStore();
        if (subSession == null) {
            return;
        }
        try {
            subSession
                    .get(null, sessionId, "agent_state", AgentState.class)
                    .ifPresent(loaded -> applyLoadedState(ra, loaded));
            logger.debug("Loaded sub-agent state for session: {}", sessionId);
        } catch (Exception e) {
            logger.warn(
                    "Failed to load sub-agent state for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 将 {@code sessionId} 标识的对话的当前 {@link AgentState} 保存到 {@link SubAgentConfig#getStateStore()}。错误会记录日志，但不中断执行。
     */
    private void saveAgentState(String sessionId, Agent agent) {
        if (!(agent instanceof ReActAgent ra) || ra.sessionLogEnabled()) {
            return;
        }
        AgentStateStore subSession = config.getStateStore();
        if (subSession == null) {
            return;
        }
        try {
            subSession.save(null, sessionId, "agent_state", ra.getAgentState());
            logger.debug("Saved sub-agent state for session: {}", sessionId);
        } catch (Exception e) {
            logger.warn(
                    "Failed to save sub-agent state for session {}: {}", sessionId, e.getMessage());
        }
    }

    private static void applyLoadedState(ReActAgent agent, AgentState loaded) {
        AgentState live = agent.getAgentState();
        if (live == null) {
            return;
        }
        live.contextMutable().clear();
        live.contextMutable().addAll(loaded.getContext());
        live.setSummary(loaded.getSummary());
        live.setReplyId(loaded.getReplyId());
        live.setCurIter(loaded.getCurIter());
        live.setShutdownInterrupted(loaded.isShutdownInterrupted());
        live.getToolContext().setActivatedGroups(loaded.getToolContext().getActivatedGroups());
    }

    /**
     * 以流式方式调用智能体，并向发射器转发事件。
     *
     * <p>使用智能体的流式 API，将每个事件作为 JSON 转发给指定发射器。从最后一个事件提取最终响应。
     *
     * @param agent 要执行的智能体
     * @param userMsg 要发送的用户消息
     * @param sessionId 构建结果时使用的会话 ID
     * @param emitter 接收转发事件的发射器
     * @return 发出工具结果块的 Mono
     */
    private Mono<ToolResultBlock> executeWithStreaming(
            Agent agent,
            Msg userMsg,
            String sessionId,
            ToolEmitter emitter,
            RuntimeContext runtimeContext) {

        StreamOptions streamOptions =
                config.getStreamOptions() != null
                        ? config.getStreamOptions()
                        : StreamOptions.defaults();

        return Mono.deferContextual(
                ctxView ->
                        streamWithContext(agent, userMsg, streamOptions, runtimeContext)
                                .doOnNext(event -> forwardEvent(event, emitter, agent, sessionId))
                                .filter(Event::isLast)
                                .last()
                                .map(
                                        lastEvent -> {
                                            Msg response = lastEvent.getMessage();
                                            return buildResult(response, sessionId);
                                        })
                                .contextWrite(context -> context.putAll(ctxView))
                                .onErrorResume(
                                        e -> {
                                            logger.error(
                                                    "Error in streaming execution:" + " {}",
                                                    e.getMessage(),
                                                    e);
                                            return Mono.just(
                                                    ToolResultBlock.error(
                                                            "Execution error: " + e.getMessage()));
                                        }));
    }

    /**
     * 以非流式方式调用智能体。
     *
     * <p>使用智能体的标准调用 API，不向发射器转发事件。
     *
     * @param agent 要执行的智能体
     * @param userMsg 要发送的用户消息
     * @param sessionId 构建结果时使用的会话 ID
     * @return 发出工具结果块的 Mono
     */
    private Mono<ToolResultBlock> executeWithoutStreaming(
            Agent agent, Msg userMsg, String sessionId, RuntimeContext runtimeContext) {

        return Mono.deferContextual(
                ctxView ->
                        callWithContext(agent, userMsg, runtimeContext)
                                .map(response -> buildResult(response, sessionId))
                                .onErrorResume(
                                        e -> {
                                            logger.error(
                                                    "Error in execution: {}", e.getMessage(), e);
                                            return Mono.just(
                                                    ToolResultBlock.error(
                                                            "Execution error: " + e.getMessage()));
                                        })
                                .contextWrite(context -> context.putAll(ctxView)));
    }

    private Flux<Event> streamWithContext(
            Agent agent, Msg userMsg, StreamOptions options, RuntimeContext runtimeContext) {
        if (runtimeContext != null && agent instanceof ReActAgent reActAgent) {
            return reActAgent.stream(List.of(userMsg), options, runtimeContext);
        }
        return agent.stream(List.of(userMsg), options);
    }

    private Mono<Msg> callWithContext(Agent agent, Msg userMsg, RuntimeContext runtimeContext) {
        if (runtimeContext != null && agent instanceof ReActAgent reActAgent) {
            return reActAgent.call(List.of(userMsg), runtimeContext);
        }
        return agent.call(List.of(userMsg));
    }

    /**
     * 将事件序列化为 JSON 并转发到发射器。
     *
     * <p>使用 JsonCodec 序列化事件，以文本块形式发出。序列化失败会记录日志，但不中断执行。
     *
     * @param event 要转发的事件
     * @param emitter 接收事件的发射器
     * @param agent 智能体
     * @param sessionId 当前会话 ID
     */
    private void forwardEvent(Event event, ToolEmitter emitter, Agent agent, String sessionId) {
        try {
            String json = JsonUtils.getJsonCodec().toJson(event);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("subagent_event", event == null ? "" : event);
            metadata.put("subagent_name", agent.getName() == null ? "" : agent.getName());
            metadata.put("subagent_id", agent.getAgentId() == null ? "" : agent.getAgentId());
            metadata.put("subagent_session_id", sessionId == null ? "" : sessionId);
            emitter.emit(
                    new ToolResultBlock(
                            null, null, List.of(TextBlock.builder().text(json).build()), metadata));
        } catch (Exception e) {
            logger.warn("Failed to serialize event to JSON: {}", e.getMessage());
        }
    }

    /**
     * 构建包含会话上下文的最终工具结果。
     *
     * <p>在格式化响应中包含会话 ID，使调用方能在后续调用中传入此 ID 继续对话。
     *
     * @param response 智能体的响应消息
     * @param sessionId 结果中包含的会话 ID
     * @return 包含格式化响应的工具结果块
     */
    private ToolResultBlock buildResult(Msg response, String sessionId) {
        String textContent = response.getTextContent();

        // 返回包含会话上下文的响应。
        return ToolResultBlock.text(
                String.format(
                        "session_id: %s\n\n%s",
                        sessionId, textContent != null ? textContent : "(No response)"));
    }

    /**
     * 构建工具参数的 JSON 模式。
     *
     * <p>创建包含两个属性的模式：
     * <ul>
     * <li>{@code session_id}：可选字符串，用于继续已有对话。
     * <li>{@code message}：必填字符串，包含要发送的消息。
     * </ul>
     *
     * @return 表示工具参数 JSON 模式的映射
     */
    private Map<String, Object> buildSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // AgentStateStore ID（可选）。
        Map<String, Object> sessionIdProp = new HashMap<>();
        sessionIdProp.put("type", "string");
        sessionIdProp.put(
                "description",
                "AgentStateStore ID for multi-turn dialogue. Omit to start a NEW session."
                        + " To CONTINUE an existing session and retain memory, you MUST extract"
                        + " the session_id from the previous response and pass it here.");
        properties.put(PARAM_SESSION_ID, sessionIdProp);

        // 消息（必填）。
        Map<String, Object> messageProp = new HashMap<>();
        messageProp.put("type", "string");
        messageProp.put("description", "Message to send to the agent");
        properties.put(PARAM_MESSAGE, messageProp);

        schema.put("properties", properties);
        schema.put("required", List.of(PARAM_MESSAGE));

        return schema;
    }

    /**
     * 从配置解析工具名称，或从智能体推导名称。
     *
     * <p>优先级：显式 config.toolName > 从智能体名称推导。
     * 若从智能体名称推导，会清理名称以满足严格的 LLM API 约束（例如 ^[a-zA-Z0-9_-]{1,64}$）。对于非英文字符（如中文）或过长名称，会附加原始名称的确定性短哈希，以避免命名冲突。
     *
     * @param agent 未配置时从中推导名称的智能体
     * @param config 可覆盖名称的配置
     * @return 解析出的工具名称
     */
    private String resolveToolName(Agent agent, SubAgentConfig config) {
        if (config.getToolName() != null && !config.getToolName().trim().isEmpty()) {
            return config.getToolName().trim();
        }

        if (agent.getName() == null || agent.getName().trim().isEmpty()) {
            return "call_agent";
        }

        return sanitizeName("call_", agent.getName().trim());
    }

    /**
     * {@link #resolveToolName(Agent, SubAgentConfig)} 的辅助方法。
     * 提取合法字符，必要时延迟计算确定性哈希，并通过安全截断严格限制长度。
     *
     * @param prefix 工具名称的前缀（例如 "call_"）。
     * @param originalName 智能体的原始名称。
     * @return 清理后的可安全使用的工具名称。
     */
    private String sanitizeName(String prefix, String originalName) {
        // 保留下划线，将其他非法字符统一替换为下划线，
        // 合并连续下划线，并移除首尾下划线。
        String lowerOriginal = originalName.toLowerCase(Locale.ROOT);
        String safePart =
                lowerOriginal
                        .replaceAll("[^a-z0-9_-]+", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_+|_+$", "");

        if (safePart.isEmpty()) {
            safePart = "agent";
        }

        String resolvedName = prefix + safePart;
        boolean isInformationLost = lowerOriginal.matches("^[a-z0-9_\\-\\s]+$");

        boolean needsHash = !isInformationLost || resolvedName.length() > 64;

        if (needsHash) {
            // 生成确定性哈希。
            UUID uuid = UUID.nameUUIDFromBytes(originalName.getBytes(StandardCharsets.UTF_8));
            String shortHash = uuid.toString().replace("-", "").substring(0, 8);
            String suffix = "_" + shortHash;

            logger.warn(
                    "Agent name '{}' contains unsupported characters or is too long. Appended hash"
                        + " '{}' to prevent collisions. Only alphanumeric characters, underscores,"
                        + " and hyphens are supported in generated names. Recommended to configure"
                        + " an explicit English 'toolName' via SubAgentConfig.",
                    originalName,
                    shortHash);

            resolvedName = prefix + safePart + suffix;

            if (resolvedName.length() > 64) {
                int allowedSafePartLen = 64 - prefix.length() - suffix.length();
                if (allowedSafePartLen > 0) {
                    // replaceAll("_+$", "") 移除截断产生的尾部下划线，
                    // 防止附加后缀时出现双下划线。
                    safePart = safePart.substring(0, allowedSafePartLen).replaceAll("_+$", "");
                    resolvedName = prefix + safePart + suffix;
                } else {
                    // 如果仅前缀加后缀就达到或超过 64 个字符，
                    // 则完全舍弃 safePart，强制截断前缀与哈希
                    // 的组合。
                    resolvedName =
                            (prefix + shortHash)
                                    .substring(
                                            0, Math.min(64, prefix.length() + shortHash.length()));
                }
            }
        }

        return resolvedName;
    }

    /**
     * 从配置解析工具描述，或从智能体推导描述。
     *
     * <p>优先级：config.description > agent.description > 默认值。默认描述生成为 "Call {agentName} to complete tasks"。
     *
     * @param agent 未配置时从中推导描述的智能体
     * @param config 可覆盖描述的配置
     * @return 解析出的描述
     */
    private String resolveDescription(Agent agent, SubAgentConfig config) {
        if (config.getDescription() != null && !config.getDescription().isEmpty()) {
            return config.getDescription();
        }
        // 若智能体描述可用，则使用它。
        String agentDesc = agent.getDescription();
        if (agentDesc != null && !agentDesc.isEmpty()) {
            return agentDesc;
        }
        // 生成默认描述。
        return "Call " + agent.getName() + " to complete tasks";
    }
}
