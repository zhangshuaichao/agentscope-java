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
package io.agentscope.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.ExecutionIdentity;
import io.agentscope.core.agent.RunControl;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.SubagentEventBus;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.agent.config.FailoverListener;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolProgressEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.LegacyHookDispatcher;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.interruption.InterruptControl;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.memory.AgentStateMemoryView;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.StaticLongTermMemoryHook;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareChain;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.middleware.TaskReminderMiddleware;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.observation.ActionObservationException;
import io.agentscope.core.observation.ActionObservations;
import io.agentscope.core.observation.ActionObserver;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.session.SessionExecution;
import io.agentscope.core.session.SessionExportSink;
import io.agentscope.core.session.SessionHistoryMode;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.session.SessionModels;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.session.SessionStateAccess;
import io.agentscope.core.shutdown.AgentShuttingDownException;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.SkillHook;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConcurrentSessionModificationException;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionCallback;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.RequirementTools;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.core.util.MessageUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

/**
 * ReAct（推理与行动）智能体实现。
 *
 * <p>ReAct 是将推理（思考与规划）和行动（工具执行）结合为迭代循环的智能体设计模式。
 * 智能体交替执行两个阶段，直到完成任务或达到最大迭代次数。
 *
 * <p><b>主要特性：</b>
 * <ul>
 * <li><b>响应式流：</b>使用 Project Reactor 实现非阻塞执行</li>
 * <li><b>钩子系统：</b>通过可扩展钩子监测、拦截执行</li>
 * <li><b>HITL 支持：</b>通过 PostReasoningEvent/PostActingEvent 的 stopAgent() 实现人在回路中</li>
 * <li><b>结构化输出：</b>单次调用的 {@code generate_response} 工具提供类型安全输出</li>
 * </ul>
 *
 * <p><b>用法示例：</b>
 * <pre>{@code
 * import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
 *
 * // 创建模型，需要 agentscope-extensions-model-dashscope 依赖
 * DashScopeChatModel model = DashScopeChatModel.builder()
 *     .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *     .modelName("qwen-plus")
 *     .build();
 *
 * // 创建并注册工具的工具集
 * Toolkit toolkit = new Toolkit();
 * toolkit.registerObject(new MyToolClass());
 *
 * // 构建智能体
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .sysPrompt("You are a helpful assistant.")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .maxIters(10)
 *     .build();
 *
 * // 使用智能体
 * Msg response = agent.call(Msg.builder()
 *     .name("user")
 *     .role(MsgRole.USER)
 *     .content(TextBlock.builder().text("What's the weather?").build())
 *     .build()).block();
 * }</pre>
 *
 * <p><b>并发：</b>共享 {@code (userId, sessionId)} 槽位的调用串行执行，不同槽位可并发执行。
 * 运行时上下文通过每次调用的执行作用域、中间件参数和工具参数传递，绝不经共享“当前上下文”访问器传递。
 * 自定义工具、钩子和中间件也必须支持安全并发使用。
 */
@SuppressWarnings("deprecation")
public class ReActAgent extends AgentBase implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);
    private static final GracefulShutdownManager shutdownManager =
            GracefulShutdownManager.getInstance();

    /**
     * 单次调用结构化输出工具 {@code generate_response} 使用的工具名称。
     */
    public static final String STRUCTURED_OUTPUT_TOOL_NAME = "generate_response";

    /**
     * @deprecated 权限 HITL 不再使用 Reactor Sink。确认结果现在通过第二次 {@code agent.call(msgs)} 调用携带
     * {@link ConfirmResult} 载荷传入，参见 {@code applyConfirmResults}。
     * 此常量保留为编译期标记，将在未来版本移除。
     */
    @Deprecated
    public static final String CONFIRM_SINK_KEY = "io.agentscope.core.ReActAgent.confirmSink";

    // ==================== 核心依赖 ====================

    private final String sysPrompt;
    private final Model model;
    private final int maxIters;
    private final ExecutionConfig modelExecutionConfig;
    private final ExecutionConfig toolExecutionConfig;
    private final GenerateOptions generateOptions;

    /**
     * 是否按请求的 JSON Schema 校验原生结构化输出响应。
     * 默认启用；{@link Builder#validateStructuredOutput(boolean)} 可恢复旧式“信任提供方”的行为。
     */
    private final boolean validateStructuredOutput;

    /**
     * 智能体拥有的工具集（在 {@code build()} 时深拷贝，每个智能体实例隔离）。
     * 在该智能体的并发调用之间共享；单次调用的结构化输出工具不注册在此，
     * 而是存于单次调用的 {@link CallExecution} 作用域。
     */
    private final Toolkit toolkit;

    /**
     * 构建器阶段工具集配置完成后捕获的默认活动工具组。
     */
    private final List<String> initialActiveToolGroups;

    /**
     * 构建器配置的技能视图基准 {@link SkillFilter}。绝不为 null，未设置时默认 {@link SkillFilter#all()}。
     * 技能中间件使用此基准，再叠加通过 {@link RuntimeContext} 提供的单次调用过滤器。
     */
    private final SkillFilter skillFilter;

    /**
     * 构建器配置的稳定技能实体化目录；未设置时为 {@code null}。
     */
    private final Path skillWorkDir;

    private final ToolExecutionContext toolExecutionContext;

    private final List<MiddlewareBase> middlewares;
    private final ModelRequestPreparer modelRequestPreparer;
    private final ActionObserver actionObserver;

    /**
     * 按扩展点分组的参与者，在构造时从 {@link #middlewares} 一次性分组（稳定过滤，保留洋葱顺序）。
     * 结果不可变，在并发调用间共享；之后修改 {@link MiddlewareBase#activePoints()} 不会生效。
     */
    private final Map<MiddlewareBase.ExtensionPoint, List<MiddlewareBase>> groupedMiddlewares;

    private final boolean enablePendingToolRecovery;

    // ==================== 持久化 ====================

    private final AgentStateStore stateStore;

    /**
     * {@code agent_state} 乐观并发保存冲突时使用的策略。
     * 默认为 {@link ConflictPolicy#OVERWRITE}（旧式后写覆盖）。
     */
    private final ConflictPolicy conflictPolicy;

    /**
     * 构建器阶段设置的回退 {@code sessionId}，仅当调用未通过 {@link RuntimeContext} 提供 {@code sessionId} 时使用。
     * 每次调用仍选择自己的活动槽位（见 {@link #activateSlotForContext(RuntimeContext)}）；
     * 这仅是 RC 未携带单次调用会话身份时的回退值。
     */
    private final String defaultSessionId;

    /**
     * 构建器阶段的权限模板，应用于每个新建槽位。可以为 null。
     */
    private final PermissionContextState initialPermissionContext;

    // ==================== 2.0 核心字段 ====================

    /**
     * 按 {@code (userId, sessionId)} 槽位键缓存状态。
     */
    private final ConcurrentHashMap<String, AgentState> stateCache = new ConcurrentHashMap<>();

    /**
     * 兼容 get/mutate/save API 的独立工作副本，绝不作为执行状态。
     */
    private final ConcurrentHashMap<String, SessionStateAccess.Snapshot> nativeStateCopies =
            new ConcurrentHashMap<>();

    /**
     * 每个槽位观测到的乐观并发版本（与 {@link #stateCache} 并行维护）。
     * 在加载及成功 CAS 保存时更新；不存在的条目表示 {@link AgentStateStore#UNVERSIONED}。
     */
    private final ConcurrentHashMap<String, Long> slotVersions = new ConcurrentHashMap<>();

    /**
     * agent_state 保存期间观测到的 CAS 冲突次数（用于指标／诊断）。
     */
    private final AtomicLong stateConflictCount = new AtomicLong();

    /**
     * 按槽位缓存权限引擎：运行时添加的 ASK 规则仅在所属槽位内累积，不会泄漏到其他用户／会话。
     */
    private final ConcurrentHashMap<String, PermissionEngine> permissionEngineCache =
            new ConcurrentHashMap<>();

    private final ModelConfig modelConfig;
    private final ReactConfig reactConfig;

    /**
     * 将 {@code streamEvents} 事件 sink 带入底层 {@code call()} 订阅的 Reactor Context 键。
     * 在 {@link #doCall(List)} 中读取，并绑定到为该订阅新建的 {@link CallExecution#eventSink}。
     * 因此同一智能体上的并发 {@code streamEvents} 调用分别携带自己的 sink，无共享实例字段，也无竞争。
     */
    private static final String EVENT_SINK_KEY = "io.agentscope.core.ReActAgent.eventSink";

    /**
     * 最终回复为空而重新进入推理时，注入的合成提醒。
     */
    private static final String EMPTY_RESPONSE_REMINDER_TEXT =
            "<system-reminder>Your previous reply had empty content - the full answer was written"
                    + " to the reasoning channel only. Reply again and write the final answer into"
                    + " the content channel.</system-reminder>";

    @SuppressWarnings("deprecation")
    private final LegacyHookDispatcher hookDispatcher;

    // ==================== 构造方法 ====================

    /**
     * 只读打开：不获取所有权、不执行修复、不派发工作。
     */
    public SessionLog sessionLog(RuntimeContext context) {
        if (sessionLogStore == null)
            throw new IllegalStateException("Native session history is disabled");
        RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
        String sid = rc.getSessionId();
        if (sid == null || sid.isBlank()) sid = defaultSessionId;
        return sessionLogStore.open(new SessionKey(rc.getUserId(), sessionLogAgentId, sid), rc);
    }

    public SessionKey sessionKey(RuntimeContext context) {
        String sid = context == null ? null : context.getSessionId();
        if (sid == null || sid.isBlank()) sid = defaultSessionId;
        return new SessionKey(context == null ? null : context.getUserId(), sessionLogAgentId, sid);
    }

    public boolean sessionLogEnabled() {
        return sessionLogStore != null && sessionHistoryMode == SessionHistoryMode.EVENT_LOG;
    }

    private final SessionHistoryMode sessionHistoryMode;
    private final SessionLogStore sessionLogStore;
    private final String sessionLogAgentId;

    private ReActAgent(Builder builder, Toolkit agentToolkit) {
        super(builder.name, builder.description, new ArrayList<>(builder.hooks), builder.agentId);

        this.toolkit = agentToolkit != null ? agentToolkit : new Toolkit();
        this.initialActiveToolGroups = List.copyOf(this.toolkit.getActiveGroups());
        this.skillFilter = builder.skillFilter != null ? builder.skillFilter : SkillFilter.all();
        this.skillWorkDir = builder.skillWorkDir;
        this.sysPrompt = builder.sysPrompt;
        this.model = builder.model;
        this.maxIters = builder.maxIters;
        this.modelExecutionConfig = builder.modelExecutionConfig;
        this.toolExecutionConfig = builder.toolExecutionConfig;
        this.generateOptions = builder.generateOptions;
        this.validateStructuredOutput = builder.validateStructuredOutput;
        this.toolExecutionContext = builder.toolExecutionContext;
        this.enablePendingToolRecovery = builder.enablePendingToolRecovery;
        List<MiddlewareBase> mws = new ArrayList<>();
        mws.add(new GracefulShutdownMiddleware(shutdownManager));
        mws.addAll(builder.middlewares);
        this.middlewares = List.copyOf(mws);
        this.modelRequestPreparer = builder.modelRequestPreparer;
        this.actionObserver = builder.actionObserver;
        this.groupedMiddlewares = groupMiddlewares(this.middlewares);

        this.stateStore = builder.stateStore;
        this.sessionLogStore = builder.sessionLogStore;
        this.sessionHistoryMode = builder.sessionHistoryMode;
        this.sessionLogAgentId =
                builder.sessionLogAgentId == null ? getName() : builder.sessionLogAgentId;
        this.conflictPolicy =
                builder.conflictPolicy != null ? builder.conflictPolicy : ConflictPolicy.OVERWRITE;
        this.defaultSessionId =
                builder.defaultSessionId != null && !builder.defaultSessionId.isBlank()
                        ? builder.defaultSessionId
                        : (builder.name != null ? builder.name : "ReActAgent");
        this.initialPermissionContext = builder.permissionContext;

        this.modelConfig = assembleModelConfig(builder);
        this.reactConfig = assembleReactConfig(builder);
        this.hookDispatcher = new LegacyHookDispatcher(this);

        if (this.stateStore != null && !sessionLogEnabled()) {
            shutdownManager.bindStateSaver(
                    this,
                    // 保存器接收与被中断请求准确绑定的 (userId, sessionId) AgentState，
                    // 因此直接持久化该会话，而不是使用实例的
                    // “最近活跃” CallExecution（它在并发下不正确）。
                    // CAS 冲突说明会话已在别处被接管，记录日志并跳过
                    // 覆盖。
                    agentState -> {
                        String uid = agentState.getUserId();
                        String sid = agentState.getSessionId();
                        String slot = slotKey(uid, sid);
                        long expected =
                                slotVersions.getOrDefault(slot, AgentStateStore.UNVERSIONED);
                        long newVersion =
                                stateStore.saveIfVersion(
                                        uid, sid, "agent_state", agentState, expected);
                        if (newVersion == AgentStateStore.UNVERSIONED
                                && stateStore.supportsVersioning()
                                && expected != AgentStateStore.UNVERSIONED) {
                            stateConflictCount.incrementAndGet();
                            log.warn(
                                    "Shutdown state save skipped due to concurrent modification"
                                            + " (userId={}, sessionId={}, expectedVersion={})",
                                    uid,
                                    sid,
                                    expected);
                        } else if (newVersion != AgentStateStore.UNVERSIONED) {
                            slotVersions.put(slot, newVersion);
                        }
                    });
        }
    }

    /**
     * 内部槽位标识符：{@code (userId or "__anon__") + "/" + sessionId}。
     * 不属于公共 API。
     */
    private static String slotKey(String userId, String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return (userId == null || userId.isBlank() ? "__anon__" : userId) + "/" + sessionId;
    }

    /**
     * {@link #slotKey} 的逆操作：解析出的 {@code (userId, sessionId)} 对。
     */
    private record SlotRef(String userId, String sessionId) {
        static SlotRef parse(String slotKey) {
            int slash = slotKey.lastIndexOf('/');
            String u = slotKey.substring(0, slash);
            String s = slotKey.substring(slash + 1);
            return new SlotRef("__anon__".equals(u) ? null : u, s);
        }
    }

    /**
     * 加载独立的状态存储条目，或创建新状态。旧 v1 会话键需要显式迁移，正常激活时不会查询。
     *
     * @return 状态及其存储版本（支持版本的后端中不存在条目时为 {@code 0}，
     * 后端不支持版本时为 {@link AgentStateStore#UNVERSIONED}）
     */
    private static VersionedState<AgentState> loadOrCreateAgentStateForSlot(
            AgentStateStore stateStore,
            String userId,
            String sessionId,
            PermissionContextState permCtx,
            String agentId,
            List<String> initialActiveToolGroups) {
        AgentState fresh = freshState(permCtx, agentId, userId, sessionId, initialActiveToolGroups);
        if (stateStore == null) {
            return new VersionedState<>(fresh, AgentStateStore.UNVERSIONED);
        }
        VersionedState<AgentState> versioned =
                stateStore.getVersioned(userId, sessionId, "agent_state", AgentState.class);
        if (versioned.isPresent()) {
            return versioned;
        }
        long version = stateStore.supportsVersioning() ? 0L : AgentStateStore.UNVERSIONED;
        return new VersionedState<>(fresh, version);
    }

    private static AgentState freshState(
            PermissionContextState permCtx,
            String agentId,
            String userId,
            String sessionId,
            List<String> initialActiveToolGroups) {
        AgentState.Builder asb =
                AgentState.builder().sessionId(sessionId != null ? sessionId : agentId);
        if (userId != null) {
            asb.userId(userId);
        }
        if (permCtx != null) {
            asb.permissionContext(permCtx);
        }
        ToolContextState.Builder toolContext = ToolContextState.builder();
        if (initialActiveToolGroups != null) {
            initialActiveToolGroups.forEach(toolContext::addActivatedGroup);
        }
        asb.toolContext(toolContext.build());
        return asb.build();
    }

    /**
     * 通过配置的 {@link AgentStateStore} 持久化当前 {@link AgentState}；
     * 未提供 AgentStateStore 时返回 {@code Mono.empty()}。存储支持版本时使用乐观并发控制。
     */
    private Mono<Void> saveStateToSession(CallExecution scope) {
        SessionRecorder recorder = SessionRecorder.from(scope.rc);
        if (recorder != null) return recorder.checkpoint(scope.state, "state_save");
        return saveLegacyState(scope);
    }

    private Mono<Void> saveLegacyState(CallExecution scope) {
        if (stateStore == null) {
            return Mono.empty();
        }
        SlotRef ref = SlotRef.parse(scope.slotKey);
        AgentState toSave = scope.state;
        return Mono.<Void>fromRunnable(
                        () -> {
                            long newVersion =
                                    persistAgentStateCas(
                                            ref.userId,
                                            ref.sessionId,
                                            scope.slotKey,
                                            toSave,
                                            scope.loadedVersion,
                                            scope.loadedContextSize);
                            if (newVersion != AgentStateStore.UNVERSIONED) {
                                scope.loadedVersion = newVersion;
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 持久化失败调用之前已累积的安全会话状态，并重新抛出原始错误。
     * 不完整的模型片段仅保存在每次迭代的累积器中，因此有意不加入 {@link AgentState}，也不在此持久化。
     *
     * <p>有意跳过 {@link InterruptedException}：中断已由 {@link #handleInterrupt} 完整处理，
     * 该方法先协调推理过程中产生的悬空 tool_use（为待处理工具调用合成错误结果），再执行持久化。
     * 此处保存可能抢在协调完成前写入不一致的中间状态（tool_use 缺少匹配工具结果），
     * 所以允许中断原样向上传播。
     */
    private <T> Mono<T> saveStateAfterCallFailure(CallExecution scope, Throwable callFailure) {
        if (ExceptionUtils.containsInterruptedException(callFailure)) {
            return Mono.error(callFailure);
        }
        return saveStateToSession(scope)
                .onErrorResume(
                        saveFailure -> {
                            if (saveFailure != callFailure) {
                                callFailure.addSuppressed(saveFailure);
                            }
                            log.warn(
                                    "Failed to persist agent state after a call failure; preserving"
                                            + " the original failure",
                                    saveFailure);
                            return Mono.empty();
                        })
                .then(Mono.error(callFailure));
    }

    /**
     * 支持 CAS 的 {@code agent_state} 持久化，冲突时应用 {@link #conflictPolicy}。
     *
     * @return 新存储版本；后端不支持版本时为 {@link AgentStateStore#UNVERSIONED}
     */
    private long persistAgentStateCas(
            String userId,
            String sessionId,
            String slot,
            AgentState toSave,
            long expectedVersion,
            int loadedContextSize) {
        if (!stateStore.supportsVersioning() || expectedVersion == AgentStateStore.UNVERSIONED) {
            if (stateStore.supportsVersioning()) {
                // 通过版本化 API 无条件写入：存储返回本次写入
                // 分配的版本（JDBC #3220，内存实现亦然），因此
                // slotVersions 不会再在写入与独立读取版本之间
                // 误记并发写入者的版本。
                long written =
                        stateStore.saveIfVersion(
                                userId,
                                sessionId,
                                "agent_state",
                                toSave,
                                AgentStateStore.UNVERSIONED);
                if (written != AgentStateStore.UNVERSIONED) {
                    slotVersions.put(slot, written);
                    return written;
                }
                // 仅作防御：支持版本的后端若为无条件写入返回 UNVERSIONED，
                // 就违反了 AgentStateStore 契约。
                stateStore.save(userId, sessionId, "agent_state", toSave);
            } else {
                stateStore.save(userId, sessionId, "agent_state", toSave);
            }
            return AgentStateStore.UNVERSIONED;
        }

        long newVersion =
                stateStore.saveIfVersion(userId, sessionId, "agent_state", toSave, expectedVersion);
        if (newVersion != AgentStateStore.UNVERSIONED) {
            slotVersions.put(slot, newVersion);
            return newVersion;
        }

        stateConflictCount.incrementAndGet();
        switch (conflictPolicy) {
            case OVERWRITE -> {
                long overwritten =
                        stateStore.saveIfVersion(
                                userId,
                                sessionId,
                                "agent_state",
                                toSave,
                                AgentStateStore.UNVERSIONED);
                if (overwritten != AgentStateStore.UNVERSIONED) {
                    slotVersions.put(slot, overwritten);
                    log.warn(
                            "agent_state CAS conflict — OVERWRITE applied"
                                    + " (userId={}, sessionId={}, expectedVersion={})",
                            userId,
                            sessionId,
                            expectedVersion);
                    return overwritten;
                }
                stateStore.save(userId, sessionId, "agent_state", toSave);
                log.warn(
                        "agent_state CAS conflict — OVERWRITE applied"
                                + " (userId={}, sessionId={}, expectedVersion={})",
                        userId,
                        sessionId,
                        expectedVersion);
                return AgentStateStore.UNVERSIONED;
            }
            case FAIL ->
                    throw new ConcurrentSessionModificationException(
                            userId, sessionId, "agent_state", expectedVersion);
            case APPEND_MERGE -> {
                VersionedState<AgentState> latest =
                        stateStore.getVersioned(userId, sessionId, "agent_state", AgentState.class);
                AgentState baseline =
                        latest.isPresent()
                                ? latest.value()
                                : freshState(
                                        initialPermissionContext,
                                        getAgentId(),
                                        userId,
                                        sessionId,
                                        initialActiveToolGroups);
                List<Msg> local = toSave.getContext();
                if (loadedContextSize >= 0 && loadedContextSize < local.size()) {
                    List<Msg> appended = local.subList(loadedContextSize, local.size());
                    baseline.contextMutable().addAll(appended);
                }
                baseline.setPermissionContext(toSave.getPermissionContext());
                long merged =
                        stateStore.saveIfVersion(
                                userId, sessionId, "agent_state", baseline, latest.version());
                if (merged == AgentStateStore.UNVERSIONED) {
                    throw new ConcurrentSessionModificationException(
                            userId, sessionId, "agent_state", latest.version());
                }
                slotVersions.put(slot, merged);
                stateCache.put(slot, baseline);
                log.info(
                        "agent_state CAS conflict — APPEND_MERGE succeeded"
                                + " (userId={}, sessionId={})",
                        userId,
                        sessionId);
                return merged;
            }
            default ->
                    throw new IllegalStateException("Unknown conflict policy: " + conflictPolicy);
        }
    }

    /**
     * 单次调用槽位激活。从给定 RuntimeContext 读取 {@code (userId, sessionId)}
     * （缺失时回退到 {@link #defaultSessionId}），原子地将活动 {@code #state} 与 {@code #permissionEngine}
     * 切换为该槽位的缓存条目。
     *
     * <p>配置 {@link AgentStateStore} 后，每次调用开始时总是从存储重新加载状态，
     * 使分布式部署中同一 sessionId 即使转移到不同机器，也能看到最新持久化状态而非过期本地缓存。
     * 相比大模型往返调用，每次读取一次存储的代价很小。
     *
     * <p>仅允许从 {@code beforeAgentExecution} 安全调用；调用方必须持有 {@code AgentBase.acquireExecution} 锁。
     */
    private CallExecution activateSlotForContext(RuntimeContext ctx) {
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        String uid = ctx != null ? ctx.getUserId() : null;
        String slot = slotKey(uid, sid);
        final String finalUid = uid;
        final String finalSid = sid;
        AgentState loaded;
        long loadedVersion = AgentStateStore.UNVERSIONED;
        SessionRecorder activeRecorder = SessionRecorder.from(ctx);
        if (activeRecorder != null) {
            loaded = activeRecorder.initial().restore();
            if (loaded == null)
                loaded =
                        freshState(
                                initialPermissionContext,
                                getAgentId(),
                                uid,
                                sid,
                                initialActiveToolGroups);
        } else if (stateStore != null) {
            VersionedState<AgentState> versioned =
                    loadOrCreateAgentStateForSlot(
                            stateStore,
                            finalUid,
                            finalSid,
                            initialPermissionContext,
                            getAgentId(),
                            initialActiveToolGroups);
            loaded = versioned.value();
            loadedVersion = versioned.version();
            stateCache.put(slot, loaded);
            slotVersions.put(slot, loadedVersion);
        } else {
            loaded =
                    stateCache.computeIfAbsent(
                            slot,
                            k ->
                                    loadOrCreateAgentStateForSlot(
                                                    null,
                                                    finalUid,
                                                    finalSid,
                                                    initialPermissionContext,
                                                    getAgentId(),
                                                    initialActiveToolGroups)
                                            .value());
        }
        final AgentState resolvedState = loaded;
        PermissionEngine loadedEngine;
        if (stateStore != null || activeRecorder != null) {
            loadedEngine = new PermissionEngine(loaded.getPermissionContext());
            permissionEngineCache.put(slot, loadedEngine);
        } else {
            loadedEngine =
                    permissionEngineCache.computeIfAbsent(
                            slot, k -> new PermissionEngine(resolvedState.getPermissionContext()));
        }
        return new CallExecution(loaded, loadedEngine, slot, loadedVersion);
    }

    // ==================== 配置组装辅助方法 ====================

    private static ModelConfig assembleModelConfig(Builder b) {
        int retries = b.flatMaxRetries != null ? b.flatMaxRetries : ModelConfig.DEFAULT_MAX_RETRIES;
        return new ModelConfig(retries, b.flatFallbackModel, b.flatFailoverListener);
    }

    private static ReactConfig assembleReactConfig(Builder b) {
        boolean stop =
                b.flatStopOnReject != null
                        ? b.flatStopOnReject
                        : ReactConfig.DEFAULT_STOP_ON_REJECT;
        return new ReactConfig(b.maxIters, stop);
    }

    // ==================== RuntimeContext ====================

    @Override
    protected Object callSerializationKey(RuntimeContext rc) {
        // 按 (userId, sessionId) 槽位串行化调用：同会话调用共享缓存的 AgentState
        // /
        // 及会话历史，因此必须逐个运行；不同会话则并行。
        String sid = rc != null ? rc.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        String uid = rc != null ? rc.getUserId() : null;
        return slotKey(uid, sid);
    }

    @Override
    protected Mono<Object> acquireCallExecution(
            List<Msg> msgs, RuntimeContext rc, RunControl control) {
        if (sessionLogStore == null || sessionHistoryMode == SessionHistoryMode.LEGACY)
            return super.acquireCallExecution(msgs, rc, control);
        return Mono.fromCallable(
                        () -> {
                            RuntimeContext callContext =
                                    rc == null
                                            ? RuntimeContext.empty()
                                            : RuntimeContext.builder(rc).build();
                            String sid = callContext.getSessionId();
                            if (sid == null || sid.isBlank()) sid = defaultSessionId;
                            SessionRecorder recorder =
                                    new SessionRecorder(
                                            sessionLogStore.open(
                                                    new SessionKey(
                                                            callContext.getUserId(),
                                                            sessionLogAgentId,
                                                            sid),
                                                    callContext),
                                            (String) callContext.get(SessionRecorder.TURN_ID_KEY),
                                            control.runId(),
                                            (SessionExportSink)
                                                    callContext.get(SessionExportSink.CONTEXT_KEY));
                            callContext.put(SessionRecorder.CONTEXT_KEY, recorder);
                            if (callContext.get(SessionRecorder.PARENT_KEY) != null)
                                recorder.append(
                                        "subagent/linked",
                                        callContext.get(SessionRecorder.PARENT_KEY));
                            try {
                                SessionExecution execution = SessionExecution.from(callContext);
                                if (execution != null) execution.prepare(recorder);
                                CallExecution acquired =
                                        (CallExecution)
                                                beforeAgentExecution(msgs, callContext, control);
                                if (rc != null) rc.setAgentState(acquired.state);
                                return (Object) acquired;
                            } catch (Throwable error) {
                                closeFailedSessionExecution(callContext, error);
                                throw error;
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        scope -> {
                            CallExecution ce = (CallExecution) scope;
                            return SessionRecorder.from(ce.rc)
                                    .start(
                                            SessionExecution.from(ce.rc) == null
                                                    ? msgs
                                                    : SessionExecution.from(ce.rc).inputs(),
                                            ce.state)
                                    .thenReturn(scope)
                                    .doOnError(error -> closeFailedSessionExecution(ce.rc, error));
                        })
                .doOnDiscard(CallExecution.class, scope -> SessionRecorder.from(scope.rc).close());
    }

    private void closeFailedSessionExecution(RuntimeContext context, Throwable error) {
        SessionRecorder recorder = SessionRecorder.from(context);
        try {
            SessionExecution execution = SessionExecution.from(context);
            if (execution != null) execution.close(recorder);
        } catch (Throwable cleanup) {
            error.addSuppressed(cleanup);
        }
        try {
            recorder.close();
        } catch (Throwable cleanup) {
            error.addSuppressed(cleanup);
        }
    }

    @Override
    protected Mono<Void> releaseCallExecution(Object scope, String status) {
        if (!(scope instanceof CallExecution ce)) return Mono.empty();
        SessionRecorder recorder = SessionRecorder.from(ce.rc);
        if (recorder == null) return Mono.empty();
        return Mono.fromRunnable(
                        () -> {
                            SessionExecution execution = SessionExecution.from(ce.rc);
                            if (execution != null) execution.close(recorder);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .materialize()
                .flatMap(
                        signal ->
                                recorder.finish(ce.state, status)
                                        .then(
                                                signal.isOnError()
                                                        ? Mono.error(signal.getThrowable())
                                                        : Mono.empty()));
    }

    @Override
    protected Object beforeAgentExecution(List<Msg> msgs, RuntimeContext rc, RunControl control) {
        RuntimeContext ctx = rc;
        if (ctx == null) {
            ctx = RuntimeContext.builder().runId(control.runId()).build();
        }
        // 按调用解析 RuntimeContext 携带的 (userId, sessionId) 槽位（缺失时
        // 回退到构建器默认值），并新建绑定到该槽位缓存状态／权限引擎的
        // 调用作用域。返回引用是权威的
        // 单次调用作用域（经 Reactor Context 传递）；实例字段仅是
        // 调用之外访问器使用的旁路默认值。
        CallExecution scope = activateSlotForContext(ctx);
        // 在 RuntimeContext 暴露调用作用域 AgentState，使中间件／工具
        // 通过 rc.getAgentState() 解析活动会话状态（调用作用域、并发安全），
        // 而不是通过并发时不具备调用作用域的 agent.getAgentState()。
        ctx.setAgentState(scope.state);
        // 状态已就绪：调用 onAgentStateReady 扩展点，此时还可以在输入进入
        // 管道（前置调用钩子、记忆、推理）之前调整输入。
        onAgentStateReady(ctx, scope.state, msgs);
        // 将单次调用状态放入活动执行作用域。系统消息由
        // consumeSystemMsgAfterPreCall 初始化；事件 sink（如果存在）在 doCall() 中从
        // streamEvents 携带的按订阅 Reactor Context 绑定。
        scope.rc = ctx;
        scope.executionIdentity =
                ctx.get(ExecutionIdentity.CONTEXT_KEY) instanceof ExecutionIdentity identity
                        ? identity
                        : new ExecutionIdentity(
                                sessionKey(ctx).agentId(),
                                sessionKey(ctx).sessionId(),
                                SessionRecorder.from(ctx) != null
                                        ? SessionRecorder.from(ctx).turnId()
                                        : UUID.randomUUID().toString(),
                                control.runId());
        // 单次调用工具请求配置（不可变工具差异：外部工具及合并模式）。
        // 共享 toolkit 字段既不复制也不修改；单次调用差异
        // 保存在作用域的 toolRequestConfig 中，按需与共享工具集组合。
        ToolRequestConfig requestConfig = ctx.getToolRequestConfig();
        scope.toolRequestConfig = requestConfig != null ? requestConfig : ToolRequestConfig.NONE;
        scope.activeToolkit = this.toolkit;
        scope.systemMsg = null;
        scope.interruption = control.interruption();
        return scope;
    }

    /**
     * 按列表顺序（即 {@code order()} 降序）为每个 {@code ON_AGENT_STATE_READY} 参与者
     * （{@link #middlewaresAt}）调用 {@link MiddlewareBase#onAgentStateReady}。不做异常隔离：
     * 异常原样传播给调用方，后续中间件不再调用。{@code msgs} 是订阅私有的可变副本，
     * 原地调整只影响本次调用的后续过程。
     */
    private void onAgentStateReady(RuntimeContext ctx, AgentState state, List<Msg> msgs) {
        for (MiddlewareBase mw :
                middlewaresAt(MiddlewareBase.ExtensionPoint.ON_AGENT_STATE_READY)) {
            mw.onAgentStateReady(this, ctx, state, msgs);
        }
    }

    @Override
    protected Mono<Msg> seedSystemMsg(Object callExectution) {
        RuntimeContext rc = callExectution instanceof CallExecution ce ? ce.rc : null;
        String base = sysPrompt != null ? sysPrompt.trim() : "";
        return applySystemPromptMiddlewares(base, rc)
                .filter(prompt -> !prompt.isEmpty())
                .map(
                        prompt ->
                                SystemMessage.builder()
                                        .name("system")
                                        .content(TextBlock.builder().text(prompt).build())
                                        .build());
    }

    @Override
    protected List<Msg> inputsForCall(Object scope, List<Msg> inputs) {
        if (scope instanceof CallExecution ce && SessionExecution.from(ce.rc) != null)
            inputs = SessionExecution.from(ce.rc).inputs();
        if (!(scope instanceof CallExecution ce)
                || SessionRecorder.from(ce.rc) == null
                || inputs == null) return inputs;
        Set<String> accepted = SessionRecorder.from(ce.rc).acceptedMessageIds();
        return inputs.stream().filter(message -> !accepted.contains(message.getId())).toList();
    }

    @Override
    protected void onCallResult(Object scope, Msg result) {
        if (scope instanceof CallExecution ce) {
            SessionRecorder recorder = SessionRecorder.from(ce.rc);
            if (recorder != null) recorder.result(result);
        }
    }

    @Override
    protected RuntimeContext runtimeForCall(Object scope) {
        return scope instanceof CallExecution ce ? ce.rc : null;
    }

    @Override
    protected AgentState stateForCall(Object callScope) {
        return callScope instanceof CallExecution ce ? ce.state : getAgentState();
    }

    /**
     * 按扩展点分组中间件；声明为 {@code null} 时视为参与全部扩展点。
     */
    private static Map<MiddlewareBase.ExtensionPoint, List<MiddlewareBase>> groupMiddlewares(
            List<MiddlewareBase> middlewares) {
        Map<MiddlewareBase.ExtensionPoint, List<MiddlewareBase>> grouped =
                new EnumMap<>(MiddlewareBase.ExtensionPoint.class);
        for (MiddlewareBase mw : middlewares) {
            Set<MiddlewareBase.ExtensionPoint> active =
                    Objects.requireNonNullElse(
                            mw.activePoints(), EnumSet.allOf(MiddlewareBase.ExtensionPoint.class));
            for (MiddlewareBase.ExtensionPoint point : MiddlewareBase.ExtensionPoint.values()) {
                if (active.contains(point)) {
                    grouped.computeIfAbsent(point, p -> new ArrayList<>()).add(mw);
                }
            }
        }
        grouped.replaceAll((point, participants) -> List.copyOf(participants));
        return Collections.unmodifiableMap(grouped);
    }

    /**
     * 按洋葱链顺序返回在指定扩展点活动的中间件。
     *
     * <p>列表是构造时的不可变快照，无中间件参与时为空。
     *
     * @param point 扩展点
     * @return 不可变参与者列表，绝不为 {@code null}
     */
    public List<MiddlewareBase> middlewaresAt(MiddlewareBase.ExtensionPoint point) {
        return groupedMiddlewares.getOrDefault(point, List.of());
    }

    private Mono<String> applySystemPromptMiddlewares(String prompt, RuntimeContext ctx) {
        List<MiddlewareBase> participants =
                middlewaresAt(MiddlewareBase.ExtensionPoint.ON_SYSTEM_PROMPT);
        if (participants.isEmpty()) {
            return Mono.just(prompt);
        }
        Mono<String> result = Mono.just(prompt);
        for (MiddlewareBase mw : participants) {
            result = result.flatMap(p -> mw.onSystemPrompt(this, ctx, p));
        }
        return result;
    }

    @Override
    protected void consumeSystemMsgAfterPreCall(Msg systemMsg, Object callScope) {
        CallExecution ce = (CallExecution) callScope;
        ce.systemMsg = systemMsg;
    }

    private RuntimeContext buildMergedRuntimeContext(RuntimeContext run) {
        if (actionObserver != null) {
            RuntimeContext base = run != null ? run : RuntimeContext.empty();
            String sid = base.getSessionId();
            run =
                    RuntimeContext.builder(base)
                            .sessionId(sid == null || sid.isBlank() ? defaultSessionId : sid)
                            .put(ActionObserver.CONTEXT_KEY, actionObserver)
                            .build();
        }
        if (run == null) {
            if (toolExecutionContext != null) {
                return RuntimeContext.builder().toolExecutionContext(toolExecutionContext).build();
            }
            return RuntimeContext.empty();
        }
        if (toolExecutionContext != null) {
            return RuntimeContext.builder(run)
                    .toolExecutionContext(
                            ToolExecutionContext.merge(
                                    run.asToolExecutionContext(), toolExecutionContext))
                    .build();
        }
        return run;
    }

    /**
     * 使用单次调用 {@link RuntimeContext} 调用智能体（供钩子和工具使用的元数据，不持久化）。
     */
    public Mono<Msg> call(List<Msg> msgs, RuntimeContext context) {
        return callInternal(msgs, context, this::doCall);
    }

    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass, RuntimeContext context) {
        return callInternal(msgs, context, m -> doCall(m, structuredOutputClass));
    }

    public Mono<Msg> call(List<Msg> msgs, JsonNode outputSchema, RuntimeContext context) {
        return callInternal(msgs, context, m -> doCall(m, outputSchema));
    }

    /**
     * 使用纯文本输入和单次调用 {@link RuntimeContext} 调用智能体。
     *
     * @param text 输入文本（包装为 {@link UserMessage}）
     * @param context 单次调用运行时上下文
     * @return 回复消息
     */
    public Mono<Msg> call(String text, RuntimeContext context) {
        return call(List.of(new UserMessage(text)), context);
    }

    /**
     * 使用纯文本输入、结构化输出类及单次调用 {@link RuntimeContext} 调用智能体。
     *
     * @param text 输入文本（包装为 {@link UserMessage}）
     * @param structuredOutputClass 定义结构的类
     * @param context 单次调用运行时上下文
     * @return 在元数据中携带结构化数据的回复消息
     */
    public Mono<Msg> call(String text, Class<?> structuredOutputClass, RuntimeContext context) {
        return call(List.of(new UserMessage(text)), structuredOutputClass, context);
    }

    /**
     * 将调用方提供的 {@link RuntimeContext} 以 {@link AgentBase#RUNTIME_CONTEXT_KEY} 为键
     * 附加到给定发布者的 Reactor Context，使调用生命周期按订阅读取它（并发安全），而非从共享实例字段读取。
     */
    private Mono<Msg> withRuntimeContext(Mono<Msg> mono, RuntimeContext context) {
        return context == null ? mono : mono.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
    }

    private Flux<Event> withRuntimeContext(Flux<Event> flux, RuntimeContext context) {
        return context == null ? flux : flux.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
    }

    // ==================== 中断（会话级） ====================

    /**
     * @deprecated 请显式提供 userId/sessionId，使用 {@link #interrupt(String, String)}。
     */
    @Deprecated
    @Override
    public void interrupt() {
        interrupt(null, defaultSessionId);
    }

    /**
     * @deprecated 请显式提供 userId/sessionId，使用 {@link #interrupt(String, String, Msg)}。
     */
    @Deprecated
    @Override
    public void interrupt(Msg msg) {
        interrupt(null, defaultSessionId, msg);
    }

    /**
     * @deprecated 请显式提供 userId/sessionId，使用 {@link #interrupt(String, String)}。
     */
    @Deprecated
    @Override
    public void interrupt(InterruptSource source) {
        interruptRunning(slotKey(null, defaultSessionId), source, null);
    }

    /**
     * 中断给定 {@link RuntimeContext} 标识的进行中调用。
     * 使用 {@code ctx.getUserId()} 与 {@code ctx.getSessionId()} 定位会话。
     *
     * @param ctx 标识待中断会话的运行时上下文
     */
    public void interrupt(RuntimeContext ctx) {
        interrupt(ctx, null);
    }

    /**
     * 中断给定 {@link RuntimeContext} 标识的进行中调用，并附带用户消息。
     *
     * @param ctx 标识待中断会话的运行时上下文
     * @param msg 可选的附加到中断信号的用户消息
     */
    public void interrupt(RuntimeContext ctx, Msg msg) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        interruptRunning(slotKey(uid, sid), InterruptSource.USER, msg);
    }

    /**
     * 中断指定 {@code (userId, sessionId)} 会话的进行中调用。
     *
     * @param userId 用户 ID（{@code null} 表示匿名／单租户）
     * @param sessionId 会话 ID
     */
    public void interrupt(String userId, String sessionId) {
        interrupt(userId, sessionId, null);
    }

    /**
     * 中断指定 {@code (userId, sessionId)} 会话的进行中调用，并附带用户消息。
     */
    public void interrupt(String userId, String sessionId, Msg msg) {
        interruptRunning(slotKey(userId, sessionId), InterruptSource.USER, msg);
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@link #streamEvents(List)}
     * 及通过 {@code call()} 驱动生命周期、接收 {@link RuntimeContext} 的重载，获取细粒度 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, RuntimeContext context) {
        return withRuntimeContext(stream(msgs, options), context);
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@link #streamEvents(List)} 获取细粒度 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs,
            StreamOptions options,
            Class<?> structuredModel,
            RuntimeContext context) {
        return withRuntimeContext(stream(msgs, options, structuredModel), context);
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@link #streamEvents(List)} 获取细粒度 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, JsonNode schema, RuntimeContext context) {
        return withRuntimeContext(stream(msgs, options, schema), context);
    }

    // ==================== 共享智能体流核心 ====================

    /**
     * 覆盖基类钩子，使所有 {@code call()} 变体（包括结构化输出及携带上下文的重载）
     * 都与 {@code streamEvents()} 走相同的 {@link #buildAgentStream} 核心，
     * 保证 {@code onAgent} 中间件链在所有调用路径上触发，而不只在流式路径触发。
     *
     * <p>从 {@link #buildAgentStream} 在 {@link AgentEndEvent} 之前发出的 {@link AgentResultEvent} 提取结果。
     */
    @Override
    protected Mono<Msg> callInternal(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        // 【学习提示】普通 call 并不另写一套执行逻辑，而是从公共事件流提取最终结果。
        // 学习时将事件流视作主干，可同时理解流式 API、普通 API 与中间件的一致性。
        return buildAgentStream(msgs, context, doCallFn)
                .filter(e -> e instanceof AgentResultEvent)
                .cast(AgentResultEvent.class)
                .map(AgentResultEvent::getResult)
                .takeLast(1)
                .next();
    }

    /**
     * {@code call()}（经 {@link #callInternal}）和 {@code streamEvents()} 共享的唯一实现。
     *
     * <p>流以 {@link AgentStartEvent}／{@link AgentEndEvent} 为起止边界，
     * 包装完整的 {@link AgentBase#runLifecycle}（关闭保护、串行化门控、前后钩子及追踪），
     * 并在 {@link AgentEndEvent} 之前立即发出携带最终 {@link Msg} 的 {@link AgentResultEvent}。
     * {@code onAgent} 中间件链在此核心外只应用一次。
     *
     * @param msgs 输入消息列表
     * @param context 调用方提供的单次调用 {@link RuntimeContext}，或 {@code null}
     * @param doCallFn 具体调用实现（{@link #doCall} 或结构化输出变体），直接传给 {@link AgentBase#runLifecycle}
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    private Flux<AgentEvent> buildAgentStream(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        return Flux.deferContextual(
                view -> {
                    RunControl inherited = RunControl.current(view, getAgentId());
                    RunControl control =
                            inherited != null ? inherited : new RunControl(getAgentId());
                    RuntimeContext source =
                            context != null
                                    ? context
                                    : view.getOrDefault(
                                            RUNTIME_CONTEXT_KEY, RuntimeContext.empty());
                    RuntimeContext effective = RuntimeContext.builder(source).build();
                    SessionKey key = sessionKey(effective);
                    String turnId = (String) effective.get(SessionRecorder.TURN_ID_KEY);
                    if (turnId == null) turnId = UUID.randomUUID().toString();
                    ExecutionIdentity identity =
                            new ExecutionIdentity(
                                    key.agentId(), key.sessionId(), turnId, control.runId());
                    effective.put(SessionRecorder.TURN_ID_KEY, identity.turnId());
                    effective.put(ExecutionIdentity.CONTEXT_KEY, identity);
                    Flux<AgentEvent> events =
                            buildIdentifiedAgentStream(msgs, effective, doCallFn)
                                    .doOnNext(
                                            event -> {
                                                if (event.getSource() == null
                                                        || event.getSource().isBlank())
                                                    event.withExecution(identity);
                                            })
                                    .doOnEach(
                                            signal -> {
                                                if (signal.isOnComplete() || signal.isOnError())
                                                    source.setAgentState(effective.getAgentState());
                                            })
                                    .contextWrite(control::attach);
                    if (inherited != null) return events;
                    return events.doOnComplete(() -> control.finish(AgentRun.Status.COMPLETED))
                            .doOnError(error -> control.finish(AgentRun.Status.FAILED))
                            .doOnCancel(control::cancel);
                });
    }

    private Flux<AgentEvent> buildIdentifiedAgentStream(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        String replyId = UUID.randomUUID().toString().replace("-", "");
        Function<AgentInput, Flux<AgentEvent>> core =
                input ->
                        Flux.<AgentEvent>create(
                                sink -> {
                                    sink.next(
                                            new AgentStartEvent(
                                                    sessionKey(context).sessionId(),
                                                    replyId,
                                                    getName()));
                                    reactor.util.context.Context subscriberCtx =
                                            reactor.util.context.Context.of(sink.contextView());

                                    // 直接调用 runLifecycle，而非 call()，避免
                                    // 第二次应用 onAgent 链。
                                    Mono<Msg> lifecycle = runLifecycle(input.msgs(), doCallFn);
                                    if (context != null) {
                                        lifecycle =
                                                lifecycle.contextWrite(
                                                        c -> c.put(RUNTIME_CONTEXT_KEY, context));
                                    }
                                    // 当已弃用的 stream() → SubagentEventBus 路径
                                    // 驱动本次调用时，不安装 AgentEventEmitter.CONTEXT_KEY。
                                    // 在该路径上，AgentSpawnTool 读取
                                    // SubagentEventBus.CONTEXT_KEY 转发子事件；
                                    // 若在此安装 CONTEXT_KEY，execLocalSync 会
                                    // 改走 AgentEvent 路径而非总线路径，将
                                    // 子事件路由到此 Flux 的内部 sink，随后它们会在
                                    // 到达调用方之前被 callInternal 过滤掉。
                                    boolean isSubagentBusPath =
                                            subscriberCtx.hasKey(SubagentEventBus.CONTEXT_KEY);
                                    Disposable lifecycleDisposable =
                                            lifecycle
                                                    .contextWrite(c -> c.put(EVENT_SINK_KEY, sink))
                                                    .contextWrite(
                                                            c ->
                                                                    isSubagentBusPath
                                                                            ? c
                                                                            : c.put(
                                                                                    AgentEventEmitter
                                                                                            .CONTEXT_KEY,
                                                                                    (AgentEventEmitter)
                                                                                            sink
                                                                                                    ::next))
                                                    .doFinally(
                                                            signal -> {
                                                                sink.next(
                                                                        new AgentEndEvent(replyId));
                                                                sink.complete();
                                                            })
                                                    .contextWrite(subscriberCtx)
                                                    .subscribe(
                                                            finalMsg ->
                                                                    sink.next(
                                                                            new AgentResultEvent(
                                                                                    finalMsg)),
                                                            sink::error);
                                    sink.onCancel(lifecycleDisposable);
                                },
                                FluxSink.OverflowStrategy.BUFFER);
        return MiddlewareChain.build(
                        middlewaresAt(MiddlewareBase.ExtensionPoint.ON_AGENT),
                        this,
                        context,
                        MiddlewareBase::onAgent,
                        core)
                .apply(new AgentInput(msgs == null ? List.of() : msgs));
    }

    /**
     * 准备一次性事件执行句柄，但不启动调用。采用上下文的 runId（{@code run.runId() == ctx.getRunId()}）。
     * null 上下文在整条调用链中等价于 {@link RuntimeContext#empty()}，
     * 响应式上下文始终携带一个（可能为空的）上下文。
     */
    public AgentRun<AgentEvent> prepareRun(List<Msg> msgs, RuntimeContext context) {
        RuntimeContext effective = context != null ? context : RuntimeContext.empty();
        return AgentRun.create(
                getAgentId(), effective.getRunId(), () -> streamEvents(msgs, effective));
    }

    /**
     * 准备一次性回复执行句柄，但不启动调用。采用上下文的 runId（{@code run.runId() == ctx.getRunId()}）。
     * null 上下文在整条调用链中等价于 {@link RuntimeContext#empty()}，
     * 响应式上下文始终携带一个（可能为空的）上下文。
     */
    public AgentRun<Msg> prepareCall(List<Msg> msgs, RuntimeContext context) {
        RuntimeContext effective = context != null ? context : RuntimeContext.empty();
        return AgentRun.create(getAgentId(), effective.getRunId(), () -> call(msgs, effective));
    }

    // ==================== streamEvents 公共 API ====================

    /**
     * 流式发送完整智能体生命周期中的细粒度 {@link AgentEvent}。
     *
     * <p>{@code call()} 与 {@code streamEvents()} 共享内部 {@link #buildAgentStream} 核心，
     * 因此 {@code onAgent} 中间件链在所有路径触发。流在 {@link AgentEndEvent} 之前包含
     * 携带最终 {@link Msg} 的 {@link AgentResultEvent}。
     *
     * @param msgs 输入消息列表
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        return streamEvents(msgs, (RuntimeContext) null);
    }

    /**
     * 为单条输入消息流式发送细粒度 {@link AgentEvent}。
     *
     * @param msg 输入消息
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(Msg msg) {
        return streamEvents(List.of(msg));
    }

    /**
     * 使用调用方提供的 {@link RuntimeContext} 流式发送细粒度 {@link AgentEvent}。
     *
     * <p>直接委托给 {@link #buildAgentStream}，与 {@code call()} 使用相同核心。
     * 并发调用不共享状态；每个订阅具有独立的事件 sink 和生命周期执行。
     *
     * @param msgs 输入消息列表
     * @param context 要传播到调用中的运行时上下文
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context) {
        return buildAgentStream(msgs, context, this::doCall);
    }

    /**
     * 使用调用方提供的 {@link RuntimeContext}，为单条输入消息流式发送细粒度 {@link AgentEvent}。
     *
     * @param msg 输入消息
     * @param context 要传播到调用中的运行时上下文
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(Msg msg, RuntimeContext context) {
        return streamEvents(List.of(msg), context);
    }

    /**
     * 为纯文本输入流式发送细粒度 {@link AgentEvent}。
     *
     * @param text 输入文本（包装为 {@link UserMessage}）
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(String text) {
        return streamEvents(new UserMessage(text));
    }

    /**
     * 使用调用方提供的 {@link RuntimeContext}，为纯文本输入流式发送细粒度 {@link AgentEvent}。
     *
     * @param text 输入文本（包装为 {@link UserMessage}）
     * @param context 要传播到调用中的运行时上下文
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(String text, RuntimeContext context) {
        return streamEvents(new UserMessage(text), context);
    }

    // ==================== 受保护 API ====================

    /**
     * 从共享 {@code call()} 生命周期携带的 Reactor Context 解析单次调用 {@link CallExecution}。
     * 缺失时（例如不经过 {@code call()} 的旧路径）回退到实例 {@link #exec}，
     * 使各并发调用操作自己的作用域。
     */
    private CallExecution scopeFrom(reactor.util.context.ContextView cv) {
        Object scope = cv.getOrDefault(CALL_SCOPE_KEY, null);
        if (!(scope instanceof CallExecution ce)) {
            throw new IllegalStateException(
                    "No CallExecution in Reactor Context — scopeFrom called outside call"
                            + " lifecycle");
        }
        return ce;
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    // 存在转发发射器时，父级工具正通过附加来源标签的包装器路由子事件。
                    // 跳过直接 FluxSink，让
                    // publishEvent() 改用转发发射器。
                    boolean hasForwardingEmitter =
                            cv.hasKey(AgentEventEmitter.FORWARDING_CONTEXT_KEY);
                    if (!hasForwardingEmitter) {
                        Object sink = cv.getOrDefault(EVENT_SINK_KEY, null);
                        if (sink instanceof FluxSink) {
                            @SuppressWarnings("unchecked")
                            FluxSink<AgentEvent> eventSink = (FluxSink<AgentEvent>) sink;
                            scope.eventSink = eventSink;
                        }
                    }
                    if (scope.eventSink == null) {
                        AgentEventEmitter.fromForwardingContext(cv)
                                .ifPresent(ae -> scope.externalEventEmitter = ae);
                    }
                    return scope.doCallInner(msgs)
                            .onErrorResume(error -> saveStateAfterCallFailure(scope, error))
                            .flatMap(result -> saveStateToSession(scope).thenReturn(result))
                            .switchIfEmpty(
                                    Mono.defer(
                                            () ->
                                                    saveStateToSession(scope)
                                                            .then(Mono.<Msg>empty())));
                });
    }

    // ==================== 结构化输出（单次调用） ====================

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
        return doStructuredCall(msgs, structuredOutputClass, null);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs, JsonNode outputSchema) {
        return doStructuredCall(msgs, null, outputSchema);
    }

    /**
     * 结构化输出调用分派器。模型支持时走原生路径（模型的 {@code response_format}），
     * 否则回退到合成 {@code generate_response} 工具方案。
     */
    private Mono<Msg> doStructuredCall(List<Msg> msgs, Class<?> targetClass, JsonNode schemaDesc) {
        if (targetClass == null && schemaDesc == null) {
            return Mono.error(
                    new IllegalArgumentException(
                            "Either targetClass or schemaDesc must be provided"));
        }
        if (targetClass != null && schemaDesc != null) {
            return Mono.error(
                    new IllegalArgumentException("Cannot provide both targetClass and schemaDesc"));
        }
        Map<String, Object> jsonSchema =
                targetClass != null
                        ? JsonSchemaUtils.generateSchemaFromClass(targetClass)
                        : JsonSchemaUtils.generateSchemaFromJsonNode(schemaDesc);
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    List<String> activeGroups = scope.state.getToolContext().getActivatedGroups();
                    boolean hasTools =
                            !scope.activeToolkit
                                    .getToolSchemas(activeGroups, scope.toolRequestConfig)
                                    .isEmpty();
                    boolean useNative =
                            hasTools
                                    ? model.supportsNativeStructuredOutputWithTools()
                                    : model.supportsNativeStructuredOutput();
                    log.debug(
                            "Structured output routing: native={}, hasTools={} (agent={}#{})",
                            useNative,
                            hasTools,
                            getAgentId(),
                            getName());
                    if (useNative) {
                        return doNativeStructuredCall(msgs, jsonSchema)
                                .onErrorResume(
                                        e -> {
                                            log.warn(
                                                    "Native structured output failed ({}) — falling"
                                                            + " back to synthetic tool path",
                                                    e.getMessage() != null
                                                            ? e.getMessage()
                                                            : e.getClass().getSimpleName());
                                            return doFallbackStructuredCall(msgs, jsonSchema);
                                        });
                    }
                    return doFallbackStructuredCall(msgs, jsonSchema);
                });
    }

    /**
     * 原生结构化输出路径：通过 {@link GenerateOptions} 的 {@code response_format} 传递 schema。
     * 模型以文本内容返回结构化 JSON，ReAct 循环自然结束，无需合成工具。
     */
    private Mono<Msg> doNativeStructuredCall(List<Msg> msgs, Map<String, Object> jsonSchema) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    boolean hasForwardingEmitter =
                            cv.hasKey(AgentEventEmitter.FORWARDING_CONTEXT_KEY);
                    if (!hasForwardingEmitter) {
                        Object sink = cv.getOrDefault(EVENT_SINK_KEY, null);
                        if (sink instanceof FluxSink) {
                            @SuppressWarnings("unchecked")
                            FluxSink<AgentEvent> eventSink = (FluxSink<AgentEvent>) sink;
                            scope.eventSink = eventSink;
                        }
                    }
                    if (scope.eventSink == null) {
                        AgentEventEmitter.fromForwardingContext(cv)
                                .ifPresent(ae -> scope.externalEventEmitter = ae);
                    }

                    scope.nativeResponseFormat =
                            ResponseFormat.jsonSchema(
                                    JsonSchema.builder()
                                            .name(STRUCTURED_OUTPUT_TOOL_NAME)
                                            .schema(jsonSchema)
                                            .build());

                    int contextSizeBefore = scope.state.contextMutable().size();

                    return scope.doCallInner(msgs)
                            .flatMap(
                                    result -> {
                                        Msg out =
                                                wrapNativeStructuredResult(
                                                        result,
                                                        scope.nativeResponseFormat.getJsonSchema());
                                        return saveStateToSession(scope).thenReturn(out);
                                    })
                            .switchIfEmpty(
                                    Mono.defer(
                                            () ->
                                                    saveStateToSession(scope)
                                                            .then(Mono.<Msg>empty())))
                            .doOnError(
                                    e -> {
                                        List<Msg> ctx = scope.state.contextMutable();
                                        while (ctx.size() > contextSizeBefore) {
                                            ctx.remove(ctx.size() - 1);
                                        }
                                        scope.nativeResponseFormat = null;
                                    });
                });
    }

    /**
     * 回退结构化输出路径：注入合成 {@code generate_response} 工具和指令提示。
     * 模型调用该工具后，循环通过 {@code PostActingEvent.stopAgent()} 自然停止。
     */
    private Mono<Msg> doFallbackStructuredCall(List<Msg> msgs, Map<String, Object> jsonSchema) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    boolean hasForwardingEmitter =
                            cv.hasKey(AgentEventEmitter.FORWARDING_CONTEXT_KEY);
                    if (!hasForwardingEmitter) {
                        Object sink = cv.getOrDefault(EVENT_SINK_KEY, null);
                        if (sink instanceof FluxSink) {
                            @SuppressWarnings("unchecked")
                            FluxSink<AgentEvent> eventSink = (FluxSink<AgentEvent>) sink;
                            scope.eventSink = eventSink;
                        }
                    }
                    if (scope.eventSink == null) {
                        AgentEventEmitter.fromForwardingContext(cv)
                                .ifPresent(ae -> scope.externalEventEmitter = ae);
                    }

                    scope.soTool = createStructuredOutputTool(jsonSchema);

                    return scope.doCallInner(msgs)
                            .onErrorResume(error -> saveStateAfterCallFailure(scope, error))
                            .flatMap(
                                    result -> {
                                        Msg out = result;
                                        if (scope.soCompleted && scope.soResultMsg != null) {
                                            ChatUsage aggregatedUsage =
                                                    collectAggregatedUsage(scope.state);
                                            ThinkingBlock aggregatedThinking =
                                                    collectLastThinking(scope.state);
                                            compressStructuredOutputContext(scope.state);
                                            Msg extracted =
                                                    extractStructuredResult(scope.soResultMsg);
                                            if (extracted != null) {
                                                out =
                                                        mergeCollectedMetadata(
                                                                extracted,
                                                                aggregatedUsage,
                                                                aggregatedThinking);
                                            }
                                            scope.state.contextMutable().add(out);
                                        }
                                        return saveStateToSession(scope).thenReturn(out);
                                    })
                            .switchIfEmpty(
                                    Mono.defer(
                                            () ->
                                                    saveStateToSession(scope)
                                                            .then(Mono.<Msg>empty())));
                });
    }

    private Msg wrapNativeStructuredResult(Msg result, JsonSchema schema) {
        if (result == null) {
            return null;
        }
        String text = result.getTextContent();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Native structured output is empty");
        }
        if (schema == null || schema.getSchema() == null) {
            throw new IllegalArgumentException("Native structured output schema is required");
        }

        JsonNode payload;
        try {
            payload = JsonUtils.getJsonCodec().fromJson(text, JsonNode.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Native structured output is not valid JSON: " + e.getMessage(), e);
        }

        if (validateStructuredOutput) {
            String validationError = ToolValidator.validateJsonOutput(payload, schema.getSchema());
            if (validationError != null) {
                throw new IllegalArgumentException(
                        "Native structured output failed schema validation: " + validationError);
            }
        }

        Object parsed = JsonUtils.getJsonCodec().convertValue(payload, Object.class);
        Map<String, Object> metadata =
                new HashMap<>(result.getMetadata() != null ? result.getMetadata() : Map.of());
        metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, parsed);
        return Msg.builderForRole(result.getRole())
                .id(result.getId())
                .name(result.getName())
                .content(result.getContent())
                .metadata(metadata)
                .timestamp(result.getTimestamp())
                .usage(result.getUsage())
                .build();
    }

    /**
     * 从会话上下文移除结构化输出相关消息，并追加最终回复。
     */
    private void compressStructuredOutputContext(AgentState agentState) {
        List<Msg> contextMutable = agentState.contextMutable();
        List<Msg> original = new ArrayList<>(contextMutable);
        contextMutable.clear();
        for (Msg msg : original) {
            if (!isStructuredOutputRelated(msg)) {
                contextMutable.add(msg);
            }
        }
    }

    private boolean isStructuredOutputRelated(Msg msg) {
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata != null
                && Boolean.TRUE.equals(
                        metadata.get(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER))) {
            return true;
        }
        if (msg.getContentBlocks(ToolUseBlock.class).stream()
                .anyMatch(tu -> STRUCTURED_OUTPUT_TOOL_NAME.equals(tu.getName()))) {
            return true;
        }
        List<ToolResultBlock> results = msg.getContentBlocks(ToolResultBlock.class);
        return !results.isEmpty()
                && results.stream()
                        .allMatch(tr -> STRUCTURED_OUTPUT_TOOL_NAME.equals(tr.getName()));
    }

    private ChatUsage collectAggregatedUsage(AgentState agentState) {
        int totalInput = 0;
        int totalOutput = 0;
        int totalCached = 0;
        int totalCacheCreation = 0;
        int totalReasoning = 0;
        int totalToolUsePrompt = 0;
        double totalTime = 0;
        boolean hasUsage = false;
        for (Msg msg : agentState.getContext()) {
            if (isStructuredOutputRelated(msg) && msg.getRole() == MsgRole.ASSISTANT) {
                ChatUsage usage = msg.getChatUsage();
                if (usage != null) {
                    hasUsage = true;
                    totalInput += usage.getInputTokens();
                    totalOutput += usage.getOutputTokens();
                    totalCached += usage.getCachedTokens();
                    totalCacheCreation += usage.getCacheCreationTokens();
                    totalReasoning += usage.getReasoningTokens();
                    totalToolUsePrompt += usage.getToolUsePromptTokens();
                    totalTime += usage.getTime();
                }
            }
        }
        return hasUsage
                ? ChatUsage.builder()
                        .inputTokens(totalInput)
                        .outputTokens(totalOutput)
                        .cachedTokens(totalCached)
                        .cacheCreationTokens(totalCacheCreation)
                        .reasoningTokens(totalReasoning)
                        .toolUsePromptTokens(totalToolUsePrompt)
                        .time(totalTime)
                        .build()
                : null;
    }

    private ThinkingBlock collectLastThinking(AgentState agentState) {
        ThinkingBlock last = null;
        for (Msg msg : agentState.getContext()) {
            if (isStructuredOutputRelated(msg) && msg.getRole() == MsgRole.ASSISTANT) {
                ThinkingBlock tb = msg.getFirstContentBlock(ThinkingBlock.class);
                if (tb != null) {
                    last = tb;
                }
            }
        }
        return last;
    }

    /**
     * 构建单次调用的 {@code generate_response} 工具，以捕获模型结构化回复。
     * 该工具只保存原始响应载荷；schema 校验由工具执行器在调用它之前完成。
     */
    private AgentTool createStructuredOutputTool(Map<String, Object> schema) {
        return new AgentTool() {
            @Override
            public String getName() {
                return STRUCTURED_OUTPUT_TOOL_NAME;
            }

            @Override
            public String getDescription() {
                return "Generate the final structured response. Call this function when"
                        + " you have all the information needed to provide a complete answer.";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> params = new HashMap<>();
                params.put("type", "object");

                // 浅拷贝内部 schema，便于安全地将 $defs 提升到外层参数
                // 根节点，而不修改共享的 `schema` 实例。
                Map<String, Object> innerSchema = new HashMap<>(schema);
                Map<String, Object> hoistedDefs = new HashMap<>();
                hoistDefsKey(innerSchema, "$defs", hoistedDefs);
                hoistDefsKey(innerSchema, "definitions", hoistedDefs);

                params.put("properties", Map.of("response", innerSchema));
                params.put("required", List.of("response"));
                if (!hoistedDefs.isEmpty()) {
                    params.put("$defs", hoistedDefs);
                }
                return params;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(
                        () -> {
                            Object responseData = param.getInput().get("response");
                            String contentText = "";
                            if (responseData != null) {
                                try {
                                    contentText = JsonUtils.getJsonCodec().toJson(responseData);
                                } catch (Exception e) {
                                    contentText = responseData.toString();
                                }
                            }
                            Msg responseMsg =
                                    AssistantMessage.builder()
                                            .name(getName())
                                            .content(TextBlock.builder().text(contentText).build())
                                            .metadata(
                                                    responseData != null
                                                            ? Map.of("response", responseData)
                                                            : Map.of())
                                            .build();

                            Map<String, Object> toolMetadata = new HashMap<>();
                            toolMetadata.put("success", true);
                            toolMetadata.put("response_msg", responseMsg);

                            return ToolResultBlock.of(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("Successfully generated response.")
                                                    .build()),
                                    toolMetadata);
                        });
            }
        };
    }

    /**
     * 从 {@code generate_response} 工具结果提取结构化结果消息。
     */
    private Msg extractStructuredResult(Msg hookResultMsg) {
        if (hookResultMsg == null) {
            return null;
        }
        List<ToolResultBlock> toolResults = hookResultMsg.getContentBlocks(ToolResultBlock.class);
        for (ToolResultBlock result : toolResults) {
            if (result.getMetadata() != null
                    && Boolean.TRUE.equals(result.getMetadata().get("success"))
                    && result.getMetadata().containsKey("response_msg")) {
                Object responseMsgObj = result.getMetadata().get("response_msg");
                Msg responseMsg = toMsg(responseMsgObj);
                if (responseMsg != null) {
                    return extractResponseData(responseMsg);
                }
            }
        }
        return hookResultMsg;
    }

    /**
     * 从元数据值恢复 {@link Msg}。
     *
     * <p>经过 JSON 持久化往返（智能体会话保存／加载）后，
     * {@code ToolResultBlock.metadata["response_msg"]} 中的类型化消息恢复为 {@code LinkedHashMap}，
     * 因此在此转回类型化形式。
     *
     * @param value 原始元数据值
     * @return 类型化消息；无法恢复时为 {@code null}
     */
    private static Msg toMsg(Object value) {
        if (value instanceof Msg msg) {
            return msg;
        }
        if (value instanceof Map<?, ?>) {
            try {
                return JsonUtils.getJsonCodec().convertValue(value, Msg.class);
            } catch (RuntimeException e) {
                log.warn("Failed to restore response_msg from tool result metadata", e);
                return null;
            }
        }
        return null;
    }

    private Msg extractResponseData(Msg responseMsg) {
        if (responseMsg.getMetadata() != null
                && responseMsg.getMetadata().containsKey("response")) {
            Object responseData = responseMsg.getMetadata().get("response");
            Map<String, Object> metadata = new HashMap<>(responseMsg.getMetadata());
            metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, responseData);
            metadata.remove("response");
            return Msg.builderForRole(responseMsg.getRole())
                    .id(responseMsg.getId())
                    .name(responseMsg.getName())
                    .content(responseMsg.getContent())
                    .metadata(metadata)
                    .timestamp(responseMsg.getTimestamp())
                    .usage(responseMsg.getUsage())
                    .build();
        }
        return responseMsg;
    }

    /**
     * 将汇总的 {@link ChatUsage}／{@link ThinkingBlock} 合并到最终消息。
     */
    private Msg mergeCollectedMetadata(Msg msg, ChatUsage chatUsage, ThinkingBlock thinking) {
        Map<String, Object> metadata =
                new HashMap<>(msg.getMetadata() != null ? msg.getMetadata() : Map.of());
        if (chatUsage != null) {
            metadata.put(MessageMetadataKeys.CHAT_USAGE, chatUsage);
        }

        List<ContentBlock> newContent;
        if (thinking != null) {
            newContent = new ArrayList<>();
            newContent.add(thinking);
            if (msg.getContent() != null) {
                newContent.addAll(msg.getContent());
            }
        } else {
            newContent = msg.getContent();
        }

        return Msg.builderForRole(msg.getRole())
                .id(msg.getId())
                .name(msg.getName())
                .content(newContent)
                .metadata(metadata)
                .timestamp(msg.getTimestamp())
                .usage(chatUsage)
                .build();
    }

    /**
     * 将嵌套 schema 中的 {@code $defs}／{@code definitions} 提升到参数根节点。
     */
    @SuppressWarnings("unchecked")
    private static void hoistDefsKey(
            Map<String, Object> innerSchema, String key, Map<String, Object> target) {
        Object raw = innerSchema.remove(key);
        if (raw instanceof Map<?, ?> defs && !defs.isEmpty()) {
            target.putAll((Map<String, Object>) defs);
        }
    }

    /**
     * 单次调用执行作用域：保存活动 {@code (userId, sessionId)} 槽位的可变 {@link AgentState}、
     * {@link PermissionEngine} 和槽位键，并承载完整 ReAct 推理循环。
     * 采用非静态内部类，使循环可直接访问外层智能体的不可变配置
     * （{@code model}、{@code toolkit}、{@code middlewares} 等）及生命周期辅助方法。
     * 由 {@link #activateSlotForContext(RuntimeContext)} 按调用创建。
     */
    final class CallExecution {
        private static final String PERMISSION_DENIED_BY_USER = "Permission denied by user";
        private static final String PERMISSION_DENIED_BY_RULES = "Permission denied by rules";
        private static final String OUTPUT_LIMIT_TOOL_ARGUMENTS =
                "Tool call not executed: the response stopped at its output token limit before"
                        + " complete arguments could be parsed. Do not repeat the same large"
                        + " arguments. Split the work into smaller tool calls or provide a"
                        + " shorter argument payload, then reissue the call.";
        private static final String INVALID_TOOL_ARGUMENTS =
                "Tool call not executed: its arguments were not a valid JSON object. Reissue"
                        + " the call with corrected JSON object arguments.";

        InterruptControl interruption = new InterruptControl();
        AgentState state;
        PermissionEngine permissionEngine;
        String slotKey;

        /**
         * 本次调用加载 {@link #state} 时观测到的存储版本，用于保存时 CAS。
         * 后端不支持版本时为 {@link AgentStateStore#UNVERSIONED}。
         */
        long loadedVersion;

        /**
         * 加载时的上下文大小，供 {@link ConflictPolicy#APPEND_MERGE} 使用。
         */
        int loadedContextSize;

        /**
         * 单次调用系统消息，在 PreCallEvent → PreReasoningEvent／PreSummaryEvent 之间传递。
         * 属于一次逻辑执行：在调用入口（{@code #beforeAgentExecution(List)}）初始化为 {@code null}，
         * 由 {@link #consumeSystemMsgAfterPreCall(Msg, Object)} 设置。
         */
        Msg systemMsg;

        /**
         * {@code streamEvents} 的单次调用事件 sink。在 {@link ReActAgent#doCall(List)} 中
         * 从按订阅 Reactor Context（{@code EVENT_SINK_KEY}）绑定，由 {@link #publishEvent} 读取。
         */
        FluxSink<AgentEvent> eventSink;

        ExecutionIdentity executionIdentity;

        /**
         * 转发子智能体事件的外部事件发射器。父级工具（例如 {@code agent_spawn}）
         * 通过 {@link AgentEventEmitter#FORWARDING_CONTEXT_KEY} 注入转发发射器时，
         * 子智能体使用它向父级 {@code streamEvents()} 流推送附带来源标签的事件。
         * 仅在 {@link #eventSink} 为 null 时生效。
         */
        AgentEventEmitter externalEventEmitter;

        /**
         * 本次调用的 {@link RuntimeContext}（调用方为钩子／工具提供的元数据）。
         * 在调用入口设置一次，由推理循环直接读取，而非读取实例级 {@code rc}，使循环在每次调用内自包含。
         */
        RuntimeContext rc;

        /**
         * 指向智能体共享 {@code toolkit} 的稳定单次调用引用，在 {@code beforeAgentExecution} 中解析一次。
         * 工具集不会按调用复制或修改；单次调用工具差异存于 {@link #toolRequestConfig}，按需与工具集组合，
         * 同一智能体的并发调用不会看到彼此的差异。此作用域所有工具集访问统一读取 {@code activeToolkit}。
         */
        Toolkit activeToolkit;

        /**
         * 单次调用工具请求配置（{@link ToolRequestConfig#NONE} 表示原样使用共享工具集）。
         * 携带不可变的单次调用工具差异（外部工具及合并模式）；共享 {@link #activeToolkit} 从不复制或修改。
         */
        ToolRequestConfig toolRequestConfig = ToolRequestConfig.NONE;

        /**
         * 单次调用结构化输出工具（{@code generate_response}）。
         * 仅在回退式结构化输出调用中非空（模型不支持原生结构化输出时）。
         * 存于此作用域而非共享工具集，避免并发结构化输出调用冲突。
         */
        AgentTool soTool;

        /**
         * {@code generate_response} 工具成功完成时设为 {@code true}。
         */
        boolean soCompleted;

        /**
         * 成功 {@code generate_response} 调用返回的工具结果消息。
         */
        Msg soResultMsg;

        /**
         * 强制 {@code generate_response} 调用的累计次数。{@code 0} 表示从未强制；
         * 大于 {@code 0} 时，下一轮推理消费该标志以强制 {@code tool_choice}（或提示提醒）。
         * 此值也用作重试上限：达到 {@code 3} 时停止强制，不带结构化数据结束，避免死循环。
         */
        int soForceToolChoiceCount;

        /**
         * 写入 returnDirect 工具 tool_result 的占位句。
         */
        private static final String RETURN_DIRECT_PLACEHOLDER =
                "Tool call completed. The result has been presented to the user as the final output"
                        + " of this turn.";

        /**
         * 在原生路径调用的单次调用作用域中设置的原生结构化输出格式。
         */
        ResponseFormat nativeResponseFormat;

        CallExecution(AgentState state, PermissionEngine permissionEngine, String slotKey) {
            this(state, permissionEngine, slotKey, AgentStateStore.UNVERSIONED);
        }

        CallExecution(
                AgentState state,
                PermissionEngine permissionEngine,
                String slotKey,
                long loadedVersion) {
            this.state = state;
            this.permissionEngine = permissionEngine;
            this.slotKey = slotKey;
            this.loadedVersion = loadedVersion;
            this.loadedContextSize = state != null ? state.getContext().size() : 0;
        }

        /**
         * 读取本次执行自己的信号；排队中的调用绝不共享取消状态。
         */
        private Mono<Void> checkInterrupted() {
            return Mono.defer(
                    () ->
                            interruption.isInterrupted()
                                    ? Mono.error(
                                            new InterruptedException("Agent execution interrupted"))
                                    : Mono.empty());
        }

        private Mono<Msg> doCallInner(List<Msg> msgs) {
            // 优雅关闭去重：如果智能体会话先前被关闭流程中断，
            // 客户端很可能使用已存在于记忆中的相同用户提示重试。
            // 丢弃重复输入，使智能体仅从
            // 已保存的记忆上下文恢复。
            if (shutdownManager.checkAndClearShutdownInterruptedForState(state)) {
                log.info(
                        "Detected shutdown-interrupted session for agent {}, discarding duplicate"
                                + " input",
                        getName());
                msgs = List.of();
            }

            Set<String> pendingIds = MessageUtils.pendingToolUseIds(state.contextMutable());

            // 没有待处理工具，正常处理
            if (pendingIds.isEmpty()) {
                addToContext(msgs);
                return coreAgent();
            }

            // 权限 HITL：任何待处理工具处于 ASKING 时，调用方必须先通过
            // Msg.METADATA_CONFIRM_RESULTS 提供 ConfirmResults，才能继续。
            List<ToolUseBlock> asking = askingToolCalls();
            if (!asking.isEmpty()) {
                validateAndAcceptConfirmResults(msgs, asking);
                return resumeAgent();
            }

            // 待处理工具调用恢复：为孤立的待处理工具调用自动补充合成
            // 错误结果，让智能体继续而非崩溃。必须在
            // 权限 HITL 流程之后执行，使 ASKING 调用优先由确认流程处理。
            if (enablePendingToolRecovery) {
                maybePatchPendingToolCalls(msgs, pendingIds);
                pendingIds = MessageUtils.pendingToolUseIds(state.contextMutable());
                if (pendingIds.isEmpty()) {
                    addToContext(msgs);
                    return coreAgent();
                }
            }

            // 存在待处理工具但无输入：恢复并直接执行待处理工具
            if (msgs == null || msgs.isEmpty()) {
                return resumeAgent();
            }

            // 存在待处理工具且有输入：检查用户是否提供工具结果
            List<ToolResultBlock> providedResults =
                    msgs.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();

            if (!providedResults.isEmpty()) {
                // 用户提供了工具结果：验证后添加。持久化前将待处理
                // tool_use 批次与提供的结果关联，以便
                // returnDirect 短路（与框架内路径采用相同判定）能
                // 直接持久化占位形式的 tool_result。仅本次恢复
                // 完整解决的批次才短路；部分解决的批次（跨
                // 先前恢复，或与框架执行工具混合）始终反馈
                // 给模型。
                List<ToolUseBlock> pendingBatch =
                        MessageUtils.extractPendingToolCalls(state.contextMutable(), getName());
                List<ToolUseBlock> originalBatch =
                        MessageUtils.extractRecentToolCalls(state.contextMutable(), getName());
                String externalReplyId =
                        resolvePendingRequestReplyId(
                                Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID);
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> externalPairs =
                        pairProvidedToolResults(pendingBatch, providedResults);
                boolean returnDirectResume =
                        !originalBatch.isEmpty()
                                && pendingBatch.size() == originalBatch.size()
                                && externalPairs.size() == pendingBatch.size()
                                && externalPairs.stream().allMatch(this::isReturnDirectToolCall);
                // 根据调用方原始消息验证并发布，使
                // ExternalExecutionResultEvent 携带真实的外部结果；
                // 短路时仅存入上下文的内容被替换为占位形式。
                validateAndAddToolResults(
                        msgs,
                        returnDirectResume ? placeholderToolResultMsgs(msgs) : msgs,
                        pendingIds);
                if (returnDirectResume) {
                    return settleSession(
                            Mono.just(finalizeReturnDirect(externalPairs, externalReplyId)));
                }
                return !MessageUtils.pendingToolUseIds(state.contextMutable()).isEmpty()
                        ? resumeAgent()
                        : coreAgent();
            }

            // 恢复功能被禁用，且用户未提供工具结果，无法恢复。
            throw new IllegalStateException(
                    "Pending tool calls exist without results. "
                            + "Enable enablePendingToolRecovery or provide tool results. "
                            + "Pending IDs: "
                            + pendingIds);
        }

        /**
         * 从传入消息列表的 {@link Msg#METADATA_CONFIRM_RESULTS} 元数据键中提取全部 {@link ConfirmResult}。
         */
        @SuppressWarnings("unchecked")
        private List<ConfirmResult> extractConfirmResults(List<Msg> msgs) {
            if (msgs == null || msgs.isEmpty()) {
                return List.of();
            }
            List<ConfirmResult> collected = new ArrayList<>();
            for (Msg m : msgs) {
                Object raw =
                        m.getMetadata() == null
                                ? null
                                : m.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
                if (raw instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof ConfirmResult cr) {
                            collected.add(cr);
                        } else if (o instanceof Map<?, ?>) {
                            collected.add(
                                    JsonUtils.getJsonCodec().convertValue(o, ConfirmResult.class));
                        }
                    }
                }
            }
            return collected;
        }

        /**
         * 根据当前 ASKING 工具调用验证并接收权限 HITL 恢复载荷。
         *
         * <p>权限 HITL 通过一个或多个针对当前 ASKING 工具调用的确认恢复。
         * 确认可以只覆盖部分 ASKING 调用，但不能引用过期或无关调用。
         * 接收后，将规范化结果应用到智能体状态，并发出关联的恢复事件。
         */
        private void validateAndAcceptConfirmResults(List<Msg> msgs, List<ToolUseBlock> asking) {
            List<ConfirmResult> results = extractConfirmResults(msgs);
            if (results.isEmpty()) {
                String pendingSummary =
                        asking.stream()
                                .map(t -> t.getName() + " (id=" + t.getId() + ")")
                                .collect(Collectors.joining(", "));
                throw new IllegalStateException(
                        "Agent is paused for human-in-the-loop confirmation: the following"
                                + " tool call(s) are in ASKING state and need your approval"
                                + " before the agent can continue: ["
                                + pendingSummary
                                + "]. This call supplied no confirmation, so it cannot"
                                + " proceed.\n"
                                + "To resume, send a follow-up message that carries a"
                                + " List<ConfirmResult> under the metadata key \""
                                + Msg.METADATA_CONFIRM_RESULTS
                                + "\", e.g.:\n"
                                + "    UserMessage.builder()\n"
                                + "        .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,\n"
                                + "            List.of(new ConfirmResult(true, toolCall))))\n"
                                + "        .build();\n"
                                + "Tip: capture the ToolUseBlocks from the"
                                + " RequireUserConfirmEvent emitted when the agent paused.\n"
                                + "If you did NOT expect a pending confirmation here, a"
                                + " previous run most likely paused on one of these tool calls"
                                + " and persisted that state under the same (agentId,"
                                + " sessionId); start a fresh session, clear the persisted"
                                + " state, or use an in-memory state store to begin clean.");
            }

            Set<String> expectedIds =
                    asking.stream()
                            .map(ToolUseBlock::getId)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toCollection(LinkedHashSet::new));

            Set<String> providedIds = new LinkedHashSet<>();
            List<ConfirmResult> normalized = new ArrayList<>();

            for (ConfirmResult result : results) {
                if (result == null || result.getToolCall() == null) {
                    throw new IllegalStateException(
                            "ConfirmResult and ConfirmResult.toolCall must not be null.");
                }
                ToolUseBlock toolCall = result.getToolCall();
                String toolCallId = toolCall.getId();
                if (toolCallId == null || toolCallId.isEmpty()) {
                    throw new IllegalStateException("ConfirmResult.toolCall.id must not be empty.");
                }
                if (!providedIds.add(toolCallId)) {
                    throw new IllegalStateException(
                            "Duplicate ConfirmResult for tool call ID: " + toolCallId);
                }
                if (!expectedIds.contains(toolCallId)) {
                    throw new IllegalStateException(
                            "ConfirmResult references non-ASKING tool call ID: "
                                    + toolCallId
                                    + ". Expected: "
                                    + expectedIds);
                }
                normalized.add(result);
            }

            String replyId = resolvePendingRequestReplyId(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID);
            if (replyId.isEmpty()) {
                replyId = UUID.randomUUID().toString().replace("-", "");
                log.warn("Missing confirmation reply id; generated fallback {}", replyId);
            }
            publishEvent(new UserConfirmResultEvent(replyId, normalized));
            clearPendingRequestReplyId(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID);

            applyConfirmResults(normalized, replyId);
        }

        /**
         * 解析最后一条助手消息中待处理 HITL 请求的回复 ID。
         */
        private String resolvePendingRequestReplyId(String metadataKey) {
            Msg requestMsg = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (requestMsg == null || requestMsg.getMetadata() == null) {
                return "";
            }
            Object raw = requestMsg.getMetadata().get(metadataKey);
            return raw instanceof String s ? s : "";
        }

        /**
         * 将待处理 HITL 请求的回复 ID 持久化到实时助手消息。
         *
         * <p>助手消息拥有暂停的 {@link ToolUseBlock}，将关联元数据存于此处，
         * 使下一次调用能从会话状态恢复它。
         */
        private void persistPendingRequestReplyId(String metadataKey, String replyId) {
            Msg lastAssistant = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (lastAssistant == null) {
                return;
            }
            Map<String, Object> metadata = new HashMap<>(lastAssistant.getMetadata());
            metadata.put(metadataKey, replyId);
            MessageUtils.replaceLastMessageByRole(
                    state.contextMutable(),
                    MsgRole.ASSISTANT,
                    lastAssistant.withMetadata(metadata));
        }

        /**
         * 恢复载荷被接收后移除 HITL 关联元数据。
         */
        private void clearPendingRequestReplyId(String metadataKey) {
            Msg lastAssistant = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (lastAssistant == null || lastAssistant.getMetadata() == null) {
                return;
            }
            if (!lastAssistant.getMetadata().containsKey(metadataKey)) {
                return;
            }
            Map<String, Object> metadata = new HashMap<>(lastAssistant.getMetadata());
            metadata.remove(metadataKey);
            MessageUtils.replaceLastMessageByRole(
                    state.contextMutable(),
                    MsgRole.ASSISTANT,
                    lastAssistant.withMetadata(metadata));
        }

        /**
         * 将用户确认结果应用于上下文中的 ASKING 工具调用。
         *
         * <p>针对每个结果：
         * <ul>
         * <li>{@code confirmed == true}：用结果中的（可能已修改的）ToolUseBlock 替换 ASKING 块，
         * 将状态设为 {@link ToolCallState#ALLOWED}，并向引擎注册附带的 {@link PermissionRule}。</li>
         * <li>{@code confirmed == false}：向上下文写入 DENIED {@link ToolResultBlock}，
         * 使工具在恢复时不再待处理；若提供了用户 {@link ConfirmResult#getReason() reason} 则采用它，
         * 并发布完整工具结果事件生命周期。</li>
         * </ul>
         */
        private void applyConfirmResults(List<ConfirmResult> results, String replyId) {
            // 用用户提供的可能已修改的块替换 ASKING ToolUseBlock，
            // 将其提升为 ALLOWED。收集被拒绝的调用另行处理。
            List<Map.Entry<ToolUseBlock, String>> deniedToolCalls = new ArrayList<>();
            Map<String, ToolUseBlock> replacements = new HashMap<>();
            for (ConfirmResult r : results) {
                ToolUseBlock target = r.getToolCall();
                if (target == null) {
                    continue;
                }
                if (r.isConfirmed()) {
                    replacements.put(target.getId(), target.withState(ToolCallState.ALLOWED));
                    if (r.getRules() != null) {
                        for (PermissionRule rule : r.getRules()) {
                            if (rule != null) {
                                permissionEngine.addRule(rule);
                            }
                        }
                    }
                } else {
                    String reason = r.getReason();
                    deniedToolCalls.add(
                            Map.entry(
                                    target,
                                    reason == null || reason.isBlank()
                                            ? PERMISSION_DENIED_BY_USER
                                            : reason));
                }
            }
            MessageUtils.replaceToolUseBlocks(state.contextMutable(), replacements);
            for (Map.Entry<ToolUseBlock, String> entry : deniedToolCalls) {
                ToolUseBlock denied = entry.getKey();
                String reasonText = entry.getValue();
                ToolResultBlock deniedResult =
                        ToolResultBlock.text(reasonText)
                                .withIdAndName(denied.getId(), denied.getName())
                                .withState(ToolResultState.DENIED);
                Msg deniedMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                deniedResult, denied, getName());
                state.contextMutable().add(deniedMsg);
                deniedToolResultEvents(denied, replyId, reasonText).forEach(this::publishEvent);
            }
        }

        /**
         * 为任意权限拒绝路径构建完整 DENIED 工具结果生命周期。
         */
        private List<AgentEvent> deniedToolResultEvents(
                ToolUseBlock toolCall, String replyId, String reason) {
            return List.of(
                    new ToolResultStartEvent(replyId, toolCall.getId(), toolCall.getName()),
                    new ToolResultTextDeltaEvent(
                            replyId, toolCall.getId(), toolCall.getName(), reason),
                    new ToolResultEndEvent(
                            replyId, toolCall.getId(), toolCall.getName(), ToolResultState.DENIED));
        }

        private void maybePatchPendingToolCalls(List<Msg> msgs, Set<String> pendingIds) {
            if (pendingIds.isEmpty()) {
                return;
            }
            if (msgs == null || msgs.isEmpty()) {
                return;
            }
            boolean userProvidedResults =
                    msgs.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
            if (userProvidedResults) {
                return;
            }
            Msg lastAssistant = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (lastAssistant == null) {
                return;
            }
            List<ToolUseBlock> pendingToolCalls =
                    lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                            .filter(toolUse -> pendingIds.contains(toolUse.getId()))
                            .filter(toolUse -> toolUse.getState() != ToolCallState.ASKING)
                            .toList();
            if (pendingToolCalls.isEmpty()) {
                return;
            }
            log.warn(
                    "Pending tool calls detected without results, auto-generating error results."
                            + " Pending IDs: {}",
                    pendingToolCalls.stream().map(ToolUseBlock::getId).toList());
            for (ToolUseBlock toolCall : pendingToolCalls) {
                ToolResultBlock errorResult =
                        ToolResultBlock.error(
                                toolCall.getId(),
                                "Previous tool execution failed or was interrupted. Tool: "
                                        + toolCall.getName());
                Msg toolResultMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                errorResult, toolCall, getName());
                state.contextMutable().add(toolResultMsg);
                log.info(
                        "Auto-generated error result for pending tool call: {} ({})",
                        toolCall.getName(),
                        toolCall.getId());
            }
        }

        /**
         * 为最后一条助手消息中没有匹配结果的待处理工具调用合成错误 {@link ToolResultBlock}。
         *
         * <p>由 {@link ReActAgent#handleInterrupt} 在追加恢复消息之前调用，确保在推理
         * （写入助手 {@code tool_use}）与行动（产生工具结果）之间收到中断时，
         * 不会持久化缺少匹配 {@link ToolResultBlock} 的 {@link ToolUseBlock}。
         * 否则下一次恢复运行将继承不一致上下文，提供方可能返回空响应。
         *
         * <p>必须在添加恢复消息之前运行，否则恢复消息成为最后一条助手消息，待处理工具检测就无法再发现这些调用。
         */
        private void synthesizeErrorResultsForPendingToolCalls() {
            List<ToolUseBlock> pendingToolCalls =
                    MessageUtils.extractPendingToolCalls(state.contextMutable(), getName());
            if (pendingToolCalls.isEmpty()) {
                return;
            }
            for (ToolUseBlock toolCall : pendingToolCalls) {
                ToolResultBlock errorResult =
                        ToolResultBlock.error(
                                toolCall.getId(),
                                "Tool execution was interrupted before it could run. Tool: "
                                        + toolCall.getName());
                Msg toolResultMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(
                                errorResult, toolCall, getName());
                state.contextMutable().add(toolResultMsg);
                log.info(
                        "Synthesized interrupted-result for pending tool call: {} ({})",
                        toolCall.getName(),
                        toolCall.getId());
            }
        }

        private void publishEvent(AgentEvent event) {
            if (event.getSource() == null || event.getSource().isBlank())
                event.withExecution(executionIdentity);
            SessionRecorder recorder = SessionRecorder.from(rc);
            if (recorder != null) recorder.observe(event);
            FluxSink<AgentEvent> sink = eventSink;
            if (sink != null) {
                sink.next(event);
            } else if (externalEventEmitter != null) {
                externalEventEmitter.emit(event);
            }
        }

        /**
         * 存在待处理工具调用时验证输入消息，再加入上下文。
         *
         * <p>验证规则：
         * <ul>
         * <li>空输入：无操作（将继续进入行动）</li>
         * <li>无工具结果：抛出错误</li>
         * <li>有工具结果：验证 ID 与待处理调用匹配且无重复</li>
         * <li>部分结果加文本内容：抛出错误（仅全部工具完成后允许文本）</li>
         * </ul>
         *
         * <p>验证及发布的 {@link ExternalExecutionResultEvent} 始终反映调用方提供的 {@code msgs}。
         * {@code msgsToPersist} 才是存入上下文的消息，仅 returnDirect 短路时可能不同，此时工具结果被替换为占位副本。
         *
         * @param msgs 要验证并发布的输入消息
         * @param msgsToPersist 要加入上下文的消息（可能已替换为占位内容）
         * @param pendingIds 待处理工具调用 ID 集合
         * @throws IllegalStateException 验证失败时抛出
         */
        private void validateAndAddToolResults(
                List<Msg> msgs, List<Msg> msgsToPersist, Set<String> pendingIds) {
            if (msgs == null || msgs.isEmpty()) {
                return;
            }

            List<ToolResultBlock> results =
                    msgs.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();

            if (results.isEmpty()) {
                throw new IllegalStateException(
                        "Cannot add messages without tool results when pending tool calls exist. "
                                + "Pending IDs: "
                                + pendingIds);
            }

            // 检查重复 ID
            Set<String> providedIds = new HashSet<>();
            for (ToolResultBlock r : results) {
                if (!providedIds.add(r.getId())) {
                    throw new IllegalStateException("Duplicate tool result ID: " + r.getId());
                }
            }

            // 检查所有提供的 ID 都匹配待处理 ID
            Set<String> invalidIds =
                    providedIds.stream()
                            .filter(id -> !pendingIds.contains(id))
                            .collect(Collectors.toSet());
            if (!invalidIds.isEmpty()) {
                throw new IllegalStateException(
                        "Invalid tool result IDs: " + invalidIds + ". Expected: " + pendingIds);
            }

            // 检查非 ToolResultBlock 内容
            boolean hasTextContent =
                    msgs.stream()
                            .flatMap(m -> m.getContent().stream())
                            .anyMatch(block -> !(block instanceof ToolResultBlock));

            // 仅提供部分结果时不允许文本内容
            boolean isPartialResults = !providedIds.containsAll(pendingIds);
            if (isPartialResults && hasTextContent) {
                throw new IllegalStateException(
                        "Cannot include text content when providing partial tool results. "
                                + "Provided: "
                                + providedIds
                                + ", Pending: "
                                + pendingIds);
            }
            String replyId =
                    resolvePendingRequestReplyId(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID);
            if (!replyId.isEmpty()) {
                publishEvent(new ExternalExecutionResultEvent(replyId, results));
                clearPendingRequestReplyId(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID);
            }
            state.contextMutable().addAll(msgsToPersist);
        }

        /**
         * 按 ID 将待处理 tool_use 批次（保持声明顺序）与调用方提供的工具结果关联。
         * 仅返回有匹配结果的配对；部分结果产生的配对数少于批次数量。
         */
        private List<Map.Entry<ToolUseBlock, ToolResultBlock>> pairProvidedToolResults(
                List<ToolUseBlock> pendingBatch, List<ToolResultBlock> providedResults) {
            Map<String, ToolResultBlock> resultsById = new HashMap<>();
            for (ToolResultBlock result : providedResults) {
                resultsById.put(result.getId(), result);
            }
            List<Map.Entry<ToolUseBlock, ToolResultBlock>> pairs = new ArrayList<>();
            for (ToolUseBlock toolUse : pendingBatch) {
                ToolResultBlock result = resultsById.get(toolUse.getId());
                if (result != null) {
                    pairs.add(Map.entry(toolUse, result));
                }
            }
            return pairs;
        }

        /**
         * 为 returnDirect 短路构建调用方消息的占位副本：将每个 {@link ToolResultBlock} 的输出
         * 替换为 returnDirect 占位句（保留 ID／名称，像框架内路径一样通过
         * {@link #determineToolResultState(ToolResultBlock)} 规范化状态），非工具结果内容原样通过。
         * 构建新的 {@link Msg}，绝不修改调用方对象，保持上下文不变量
         * {@code [tool_use, tool_result(placeholder), assistant(full result)]}。
         */
        private List<Msg> placeholderToolResultMsgs(List<Msg> msgs) {
            List<Msg> replaced = new ArrayList<>(msgs.size());
            for (Msg msg : msgs) {
                replaced.add(
                        msg.withContent(
                                msg.getContent().stream()
                                        .map(this::placeholderToolResultContent)
                                        .toList()));
            }
            return replaced;
        }

        private ContentBlock placeholderToolResultContent(ContentBlock block) {
            if (!(block instanceof ToolResultBlock result)) {
                return block;
            }
            return placeholderResultBlock(
                    result.getId(), result.getName(), determineToolResultState(result));
        }

        /**
         * 消息非 null 时，将其加入智能体状态上下文。
         *
         * @param msgs 要添加的消息
         */
        private void addToContext(List<Msg> msgs) {
            if (msgs != null) {
                state.contextMutable().addAll(msgs);
            }
        }

        // ==================== 核心 ReAct 循环 ====================

        /**
         * 新智能体调用的入口：从第 0 次迭代启动 ReAct 循环。
         */
        private Mono<Msg> coreAgent() {
            return settleSession(executeIteration(0));
        }

        /**
         * 上一轮仍有待处理工具调用时的恢复入口：不再进行一次推理，直接跳入行动阶段。
         */
        private Mono<Msg> resumeAgent() {
            return settleSession(acting(0));
        }

        private int lastSessionIteration;

        private Mono<Msg> settleSession(Mono<Msg> work) {
            return work.flatMap(
                    result -> {
                        SessionExecution execution = SessionExecution.from(rc);
                        if (execution == null
                                || !SessionRecorder.outcome(result.getGenerateReason())
                                        .equals("completed")) return Mono.just(result);
                        return Mono.fromCallable(
                                        () -> execution.continueOrClose(SessionRecorder.from(rc)))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(
                                        again ->
                                                again
                                                        ? settleSession(
                                                                executeIteration(
                                                                        lastSessionIteration + 1))
                                                        : Mono.just(result));
                    });
        }

        private Mono<Msg> executeIteration(int iter) {
            return reasoning(iter, false);
        }

        /**
         * 执行推理阶段。
         *
         * <p>从模型流式读取、累积片段、通知钩子，并决定继续行动还是提前返回
         * （HITL 停止、gotoReasoning 或已完成）。
         *
         * @param iter 当前迭代编号
         * @param ignoreMaxIters 为 true 时跳过 maxIters 检查（用于 gotoReasoning 及结构化输出强制重试）
         * @return 包含最终结果消息的 Mono
         */
        private Mono<Msg> reasoning(int iter, boolean ignoreMaxIters) {
            // 【学习提示】一轮推理负责“向模型提问并解释回答”，而不是直接执行工具。
            // 本轮新建 ReasoningContext 累积增量，随后依据完整消息选择行动、结束或重新推理。
            lastSessionIteration = iter;
            // 除非设置 ignoreMaxIters，否则检查 maxIters
            if (!ignoreMaxIters && iter >= maxIters) {
                return summarizing();
            }

            SessionRecorder recorder = SessionRecorder.from(rc);
            if (recorder != null) recorder.step(iter);
            ReasoningContext context = new ReasoningContext(getName());

            return checkInterrupted()
                    .then(
                            Mono.fromRunnable(
                                            () -> {
                                                SessionExecution execution =
                                                        SessionExecution.from(rc);
                                                if (execution != null)
                                                    execution.beforeStep(recorder, state);
                                            })
                                    .subscribeOn(Schedulers.boundedElastic()))
                    .then(
                            Mono.defer(
                                    () ->
                                            hookDispatcher.firePreReasoning(
                                                    state.contextMutable(),
                                                    systemMsg,
                                                    model.getModelName())))
                    .flatMap(
                            event -> {
                                GenerateOptions options =
                                        event.getEffectiveGenerateOptions() != null
                                                ? event.getEffectiveGenerateOptions()
                                                : buildGenerateOptions();
                                if (nativeResponseFormat != null && soTool == null) {
                                    options =
                                            GenerateOptions.mergeOptions(
                                                    GenerateOptions.builder()
                                                            .responseFormat(nativeResponseFormat)
                                                            .build(),
                                                    options);
                                }
                                // 上一轮结束却未调用 generate_response 时，
                                // 强制调用它。与上面的 nativeResponseFormat 互斥：
                                // soForceToolChoiceCount 只在回退路径设置
                                // （soTool != null），该路径的 nativeResponseFormat 始终为 null。
                                if (soForceToolChoiceCount > 0
                                        && soTool != null
                                        && model.supportsToolChoiceSpecific()) {
                                    options =
                                            GenerateOptions.mergeOptions(
                                                    GenerateOptions.builder()
                                                            .toolChoice(
                                                                    new ToolChoice.Specific(
                                                                            STRUCTURED_OUTPUT_TOOL_NAME))
                                                            .build(),
                                                    options);
                                }
                                List<Msg> modelInput =
                                        MessageUtils.prependSystemMessage(
                                                event.getInputMessages(), event.getSystemMessage());
                                List<ToolSchema> tools =
                                        activeToolkit.getToolSchemas(
                                                state.getToolContext().getActivatedGroups(),
                                                toolRequestConfig);
                                // 单次调用结构化输出工具：只在本次调用中向
                                // 模型暴露 generate_response，不注册到共享工具集。
                                if (soTool != null) {
                                    tools = new ArrayList<>(tools);
                                    tools.add(
                                            ToolSchema.builder()
                                                    .name(soTool.getName())
                                                    .description(soTool.getDescription())
                                                    .parameters(soTool.getParameters())
                                                    .strict(soTool.getStrict())
                                                    .outputSchema(soTool.getOutputSchema())
                                                    .build());
                                }
                                Function<ReasoningInput, Flux<AgentEvent>> reasoningCore =
                                        ri ->
                                                reasoningStream(
                                                        context,
                                                        ri.messages(),
                                                        ri.tools(),
                                                        ri.options());
                                Flux<AgentEvent> stream =
                                        MiddlewareChain.build(
                                                        middlewaresAt(
                                                                MiddlewareBase.ExtensionPoint
                                                                        .ON_REASONING),
                                                        ReActAgent.this,
                                                        rc,
                                                        MiddlewareBase::onReasoning,
                                                        reasoningCore)
                                                .apply(
                                                        new ReasoningInput(
                                                                modelInput, tools, options));
                                // 跟踪中间件发出的所有 RequestStopEvent，同时仍
                                // 消费完整流。在外层推理边界发布，
                                // 使 onReasoning 中间件追加的事件也能转发。
                                AtomicReference<RequestStopEvent> stopRequested =
                                        new AtomicReference<>();
                                return stream.doOnNext(this::publishEvent)
                                        .doOnNext(
                                                ev -> {
                                                    if (ev instanceof RequestStopEvent rs) {
                                                        stopRequested.compareAndSet(null, rs);
                                                    }
                                                })
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            Msg finalMsg =
                                                                    context.buildFinalMessage();
                                                            RequestStopEvent rs =
                                                                    stopRequested.get();
                                                            if (rs != null && finalMsg != null) {
                                                                // 持久化推理消息
                                                                // 然后
                                                                // 才返回，确保下一次调用可以
                                                                // 恢复
                                                                // 待处理工具调用。
                                                                state.contextMutable()
                                                                        .add(finalMsg);
                                                                return Mono.just(
                                                                        finalMsg.withGenerateReason(
                                                                                rs
                                                                                        .getGenerateReason()));
                                                            }
                                                            return Mono.justOrEmpty(finalMsg);
                                                        }));
                            })
                    .onErrorResume(
                            InterruptedException.class,
                            error -> {
                                Msg msg = context.buildFinalMessage();
                                if (msg != null) {
                                    boolean discard =
                                            interruption.getSource() == InterruptSource.SYSTEM
                                                    && shutdownManager
                                                                    .getConfig()
                                                                    .partialReasoningPolicy()
                                                            == PartialReasoningPolicy.DISCARD;
                                    if (!discard) {
                                        state.contextMutable().add(msg);
                                    }
                                }
                                return Mono.error(error);
                            })
                    .flatMap(
                            msg -> {
                                // 短路：中间件在推理期间请求停止。消息
                                // 此时
                                // 已持久化到上下文并标记正确的
                                // GenerateReason；跳过旧式 postReasoning 钩子。
                                if (msg.getGenerateReason()
                                                == GenerateReason.MIDDLEWARE_STOP_REQUESTED
                                        || msg.getGenerateReason()
                                                == GenerateReason.PERMISSION_ASKING) {
                                    return Mono.just(msg);
                                }
                                return runPostReasoningPipeline(msg, iter);
                            });
        }

        @SuppressWarnings("deprecation")
        private Mono<Msg> runPostReasoningPipeline(Msg msg, int iter) {
            return hookDispatcher
                    .firePostReasoning(msg, model.getModelName())
                    .flatMap(
                            event -> {
                                Msg eventMsg = event.getReasoningMessage();
                                if (eventMsg != null) {
                                    state.contextMutable().add(eventMsg);
                                }

                                // HITL 停止
                                if (event.isStopRequested()) {
                                    if (eventMsg == null) {
                                        return Mono.empty();
                                    }
                                    return Mono.just(
                                            eventMsg.withGenerateReason(
                                                    GenerateReason.REASONING_STOP_REQUESTED));
                                }

                                // 请求 gotoReasoning（例如来自 PostReasoning 钩子）
                                if (event.isGotoReasoningRequested()) {
                                    List<Msg> gotoMsgs = event.getGotoReasoningMsgs();
                                    if (gotoMsgs != null) {
                                        state.contextMutable().addAll(gotoMsgs);
                                    }
                                    return reasoning(iter + 1, true);
                                }

                                // 检查结束条件
                                if (isFinished(eventMsg)) {
                                    // 结构化输出仍未完成：强制模型调用
                                    // generate_response。最多重试 3 次，然后放弃，
                                    // 避免模型拒绝调用时陷入死循环。
                                    if (soTool != null && !soCompleted) {
                                        if (soForceToolChoiceCount < 3) {
                                            soForceToolChoiceCount++;
                                            log.debug(
                                                    "Structured output still pending after finish;"
                                                            + " forcing retry {} (agent={}#{})",
                                                    soForceToolChoiceCount,
                                                    getAgentId(),
                                                    getName());
                                            // 有意使用 ignoreMaxIters：强制重试
                                            // 不能被 maxIters 提前截断（例如
                                            // maxIters=1 的调用方仍应获得待执行的
                                            // generate_response），额外模型调用总量
                                            // 仍受上述 3 次重试上限约束。
                                            return reasoning(iter + 1, true);
                                        }
                                        log.warn(
                                                "Structured output tool '{}' not called after {} "
                                                        + "forced retries; finishing without "
                                                        + "structured data (agent={}#{})",
                                                STRUCTURED_OUTPUT_TOOL_NAME,
                                                soForceToolChoiceCount,
                                                getAgentId(),
                                                getName());
                                    }
                                    return Mono.justOrEmpty(eventMsg);
                                }

                                // 最终回复为空：没有工具调用，也没有可见内容
                                // （例如推理模型将完整答案写入思考
                                // 通道，却让内容通道为空）。注入合成提醒
                                // 并重新进入推理。
                                if (!MessageUtils.hasToolCalls(eventMsg)) {
                                    log.warn(
                                            "Final response has no visible content (empty reply),"
                                                    + " model: {}, iter: {}",
                                            model.getModelName(),
                                            iter);
                                    state.contextMutable().add(buildEmptyResponseReminder());
                                }

                                // 继续进入行动
                                return checkInterrupted().then(acting(iter));
                            })
                    .switchIfEmpty(
                            Mono.defer(
                                    () -> {
                                        // 未产生消息
                                        return Mono.justOrEmpty((Msg) null);
                                    }));
        }

        /**
         * 流式发送推理阶段模型调用产生的细粒度 {@link AgentEvent}。
         *
         * <p>事件顺序：{@link ModelCallStartEvent} → 块开始／增量／结束事件 → {@link ModelCallEndEvent}。
         * 提供的 {@link ReasoningContext} 用于累积片段（构建最终 {@link Msg}）及通知旧式 {@link Hook}。
         *
         * @param context 用于片段累积的推理上下文
         * @param messages 要发送给模型的消息
         * @param tools 可用工具 schema
         * @param options 生成选项
         * @return 单次模型调用的事件流
         */
        Flux<AgentEvent> reasoningStream(
                ReasoningContext context,
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {

            Function<ModelCallInput, Flux<AgentEvent>> modelCallCore =
                    mci -> modelCallStream(context, mci, true);

            StringBuilder transformedText = new StringBuilder();
            AtomicBoolean sawTransformedTextDelta = new AtomicBoolean(false);
            return MiddlewareChain.build(
                            middlewaresAt(MiddlewareBase.ExtensionPoint.ON_MODEL_CALL),
                            ReActAgent.this,
                            rc,
                            MiddlewareBase::onModelCall,
                            modelCallCore)
                    .apply(
                            new ModelCallInput(
                                    messages,
                                    tools,
                                    options,
                                    modelForCall(
                                            rc,
                                            ModelRequestPreparer.Purpose.REASONING,
                                            this::publishEvent)))
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent textDelta) {
                                    sawTransformedTextDelta.set(true);
                                    if (textDelta.getDelta() != null) {
                                        transformedText.append(textDelta.getDelta());
                                    }
                                }
                            })
                    .doOnTerminate(
                            () -> {
                                if (sawTransformedTextDelta.get()
                                        || !context.getAccumulatedText().isEmpty()) {
                                    context.replaceAccumulatedText(transformedText.toString());
                                }
                            });
        }

        private Flux<AgentEvent> modelCallStream(
                ReasoningContext context, ModelCallInput mci, boolean withToolEvents) {

            return Flux.defer(
                    () -> {
                        String callId = UUID.randomUUID().toString().replace("-", "");
                        return prepareRequest(mci, callId, ModelRequestPreparer.Purpose.REASONING)
                                .flatMapMany(
                                        prepared ->
                                                preparedModelCallStream(
                                                        context, prepared, withToolEvents, callId));
                    });
        }

        private Mono<ModelCallInput> prepareRequest(
                ModelCallInput input, String callId, ModelRequestPreparer.Purpose purpose) {
            return modelRequestPreparer == null
                    ? Mono.just(input)
                    : Mono.defer(
                                    () ->
                                            modelRequestPreparer.prepareObserved(
                                                    ReActAgent.this,
                                                    rc,
                                                    input,
                                                    callId,
                                                    purpose,
                                                    this::publishEvent))
                            .switchIfEmpty(
                                    Mono.error(
                                            new IllegalStateException(
                                                    "Model request preparer returned no request")));
        }

        private AgentEvent modelCallStart(String replyId) {
            if (rc == null) return new ModelCallStartEvent(replyId);
            String key = ModelRequestPreparer.MANIFEST_ATTRIBUTE_PREFIX + replyId;
            Object manifest = rc.get(key);
            rc.put(key, null);
            ModelCallStartEvent event = new ModelCallStartEvent(replyId);
            return manifest == null ? event : event.withMetadataEntry("contextManifest", manifest);
        }

        private static String soToolReminderText() {
            return "<system-reminder>\n"
                    + "You MUST call the `"
                    + STRUCTURED_OUTPUT_TOOL_NAME
                    + "` tool to generate your final structured response. Do NOT output free-form"
                    + " text as the final answer.\n"
                    + "</system-reminder>";
        }

        /**
         * 将结构化输出提醒追加到请求局部的模型输入副本。
         * 提醒绝不进入 {@code state.contextMutable()}，因此钩子、中间件、会话持久化及后续调用仅看到调用方真实消息。
         */
        private ModelCallInput appendTransientSoToolReminder(ModelCallInput input) {
            if (soTool == null) {
                return input;
            }

            List<Msg> messages = new ArrayList<>(input.messages());
            TextBlock reminder = TextBlock.builder().text(soToolReminderText()).build();
            int lastIndex = messages.size() - 1;
            if (lastIndex >= 0 && messages.get(lastIndex).getRole() == MsgRole.USER) {
                Msg last = messages.get(lastIndex);
                List<ContentBlock> content = new ArrayList<>(last.getContent());
                content.add(reminder);
                messages.set(lastIndex, last.withContent(List.copyOf(content)));
            } else {
                messages.add(UserMessage.builder().name("user").content(List.of(reminder)).build());
            }

            List<ToolSchema> tools = input.tools();
            boolean soToolPresent =
                    tools.stream()
                            .anyMatch(tool -> STRUCTURED_OUTPUT_TOOL_NAME.equals(tool.getName()));
            if (!soToolPresent) {
                tools = new ArrayList<>(tools);
                tools.add(
                        ToolSchema.builder()
                                .name(soTool.getName())
                                .description(soTool.getDescription())
                                .parameters(soTool.getParameters())
                                .strict(soTool.getStrict())
                                .outputSchema(soTool.getOutputSchema())
                                .build());
            }

            return new ModelCallInput(
                    List.copyOf(messages), List.copyOf(tools), input.options(), input.model());
        }

        private Flux<AgentEvent> preparedModelCallStream(
                ReasoningContext context,
                ModelCallInput modelCallInput,
                boolean withToolEvents,
                String replyId) {

            ModelCallBlockLifecycle blockLifecycle = new ModelCallBlockLifecycle(replyId);
            ModelCallInput mci = appendTransientSoToolReminder(modelCallInput);

            Flux<AgentEvent> modelEvents =
                    Flux.defer(
                                    () ->
                                            SessionModels.wrap(mci.model(), rc, "REASONING").stream(
                                                    mci.messages(), mci.tools(), mci.options()))
                            .concatMap(chunk -> checkInterrupted().thenReturn(chunk))
                            .concatMap(
                                    chunk ->
                                            Flux.deferContextual(
                                                    parentCtx -> {
                                                        List<Msg> chunkMsgs =
                                                                context.processChunk(chunk);
                                                        for (Msg msg : chunkMsgs) {
                                                            hookDispatcher
                                                                    .fireReasoningChunk(
                                                                            msg,
                                                                            context,
                                                                            mci.model()
                                                                                    .getModelName())
                                                                    .contextWrite(
                                                                            ctx ->
                                                                                    ctx.putAll(
                                                                                            parentCtx))
                                                                    .subscribe();
                                                        }

                                                        List<AgentEvent> events = new ArrayList<>();
                                                        for (ContentBlock block :
                                                                chunk.getContent()) {
                                                            emitBlockEvents(
                                                                    block,
                                                                    context,
                                                                    blockLifecycle,
                                                                    withToolEvents,
                                                                    events);
                                                        }
                                                        return Flux.fromIterable(events);
                                                    }));

            Flux<AgentEvent> endEvents =
                    Flux.defer(
                            () -> {
                                List<AgentEvent> events = new ArrayList<>();
                                blockLifecycle.flushAll(events);
                                events.add(new ModelCallEndEvent(replyId, context.getChatUsage()));
                                return Flux.fromIterable(events);
                            });

            return Flux.concat(Flux.just(modelCallStart(replyId)), modelEvents, endEvents);
        }

        private void emitBlockEvents(
                ContentBlock block,
                ReasoningContext context,
                ModelCallBlockLifecycle blockLifecycle,
                boolean withToolEvents,
                List<AgentEvent> events) {

            if (block instanceof TextBlock tb) {
                blockLifecycle.startText(events);
                if (tb.getText() != null && !tb.getText().isEmpty()) {
                    events.add(
                            new TextBlockDeltaEvent(
                                    blockLifecycle.replyId,
                                    blockLifecycle.currentTextBlockId(),
                                    tb.getText()));
                }
            } else if (block instanceof ThinkingBlock tb) {
                blockLifecycle.startThinking(events);
                if (tb.getThinking() != null && !tb.getThinking().isEmpty()) {
                    events.add(
                            new ThinkingBlockDeltaEvent(
                                    blockLifecycle.replyId,
                                    blockLifecycle.currentThinkingBlockId(),
                                    tb.getThinking()));
                }
            } else if (withToolEvents && block instanceof ToolUseBlock tub) {
                String toolId = resolveToolCallId(tub, context);
                String toolName = tub.getName();
                blockLifecycle.startToolCall(toolId, toolName, events);
                if (tub.getContent() != null && !tub.getContent().isEmpty()) {
                    events.add(
                            new ToolCallDeltaEvent(
                                    blockLifecycle.replyId,
                                    toolId != null ? toolId : "",
                                    toolName,
                                    tub.getContent()));
                }
            } else if (withToolEvents
                    && block instanceof ToolResultBlock trb
                    && trb.isServerTool()) {
                blockLifecycle.flushServerToolCall(trb.getId(), events);
                events.addAll(serverToolResultEvents(trb, blockLifecycle.replyId));
            }
        }

        /**
         * 为提供方执行的工具结果构建完整工具结果生命周期。
         *
         * <p>事件仅附加服务端工具标记；提供方特定原始结果保留在最终消息中，不重复放入事件流。
         */
        private List<AgentEvent> serverToolResultEvents(ToolResultBlock result, String replyId) {
            String toolId = result.getId();
            String toolName = result.getName();
            Map<String, Object> eventMetadata = Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true);

            List<AgentEvent> events = new ArrayList<>();
            events.add(
                    new ToolResultStartEvent(replyId, toolId, toolName)
                            .withMetadata(eventMetadata));

            for (ContentBlock block : result.getOutput()) {
                if (block instanceof TextBlock tb) {
                    events.add(
                            new ToolResultTextDeltaEvent(replyId, toolId, toolName, tb.getText())
                                    .withMetadata(eventMetadata));
                } else {
                    events.add(
                            new ToolResultDataDeltaEvent(replyId, toolId, toolName, block)
                                    .withMetadata(eventMetadata));
                }
            }

            events.add(
                    new ToolResultEndEvent(
                                    replyId, toolId, toolName, determineToolResultState(result))
                            .withMetadata(eventMetadata));

            return events;
        }

        /**
         * 跟踪单次模型调用订阅内的块生命周期。
         *
         * <p>模型流通过 {@code concatMap} 消费，但状态容器仍保持原有线程安全结构，
         * 因为模型提供方可能以不可预测的方式投递片段内容。每段连续文本或思考获得独立块 ID，
         * 使其开始、增量和结束事件能独立关联。
         */
        private final class ModelCallBlockLifecycle {
            private final String replyId;
            private final AtomicBoolean textStarted = new AtomicBoolean(false);
            private final AtomicLong textSegmentSequence = new AtomicLong(0);
            private final AtomicReference<String> currentTextBlockId = new AtomicReference<>();
            private final AtomicBoolean thinkingStarted = new AtomicBoolean(false);
            private final AtomicLong thinkingSegmentSequence = new AtomicLong(0);
            private final AtomicReference<String> currentThinkingBlockId = new AtomicReference<>();
            private final Map<String, String> startedToolCalls = new ConcurrentHashMap<>();

            private ModelCallBlockLifecycle(String replyId) {
                this.replyId = replyId;
            }

            private void startText(List<AgentEvent> events) {
                flushThinking(events);
                if (textStarted.compareAndSet(false, true)) {
                    long segment = textSegmentSequence.incrementAndGet();
                    String blockId = segment == 1 ? "text" : "text-" + segment;
                    currentTextBlockId.set(blockId);
                    events.add(new TextBlockStartEvent(replyId, blockId));
                }
            }

            private String currentTextBlockId() {
                return currentTextBlockId.get();
            }

            private void startThinking(List<AgentEvent> events) {
                if (thinkingStarted.compareAndSet(false, true)) {
                    long segment = thinkingSegmentSequence.incrementAndGet();
                    String blockId = segment == 1 ? "thinking" : "thinking-" + segment;
                    currentThinkingBlockId.set(blockId);
                    events.add(new ThinkingBlockStartEvent(replyId, blockId));
                }
            }

            private String currentThinkingBlockId() {
                return currentThinkingBlockId.get();
            }

            private void startToolCall(String toolId, String toolName, List<AgentEvent> events) {
                if (toolId == null || startedToolCalls.containsKey(toolId)) {
                    return;
                }
                flushText(events);
                flushThinking(events);
                flushAllToolCalls(events);
                boolean visibleTool = toolName != null && !toolName.startsWith("__");
                if (visibleTool && startedToolCalls.putIfAbsent(toolId, toolName) == null) {
                    events.add(new ToolCallStartEvent(replyId, toolId, toolName));
                }
            }

            private void flushServerToolCall(String toolId, List<AgentEvent> events) {
                String toolName = startedToolCalls.remove(toolId);
                if (toolName != null) {
                    events.add(new ToolCallEndEvent(replyId, toolId, toolName));
                }
            }

            private void flushText(List<AgentEvent> events) {
                if (textStarted.compareAndSet(true, false)) {
                    String blockId = currentTextBlockId.getAndSet(null);
                    events.add(new TextBlockEndEvent(replyId, blockId));
                }
            }

            private void flushThinking(List<AgentEvent> events) {
                if (thinkingStarted.compareAndSet(true, false)) {
                    String blockId = currentThinkingBlockId.getAndSet(null);
                    events.add(new ThinkingBlockEndEvent(replyId, blockId));
                }
            }

            private void flushAllToolCalls(List<AgentEvent> events) {
                for (Map.Entry<String, String> tc : startedToolCalls.entrySet()) {
                    events.add(new ToolCallEndEvent(replyId, tc.getKey(), tc.getValue()));
                }
                startedToolCalls.clear();
            }

            private void flushAll(List<AgentEvent> events) {
                flushText(events);
                flushThinking(events);
                flushAllToolCalls(events);
            }
        }

        private String resolveToolCallId(ToolUseBlock tub, ReasoningContext context) {
            if (tub.getId() != null && !tub.getId().isEmpty()) {
                return tub.getId();
            }
            ToolUseBlock accumulated = context.getAccumulatedToolCall(null);
            return accumulated != null ? accumulated.getId() : null;
        }

        /**
         * 执行行动阶段。
         *
         * <p>仅执行待处理工具（上下文中尚无结果的调用），为成功工具结果通知钩子，
         * 并决定继续迭代还是返回（HITL 停止、工具暂停或结构化输出）。
         *
         * <p>对于抛出 {@link io.agentscope.core.tool.ToolSuspendException} 的工具：
         * <ul>
         * <li>Toolkit 捕获异常并转为待处理 ToolResultBlock</li>
         * <li>成功结果存入上下文，待处理结果不存入</li>
         * <li>返回标记为 {@link GenerateReason#TOOL_SUSPENDED} 且包含暂停 ToolUseBlock 的 Msg</li>
         * </ul>
         *
         * @param iter 当前迭代编号
         * @return 包含最终结果消息的 Mono
         */
        private Mono<Msg> acting(int iter) {
            // 【学习提示】待执行集合由上下文中“有 tool_use、无匹配 tool_result”推导，
            // 不简单地执行最近消息的全部工具。恢复调用时，这能避免重跑已有结果的工具。
            List<ToolUseBlock> pendingToolCalls =
                    MessageUtils.extractPendingToolCalls(state.contextMutable(), getName());
            String replyId = UUID.randomUUID().toString().replace("-", "");

            // 流式参数始终未构成合法 JSON 对象的工具调用（通常被
            // max-tokens 停止截断）不可执行。使用原调用 ID 合成 ERROR 结果，
            // 保持 tool_use ↔ tool_result 配对完整，并且绝不
            // 交给权限门控、中间件链或执行器。循环继续
            // 进入下一轮推理；本轮不进行自动重试。
            List<ToolUseBlock> malformedToolCalls =
                    pendingToolCalls.stream().filter(ToolUseBlock::hasMalformedArguments).toList();
            if (!malformedToolCalls.isEmpty()) {
                writeMalformedArgumentResults(
                        malformedToolCalls,
                        replyId,
                        MessageUtils.lastAssistantReachedOutputLimit(
                                state.contextMutable(), getName()));
            }
            List<ToolUseBlock> executableToolCalls =
                    pendingToolCalls.stream().filter(tc -> !tc.hasMalformedArguments()).toList();

            List<ToolUseBlock> roundToolCalls = extractRecentToolCalls();

            if (executableToolCalls.isEmpty()) {
                if (!roundToolCalls.isEmpty()
                        && MessageUtils.allToolCallsDenied(
                                state.contextMutable(), roundToolCalls)) {
                    return emitAllToolsDeniedThroughMiddleware(roundToolCalls, iter);
                }
                return executeIteration(iter + 1);
            }

            AtomicReference<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> resultHolder =
                    new AtomicReference<>(List.of());

            AtomicReference<RequestStopEvent> actingStopRequested = new AtomicReference<>();
            return hookDispatcher
                    .firePreActing(executableToolCalls, toolkit)
                    .flatMap(
                            toolCalls -> {
                                Function<ActingInput, Flux<AgentEvent>> actingCore =
                                        ai -> actingStream(ai.toolCalls(), replyId, resultHolder);
                                Flux<AgentEvent> stream =
                                        MiddlewareChain.build(
                                                        middlewaresAt(
                                                                MiddlewareBase.ExtensionPoint
                                                                        .ON_ACTING),
                                                        ReActAgent.this,
                                                        rc,
                                                        MiddlewareBase::onActing,
                                                        actingCore)
                                                .apply(new ActingInput(toolCalls));
                                return stream.doOnNext(
                                                ev -> {
                                                    if (ev instanceof RequestStopEvent rs) {
                                                        actingStopRequested.compareAndSet(null, rs);
                                                    }
                                                })
                                        .then(Mono.defer(() -> Mono.just(resultHolder.get())));
                            })
                    .flatMap(
                            results -> {
                                // 中间件在行动期间请求停止，立即返回
                                // 请求的 GenerateReason，同时保留已经
                                // 收集到的结果。
                                RequestStopEvent rs = actingStopRequested.get();
                                if (rs != null) {
                                    if (rs.getGenerateReason()
                                            == GenerateReason.PERMISSION_ASKING) {
                                        Msg lastAssistant =
                                                MessageUtils.lastAssistantMessage(
                                                        state.contextMutable());
                                        if (lastAssistant != null) {
                                            return Mono.just(
                                                    lastAssistant.withGenerateReason(
                                                            GenerateReason.PERMISSION_ASKING));
                                        }
                                    }
                                    Msg stopMsg = buildStopMsg(results, rs.getGenerateReason());
                                    return Mono.just(stopMsg);
                                }
                                List<Map.Entry<ToolUseBlock, ToolResultBlock>> successPairs =
                                        results.stream()
                                                .filter(e -> !e.getValue().isSuspended())
                                                .toList();
                                List<Map.Entry<ToolUseBlock, ToolResultBlock>> pendingPairs =
                                        results.stream()
                                                .filter(e -> e.getValue().isSuspended())
                                                .toList();

                                if (successPairs.isEmpty()) {
                                    if (!pendingPairs.isEmpty()) {
                                        return Mono.just(buildSuspendedMsg(pendingPairs));
                                    }
                                    return executeIteration(iter + 1);
                                }

                                boolean returnDirect =
                                        executableToolCalls.size() == roundToolCalls.size()
                                                && pendingPairs.isEmpty()
                                                && !successPairs.isEmpty()
                                                && successPairs.stream()
                                                        .allMatch(this::isReturnDirectToolCall);

                                // 为每个已执行工具触发钩子并持久化结果，然后
                                // 让最早的 stopAgent() 生效；若使用 takeUntil，会取消
                                // 尾部钩子及 tool_result 写入，使已经运行的工具留下
                                // 悬空 tool_use（参照中断路径的
                                // synthesizeErrorResultsForPendingToolCalls）。
                                // 停止之后仍原样持久化结果；本轮通过
                                // ACTING_STOP_REQUESTED 结束，而不是 returnDirect 短路。
                                AtomicBoolean stopSeen = new AtomicBoolean();
                                return Flux.fromIterable(successPairs)
                                        .concatMap(
                                                e ->
                                                        notifyPostActingHook(
                                                                e, returnDirect && !stopSeen.get()))
                                        .doOnNext(
                                                e -> {
                                                    if (e.isStopRequested()) {
                                                        stopSeen.set(true);
                                                    }
                                                })
                                        .collectList()
                                        .map(this::earliestStopOrLast)
                                        .flatMap(
                                                event -> {
                                                    if (event.isStopRequested()) {
                                                        return Mono.just(
                                                                event.getToolResultMsg()
                                                                        .withGenerateReason(
                                                                                GenerateReason
                                                                                        .ACTING_STOP_REQUESTED));
                                                    }

                                                    if (returnDirect) {
                                                        return Mono.just(
                                                                finalizeReturnDirect(
                                                                        successPairs, replyId));
                                                    }

                                                    if (!pendingPairs.isEmpty()) {
                                                        return Mono.just(
                                                                buildSuspendedMsg(pendingPairs));
                                                    }

                                                    return executeIteration(iter + 1);
                                                });
                            });
        }

        /**
         * 流式发送行动阶段工具执行产生的细粒度 {@link AgentEvent}。
         *
         * <p>每个工具调用发出：{@link ToolResultStartEvent} → 增量事件 → {@link ToolResultEndEvent}。
         * 执行结果填入 {@code resultHolder}，便于调用方随后处理。
         *
         * @param toolCalls 要执行的工具调用
         * @param replyId 用于事件关联的回复标识符
         * @param resultHolder 完成时填入工具执行结果
         * @return 工具执行事件流
         */
        Flux<AgentEvent> actingStream(
                List<ToolUseBlock> toolCalls,
                String replyId,
                AtomicReference<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> resultHolder) {

            // 【学习提示】权限门控先于实际副作用。ASKING 保留为待确认调用并暂停，
            // DENIED 写入拒绝结果形成完整配对，ALLOWED 才进入后续执行路径。
            // 恢复时依靠消息里的调用 ID 关联确认，不能把“继续”理解为无条件批准所有工具。
            return evaluatePermissions(toolCalls)
                    .flatMapMany(
                            gate -> {
                                List<ToolUseBlock> pending = gate.pendingAsk();
                                Set<String> autoDenied = gate.autoDeniedIds();

                                // 为每个经过门控的工具在上下文标记 ToolUseBlock.state。ALLOWED
                                // 调用立即执行；ASKING 调用使智能体暂停并
                                // 返回；DENIED 调用在下面写入 DENIED ToolResultBlock。
                                Map<String, ToolCallState> stateUpdates = new HashMap<>();
                                for (ToolUseBlock tc : toolCalls) {
                                    if (autoDenied.contains(tc.getId())) {
                                        // DENIED 工具无需更改调用状态，因为会写入
                                        // DENIED ToolResultBlock，之后不再出现在待处理列表。
                                        continue;
                                    }
                                    stateUpdates.put(
                                            tc.getId(),
                                            pending.stream()
                                                            .anyMatch(
                                                                    p ->
                                                                            p.getId()
                                                                                    .equals(
                                                                                            tc
                                                                                                    .getId()))
                                                    ? ToolCallState.ASKING
                                                    : ToolCallState.ALLOWED);
                                }
                                updateToolCallStates(stateUpdates);

                                if (pending.isEmpty()) {
                                    return runToolBatch(
                                            toolCalls, autoDenied, replyId, resultHolder);
                                }

                                // 权限 HITL：暴露待处理工具调用，持久化全部
                                // 自动拒绝结果，使第二次调用能辨别哪些
                                // 仍需确认，然后通过 RequestStopEvent 发出停止信号。
                                // 智能体的 acting() 将看到 RequestStopEvent，设置
                                // GenerateReason 为 PERMISSION_ASKING，然后返回。
                                if (!autoDenied.isEmpty()) {
                                    // 原地写入 DENIED 结果，避免恢复时
                                    // 再次评估。
                                    writeAutoDeniedResults(toolCalls, autoDenied);
                                }
                                // 流完成之后，调用方可能检查 resultHolder；
                                // 此时
                                // 由于未发生成功执行，将它初始化为空。
                                resultHolder.set(List.of());
                                persistPendingRequestReplyId(
                                        Msg.METADATA_CONFIRM_REQUEST_REPLY_ID, replyId);
                                Flux<AgentEvent> autoDeniedEvents =
                                        Flux.fromIterable(toolCalls)
                                                .filter(tc -> autoDenied.contains(tc.getId()))
                                                .concatMapIterable(
                                                        tc ->
                                                                deniedToolResultEvents(
                                                                        tc,
                                                                        replyId,
                                                                        PERMISSION_DENIED_BY_RULES));
                                return autoDeniedEvents.concatWith(
                                        Flux.just(
                                                new RequireUserConfirmEvent(replyId, pending),
                                                new RequestStopEvent(
                                                        "permission asking",
                                                        GenerateReason.PERMISSION_ASKING)));
                            })
                    .doOnNext(this::publishEvent);
        }

        /**
         * 为被 deny 规则拒绝的工具合成 DENIED ToolResultBlock，并追加到上下文，
         * 使会话反映拒绝事实，恢复时不再将其视为待处理。
         */
        private void writeAutoDeniedResults(List<ToolUseBlock> toolCalls, Set<String> deniedIds) {
            for (ToolUseBlock tc : toolCalls) {
                if (!deniedIds.contains(tc.getId())) {
                    continue;
                }
                ToolResultBlock denied =
                        ToolResultBlock.text(PERMISSION_DENIED_BY_RULES)
                                .withIdAndName(tc.getId(), tc.getName())
                                .withState(ToolResultState.DENIED);
                Msg deniedMsg = ToolResultMessageBuilder.buildToolResultMsg(denied, tc, getName());
                state.contextMutable().add(deniedMsg);
            }
        }

        /**
         * 为流式参数始终未构成合法 JSON 对象的工具调用合成 ERROR ToolResultBlock
         * （由 {@link ToolUseBlock#METADATA_MALFORMED_ARGUMENTS} 标记）。
         * 绝不调用处理器；结果追加到上下文并作为生命周期事件发出，
         * 保持 tool_use ↔ tool_result 配对完整，让模型能够重新发起调用。
         */
        private void writeMalformedArgumentResults(
                List<ToolUseBlock> toolCalls, String replyId, boolean outputLimitReached) {
            String message =
                    outputLimitReached ? OUTPUT_LIMIT_TOOL_ARGUMENTS : INVALID_TOOL_ARGUMENTS;
            for (ToolUseBlock tc : toolCalls) {
                ToolResultBlock errorResult = ToolResultBlock.error(tc.getId(), message);
                Msg toolResultMsg =
                        ToolResultMessageBuilder.buildToolResultMsg(errorResult, tc, getName());
                state.contextMutable().add(toolResultMsg);
                publishEvent(new ToolResultStartEvent(replyId, tc.getId(), tc.getName()));
                publishEvent(
                        new ToolResultTextDeltaEvent(replyId, tc.getId(), tc.getName(), message));
                publishEvent(
                        new ToolResultEndEvent(
                                replyId, tc.getId(), tc.getName(), ToolResultState.ERROR));
            }
        }

        /**
         * 执行给定工具调用；ID 位于 {@code deniedIds} 的调用合成 DENIED 结果并跳过工具集调用，
         * 其余通过 {@link #executeToolCalls(List)} 执行。
         * 合并结果写入 {@code resultHolder}，并以细粒度 {@link AgentEvent} 流发出。
         */
        private Flux<AgentEvent> runToolBatch(
                List<ToolUseBlock> toolCalls,
                Set<String> deniedIds,
                String replyId,
                AtomicReference<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> resultHolder) {

            List<Map.Entry<ToolUseBlock, ToolResultBlock>> deniedEntries = new ArrayList<>();
            List<ToolUseBlock> approved = new ArrayList<>();
            for (ToolUseBlock tc : toolCalls) {
                if (deniedIds.contains(tc.getId())) {
                    ToolResultBlock denied =
                            ToolResultBlock.text(PERMISSION_DENIED_BY_RULES)
                                    .withIdAndName(tc.getId(), tc.getName())
                                    .withState(ToolResultState.DENIED);
                    deniedEntries.add(Map.entry(tc, denied));
                } else {
                    approved.add(tc);
                }
            }

            Flux<AgentEvent> deniedEvents =
                    Flux.fromIterable(deniedEntries)
                            .concatMap(
                                    entry -> {
                                        ToolUseBlock use = entry.getKey();
                                        ToolCallParam deniedParam =
                                                ToolCallParam.builder()
                                                        .toolUseBlock(use)
                                                        .agent(ReActAgent.this)
                                                        .runtimeContext(
                                                                buildMergedRuntimeContext(rc))
                                                        .build();
                                        return ActionObservations.observe(
                                                        deniedParam,
                                                        () -> Mono.just(entry.getValue()))
                                                .thenMany(
                                                        Flux.fromIterable(
                                                                deniedToolResultEvents(
                                                                        use,
                                                                        replyId,
                                                                        PERMISSION_DENIED_BY_RULES)));
                                    });

            if (approved.isEmpty()) {
                resultHolder.set(deniedEntries);
                return deniedEvents;
            }

            // 捕获父级 Reactor Context（由 AgentBase.createEventStream 设置，其中放入
            // 了
            // SubagentEventBus），以便转发到内部 executeToolCalls 的
            // 订阅中。
            // 否则下面直接 .subscribe() 会脱离上游链，使 AgentSpawnTool 等工具
            // 看到空 ContextView，破坏子事件转发。
            Flux<AgentEvent> approvedEvents =
                    Flux.<AgentEvent>deferContextual(
                            parentCtx ->
                                    Flux.<AgentEvent>create(
                                            sink -> {
                                                // 开始／进度／结果事件现在由
                                                // 执行器逐个触及工具时产生，因此
                                                // ToolResultStartEvent 表示实际执行
                                                // 开始，而非批次获准进入。
                                                ToolResultCallback callback =
                                                        new ToolResultCallback(
                                                                replyId, sink, parentCtx);
                                                Disposable toolCallsDisposable =
                                                        executeToolCalls(approved, callback)
                                                                .contextWrite(
                                                                        ctx -> {
                                                                            Context merged =
                                                                                    ctx.putAll(
                                                                                            parentCtx);
                                                                            if (!merged.hasKey(
                                                                                            SubagentEventBus
                                                                                                    .CONTEXT_KEY)
                                                                                    && !merged
                                                                                            .hasKey(
                                                                                                    AgentEventEmitter
                                                                                                            .CONTEXT_KEY)) {
                                                                                if (eventSink
                                                                                        != null) {
                                                                                    merged =
                                                                                            merged
                                                                                                    .put(
                                                                                                            AgentEventEmitter
                                                                                                                    .CONTEXT_KEY,
                                                                                                            (AgentEventEmitter)
                                                                                                                    eventSink
                                                                                                                            ::next);
                                                                                } else if (externalEventEmitter
                                                                                        != null) {
                                                                                    merged =
                                                                                            merged
                                                                                                    .put(
                                                                                                            AgentEventEmitter
                                                                                                                    .CONTEXT_KEY,
                                                                                                            externalEventEmitter);
                                                                                }
                                                                            }
                                                                            return merged;
                                                                        })
                                                                .subscribe(
                                                                        results -> {
                                                                            List<
                                                                                            Map
                                                                                                            .Entry<
                                                                                                    ToolUseBlock,
                                                                                                    ToolResultBlock>>
                                                                                    merged =
                                                                                            new ArrayList<>(
                                                                                                    deniedEntries);
                                                                            merged.addAll(results);
                                                                            resultHolder.set(
                                                                                    merged);
                                                                            List<ToolUseBlock>
                                                                                    suspendedCalls =
                                                                                            getSuspendedToolCalls(
                                                                                                    results);
                                                                            if (!suspendedCalls
                                                                                    .isEmpty()) {
                                                                                persistPendingRequestReplyId(
                                                                                        Msg
                                                                                                .METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID,
                                                                                        replyId);
                                                                                sink.next(
                                                                                        new RequireExternalExecutionEvent(
                                                                                                replyId,
                                                                                                suspendedCalls));
                                                                            }
                                                                            sink.complete();
                                                                        },
                                                                        sink::error);
                                                sink.onCancel(toolCallsDisposable);
                                            }));

            return deniedEvents.concatWith(approvedEvents);
        }

        /**
         * 每个 {@link ToolBase} 调用通过 {@link PermissionEngine} 后的结果。
         *
         * @param pendingAsk 执行前需要用户确认的工具调用
         * @param autoDeniedIds 决策为 {@code DENY} 的调用 ID；智能体循环为其合成拒绝结果，不实际调用工具
         */
        private record PermissionGate(List<ToolUseBlock> pendingAsk, Set<String> autoDeniedIds) {}

        /**
         * 让每个工具调用通过权限门控。
         *
         * <p>当智能体的 {@link io.agentscope.core.permission.PermissionContextState} 为简单配置
         * （默认模式、无规则、无工作目录，即用户未启用权限系统）时，回退到 2.0 之前的轻量路径：
         * 工具自身 {@link ToolBase#checkPermissions} 返回 ASK 时要求确认，其余通过。
         *
         * <p>否则启用完整 {@link PermissionEngine} 管道，在执行前遵循 deny/ask/allow 规则
         * 及 EXPLORE/ACCEPT_EDITS/BYPASS/DONT_ASK 模式。
         * 不继承 {@link ToolBase} 的旧式 {@link AgentTool} 始终获准通过。
         */
        private Mono<PermissionGate> evaluatePermissions(List<ToolUseBlock> toolCalls) {
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Mono.just(new PermissionGate(List.of(), Set.of()));
            }
            boolean useEngine = !state.getPermissionContext().isTrivial();
            return Flux.fromIterable(toolCalls)
                    .concatMap(use -> evaluateOne(use, useEngine))
                    .collectList()
                    .map(
                            verdicts -> {
                                List<ToolUseBlock> pending = new ArrayList<>();
                                Set<String> denied = new HashSet<>();
                                for (PermissionVerdict v : verdicts) {
                                    switch (v.behavior()) {
                                        case DENY -> denied.add(v.use().getId());
                                        case ASK -> pending.add(v.use());
                                        case ALLOW, PASSTHROUGH -> {
                                            // 自动批准，继续执行
                                        }
                                    }
                                }
                                return new PermissionGate(pending, denied);
                            });
        }

        private Mono<PermissionVerdict> evaluateOne(ToolUseBlock use, boolean useEngine) {
            // 【学习提示】这是兼容性边界：旧 AgentTool（非 ToolBase）直接通过；
            // ToolBase 才参与此处权限检查。不能仅因配置了权限引擎就假定所有旧工具自动受控。
            // 经用户确认提升为 ALLOWED 的工具完全跳过引擎。
            if (use.getState() == ToolCallState.ALLOWED) {
                return Mono.just(new PermissionVerdict(use, PermissionBehavior.ALLOW));
            }
            AgentTool tool = activeToolkit.getTool(use.getName(), toolRequestConfig);
            if (!(tool instanceof ToolBase tb)) {
                return Mono.just(new PermissionVerdict(use, PermissionBehavior.ALLOW));
            }
            Map<String, Object> input = use.getInput() == null ? Map.of() : use.getInput();
            if (useEngine) {
                return permissionEngine
                        .checkPermission(tb, input)
                        .map(
                                decision ->
                                        new PermissionVerdict(
                                                use,
                                                decision == null
                                                        ? PermissionBehavior.ASK
                                                        : decision.getBehavior()));
            }
            return tb.checkPermissions(input, state.getPermissionContext())
                    .map(
                            decision -> {
                                if (decision == null) {
                                    return new PermissionVerdict(use, PermissionBehavior.ALLOW);
                                }
                                // 旧式轻量路径中，仅工具显式返回 ASK 才要求
                                // 确认；PASSTHROUGH 和 ALLOW 都执行，DENY
                                // 仍会被遵守。
                                return switch (decision.getBehavior()) {
                                    case ASK -> new PermissionVerdict(use, PermissionBehavior.ASK);
                                    case DENY ->
                                            new PermissionVerdict(use, PermissionBehavior.DENY);
                                    default -> new PermissionVerdict(use, PermissionBehavior.ALLOW);
                                };
                            });
        }

        private record PermissionVerdict(ToolUseBlock use, PermissionBehavior behavior) {}

        private List<ToolUseBlock> getSuspendedToolCalls(
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> results) {
            return results.stream()
                    .filter(entry -> entry.getValue().isSuspended())
                    .map(Map.Entry::getKey)
                    .toList();
        }

        /**
         * 为本地派发的工具调用发布开始、进度及结果事件。
         *
         * <p>按调用 ID 跟踪已确定结果：批次错误回退会重新遍历所有调用，
         * 但已确定结果的调用必须保留真实结果，不得重复发送事件。
         * 实例在 {@code Flux.create} 内按批次创建，因此该状态不超出单批次生命周期，
         * 共享 {@link Toolkit} 保持无状态。
         */
        private final class ToolResultCallback implements ToolExecutionCallback {
            private final String replyId;
            private final FluxSink<AgentEvent> sink;
            private final reactor.util.context.ContextView parentContext;

            /**
             * 批次结算前已确定的结果，以调用 ID 为键。
             */
            private final ConcurrentHashMap<String, ToolResultBlock> resolvedResults =
                    new ConcurrentHashMap<>();

            /**
             * 已发布开始事件的调用 ID。
             */
            private final Set<String> startedCalls = ConcurrentHashMap.newKeySet();

            private ToolResultCallback(
                    String replyId,
                    FluxSink<AgentEvent> sink,
                    reactor.util.context.ContextView parentContext) {
                this.replyId = replyId;
                this.sink = sink;
                this.parentContext = parentContext;
            }

            private void emit(AgentEvent event) {
                sink.next(event);
            }

            @Override
            public void onStart(ToolUseBlock call) {
                // 幂等：执行器每次调用触发一次，批次错误
                // 回退会对尚未进入执行器的调用防御性地触发它。
                if (!startedCalls.add(call.getId())) return;
                emit(new ToolResultStartEvent(replyId, call.getId(), call.getName()));
            }

            @Override
            public void onProgress(ToolUseBlock call, ToolResultBlock progress) {
                if (progress.getOutput() != null) {
                    for (ContentBlock block : progress.getOutput()) {
                        emit(
                                new ToolProgressEvent(replyId, call.getId(), call.getName(), block)
                                        .withMetadata(progress.getMetadata()));
                    }
                }
                hookDispatcher
                        .fireActingChunk(call, progress, toolkit)
                        .contextWrite(ctx -> ctx.putAll(parentContext))
                        .subscribe();
            }

            @Override
            public void onResult(ToolUseBlock call, ToolResultBlock result) {
                // 在暂停检查前记录，使较早的暂停状态在批次
                // 错误回退后仍保留；仅首次确定结果时发布事件。
                if (resolvedResults.putIfAbsent(call.getId(), result) != null) return;
                if (result.isSuspended()) return;
                Map<String, Object> metadata = result.getMetadata();
                if (result.getOutput() != null) {
                    for (ContentBlock block : result.getOutput()) {
                        AgentEvent event =
                                block instanceof TextBlock text
                                        ? new ToolResultTextDeltaEvent(
                                                replyId,
                                                call.getId(),
                                                call.getName(),
                                                text.getText())
                                        : new ToolResultDataDeltaEvent(
                                                replyId, call.getId(), call.getName(), block);
                        emit(event.withMetadata(metadata));
                    }
                }
                emit(
                        new ToolResultEndEvent(
                                        replyId,
                                        call.getId(),
                                        call.getName(),
                                        determineToolResultState(result))
                                .withMetadata(metadata));
            }

            /**
             * {@code call} 已确定的结果；尚未完成结算时为 {@code null}。
             */
            private ToolResultBlock resolvedResult(ToolUseBlock call) {
                return resolvedResults.get(call.getId());
            }
        }

        private ToolResultState determineToolResultState(ToolResultBlock result) {
            if (result.isSuspended()) {
                return ToolResultState.SUSPENDED;
            }
            if (result.getState() != null && result.getState() != ToolResultState.RUNNING) {
                return result.getState();
            }
            if (result.getOutput() != null
                    && result.getOutput().stream()
                            .anyMatch(
                                    b ->
                                            b instanceof TextBlock tb
                                                    && tb.getText() != null
                                                    && tb.getText().startsWith("[ERROR]"))) {
                return ToolResultState.ERROR;
            }
            return ToolResultState.SUCCESS;
        }

        /**
         * 构建包含暂停工具调用的消息，供用户执行。
         *
         * <p>每个暂停调用的 ToolUseBlock 与标记 ToolResultBlock 配对
         * （空输出、{@link ToolResultState#SUSPENDED}）。
         *
         * @param pendingPairs (ToolUseBlock, 待处理 ToolResultBlock) 配对列表
         * @return GenerateReason.TOOL_SUSPENDED 的 Msg
         */
        private Msg buildSuspendedMsg(List<Map.Entry<ToolUseBlock, ToolResultBlock>> pendingPairs) {
            List<ContentBlock> content = new ArrayList<>();
            for (Map.Entry<ToolUseBlock, ToolResultBlock> pair : pendingPairs) {
                content.add(pair.getKey());
                content.add(pair.getValue());
            }
            return AssistantMessage.builder()
                    .name(getName())
                    .content(content)
                    .generateReason(GenerateReason.TOOL_SUSPENDED)
                    .build();
        }

        /**
         * 为行动阶段中间件请求的停止构建确认 Msg，保留已收集的工具结果，使调用方看到部分进度。
         */
        private Msg buildStopMsg(
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> results, GenerateReason reason) {
            List<ContentBlock> content = new ArrayList<>();
            if (results != null) {
                for (Map.Entry<ToolUseBlock, ToolResultBlock> pair : results) {
                    content.add(pair.getKey());
                    content.add(pair.getValue());
                }
            }
            return AssistantMessage.builder()
                    .name(getName())
                    .content(content)
                    .generateReason(reason)
                    .build();
        }

        /**
         * 执行工具调用并返回配对结果。
         *
         * <p>工具执行失败（超时、错误等）时，为所有待处理调用生成错误工具结果，而非传播错误，
         * 使智能体能继续处理，模型获得适当错误反馈。已经确定结果的调用保留真实结果，不再重复通知；
         * 其余调用生成的错误结果传给回调，尚未开始的调用先获得开始通知。
         *
         * @param toolCalls 工具调用列表（可能已被 PreActingEvent 钩子修改）
         * @return 包含 (ToolUseBlock, ToolResultBlock) 配对列表的 Mono
         */
        private Mono<List<Map.Entry<ToolUseBlock, ToolResultBlock>>> executeToolCalls(
                List<ToolUseBlock> toolCalls, ToolResultCallback callback) {
            return dispatchToolCalls(toolCalls, callback)
                    .map(
                            results ->
                                    IntStream.range(0, toolCalls.size())
                                            .mapToObj(
                                                    i ->
                                                            Map.entry(
                                                                    toolCalls.get(i),
                                                                    results.get(i)))
                                            .toList())
                    .onErrorResume(
                            Exception.class,
                            error -> {
                                // 保留中断信号，供智能体停止策略处理
                                if (ActionObservationException.causedBy(error))
                                    return Mono.error(error);
                                if (error instanceof InterruptedException) {
                                    return Mono.error(error);
                                }
                                // 为所有待处理工具调用生成错误工具结果。
                                // 仅捕获 Exception 子类；严重 JVM 错误
                                // （例如 OutOfMemoryError）继续传播。
                                String errorMsg = ExceptionUtils.getErrorMessage(error);
                                log.error(
                                        "Tool execution failed, generating error results for {}"
                                                + " tool calls",
                                        toolCalls.size(),
                                        error);
                                List<Map.Entry<ToolUseBlock, ToolResultBlock>> errorResults =
                                        toolCalls.stream()
                                                .map(
                                                        toolCall -> {
                                                            // 已确定结果的调用保留
                                                            // 真实结果：事件层
                                                            // 已经发布过它，再告诉
                                                            // 模型已完成工具失败
                                                            // 会诱发无意义重试。
                                                            ToolResultBlock resolved =
                                                                    callback.resolvedResult(
                                                                            toolCall);
                                                            if (resolved != null) {
                                                                return Map.entry(
                                                                        toolCall, resolved);
                                                            }
                                                            // 执行器已经启动的调用
                                                            // 不做操作；排队调用
                                                            // （例如顺序模式中位于失败
                                                            // 之后的调用）在此收到 Start，
                                                            // 避免出现没有对应开始的 End。
                                                            callback.onStart(toolCall);
                                                            ToolResultBlock errorResult =
                                                                    ToolResultBlock.error(
                                                                            toolCall.getId(),
                                                                            "Tool execution failed:"
                                                                                    + " "
                                                                                    + errorMsg);
                                                            callback.onResult(
                                                                    toolCall, errorResult);
                                                            return Map.entry(toolCall, errorResult);
                                                        })
                                                .toList();
                                return Mono.just(errorResults);
                            });
        }

        /**
         * 按原顺序解析 {@code toolCalls} 的工具结果。结构化输出调用中，
         * {@code generate_response} 调用针对单次调用 {@link #soTool} 执行（绝不注册到共享工具集）；
         * 其他工具经 {@link Toolkit#callToolsWithCallback} 执行。
         */
        private Mono<List<ToolResultBlock>> dispatchToolCalls(
                List<ToolUseBlock> toolCalls, ToolResultCallback callback) {
            boolean hasStructured =
                    soTool != null
                            && toolCalls.stream()
                                    .anyMatch(t -> STRUCTURED_OUTPUT_TOOL_NAME.equals(t.getName()));
            if (!hasStructured) {
                return activeToolkit.callToolsWithCallback(
                        toolCalls,
                        toolExecutionConfig,
                        ReActAgent.this,
                        buildMergedRuntimeContext(rc),
                        toolRequestConfig,
                        callback);
            }

            List<ToolUseBlock> regular =
                    toolCalls.stream()
                            .filter(t -> !STRUCTURED_OUTPUT_TOOL_NAME.equals(t.getName()))
                            .toList();
            Mono<Map<String, ToolResultBlock>> regularResults =
                    regular.isEmpty()
                            ? Mono.just(Map.of())
                            : activeToolkit
                                    .callToolsWithCallback(
                                            regular,
                                            toolExecutionConfig,
                                            ReActAgent.this,
                                            buildMergedRuntimeContext(rc),
                                            toolRequestConfig,
                                            callback)
                                    .map(
                                            list -> {
                                                Map<String, ToolResultBlock> byId = new HashMap<>();
                                                for (int i = 0; i < regular.size(); i++) {
                                                    byId.put(regular.get(i).getId(), list.get(i));
                                                }
                                                return byId;
                                            });
            return regularResults.flatMap(
                    byId ->
                            Flux.fromIterable(toolCalls)
                                    .concatMap(
                                            use -> {
                                                if (STRUCTURED_OUTPUT_TOOL_NAME.equals(
                                                        use.getName())) {
                                                    return executeStructuredTool(use)
                                                            .doOnSubscribe(
                                                                    s -> callback.onStart(use))
                                                            .doOnNext(
                                                                    result ->
                                                                            callback.onResult(
                                                                                    use, result));
                                                }
                                                ToolResultBlock result = byId.get(use.getId());
                                                if (result == null) {
                                                    ToolResultBlock missing =
                                                            ToolResultBlock.error(
                                                                    use.getId(),
                                                                    "Internal error: missing tool"
                                                                            + " result for '"
                                                                            + use.getName()
                                                                            + "'");
                                                    callback.onResult(use, missing);
                                                    return Mono.just(missing);
                                                }
                                                return Mono.just(result);
                                            })
                                    .collectList());
        }

        /**
         * 对单个工具调用执行本次调用的 {@code generate_response} 工具，
         * 采用与执行器对注册工具相同的 schema 校验。
         */
        private Mono<ToolResultBlock> executeStructuredTool(ToolUseBlock use) {
            ToolCallParam param =
                    ToolCallParam.builder()
                            .toolUseBlock(use)
                            .input(use.getInput() == null ? Map.of() : use.getInput())
                            .agent(ReActAgent.this)
                            .runtimeContext(buildMergedRuntimeContext(rc))
                            .build();
            return ActionObservations.observe(
                    param, () -> executeStructuredToolUnobserved(use, param));
        }

        private Mono<ToolResultBlock> executeStructuredToolUnobserved(
                ToolUseBlock use, ToolCallParam param) {
            String validationError =
                    ToolValidator.validateInput(use.getContent(), soTool.getParameters());
            if (validationError != null) {
                return Mono.just(
                        ToolResultBlock.error(
                                use.getId(),
                                "Parameter validation failed for tool '"
                                        + STRUCTURED_OUTPUT_TOOL_NAME
                                        + "': "
                                        + validationError));
            }
            return soTool.callAsync(param).map(rb -> rb.withIdAndName(use.getId(), use.getName()));
        }

        /**
         * 为单个工具结果触发 PostActingEvent，构建消息并加入上下文。
         *
         * <p>{@code returnDirect} 为 {@code true}（整个批次直接返回）且钩子未请求停止时，
         * 写入上下文的工具结果替换为占位句；完整结果保留在钩子事件中供审计，稍后提升为结束助手消息。
         */
        private Mono<PostActingEvent> notifyPostActingHook(
                Map.Entry<ToolUseBlock, ToolResultBlock> entry, boolean returnDirect) {
            ToolUseBlock toolUse = entry.getKey();
            ToolResultBlock result = entry.getValue();

            // 修复：加入 contextMutable() 前先确定最终状态并更新
            // ToolResultBlock，使通过
            // agent.getAgentState(ctx).contextMutable() 查询历史时反映正确
            // 终态，而非始终显示 RUNNING。
            ToolResultState finalState = determineToolResultState(result);
            ToolResultBlock updatedResult = result.withState(finalState);

            Msg toolMsg =
                    ToolResultMessageBuilder.buildToolResultMsg(updatedResult, toolUse, getName());

            return hookDispatcher
                    .firePostActing(toolUse, updatedResult, toolkit, toolMsg)
                    .doOnNext(
                            e -> {
                                if (soTool != null
                                        && STRUCTURED_OUTPUT_TOOL_NAME.equals(toolUse.getName())
                                        && result.getMetadata() != null
                                        && Boolean.TRUE.equals(
                                                result.getMetadata().get("success"))) {
                                    soCompleted = true;
                                    soResultMsg = e.getToolResultMsg();
                                    e.stopAgent();
                                }
                                Msg resultMsg = e.getToolResultMsg();
                                if (returnDirect && !e.isStopRequested()) {
                                    if (e.getToolResult() != updatedResult
                                            || e.getToolResultMsg() != toolMsg) {
                                        log.warn(
                                                "returnDirect: discarding PostActing hook rewrite"
                                                        + " for tool '{}' - apply transformations"
                                                        + " at the tool/converter level"
                                                        + " instead",
                                                toolUse.getName());
                                    }
                                    resultMsg =
                                            buildReturnDirectPlaceholderMsg(toolUse, updatedResult);
                                    log.debug(
                                            "returnDirect: replaced tool result with placeholder"
                                                    + " for tool '{}'",
                                            toolUse.getName());
                                }
                                state.contextMutable().add(resultMsg);
                            });
        }

        /**
         * 本批次中最早请求停止的事件；若没有停止请求则返回最后一个事件。
         */
        private PostActingEvent earliestStopOrLast(List<PostActingEvent> events) {
            return events.stream()
                    .filter(PostActingEvent::isStopRequested)
                    .findFirst()
                    .orElseGet(() -> events.get(events.size() - 1));
        }

        /**
         * 单个工具结果是否可参与 returnDirect 短路：工具声明了 {@code returnDirect = true}，
         * 且结果确实执行成功。DENIED／ERROR／INTERRUPTED 结果绝不能作为最终答案展示。
         */
        private boolean isReturnDirectToolCall(Map.Entry<ToolUseBlock, ToolResultBlock> entry) {
            AgentTool tool = toolkit.getTool(entry.getKey().getName());
            if (tool == null || !tool.isReturnDirect()) {
                return false;
            }
            return determineToolResultState(entry.getValue()) == ToolResultState.SUCCESS;
        }

        /**
         * 构建占位 tool_result，仅替换输出，保留 ID／名称／状态。
         */
        private Msg buildReturnDirectPlaceholderMsg(ToolUseBlock toolUse, ToolResultBlock result) {
            ToolResultBlock placeholder =
                    placeholderResultBlock(toolUse.getId(), toolUse.getName(), result.getState());
            return ToolResultMessageBuilder.buildToolResultMsg(placeholder, toolUse, getName());
        }

        /**
         * 替代 returnDirect 工具真实结果进行持久化的占位 {@link ToolResultBlock}：
         * ID／名称／状态相同，输出换成占位句。
         */
        private ToolResultBlock placeholderResultBlock(
                String id, String name, ToolResultState state) {
            return ToolResultBlock.builder()
                    .id(id)
                    .name(name)
                    .output(TextBlock.builder().text(RETURN_DIRECT_PLACEHOLDER).build())
                    .state(state)
                    .build();
        }

        /**
         * 完成 returnDirect 轮次：合成结束助手消息、持久化、发出结束文本事件并记录日志。
         *
         * <p>框架内路径（{@code acting}）及外部恢复路径（{@code doCallInner}）共享，
         * 使两者产生相同的消息、上下文、返回契约及事件流结构。
         * {@code replyId} 将发出的事件与原回复关联（行动 replyId 或暂停的外部执行请求）。
         */
        private Msg finalizeReturnDirect(
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> pairs, String replyId) {
            Msg result = buildReturnDirectResultMsg(pairs);
            state.contextMutable().add(result);
            publishReturnDirectTextEvents(result, replyId);
            logReturnDirect(pairs);
            return result;
        }

        /**
         * 为 returnDirect 结束消息发出标准文本块生命周期（{@code TextBlockStart/Delta/End}），
         * 使 AG-UI、A2A 等事件流消费者获得与模型生成最终答案相同的投影。
         *
         * <p>增量事件将 {@link AgentEvent#METADATA_GENERATE_REASON} 设为 {@code TOOL_RETURN_DIRECT} 供审计。
         * 非文本块（图像、数据）不发出事件，继续通过 Msg 级 {@code AgentResultEvent} 传递。
         * 事件在结束消息返回前同步发布，因此早于 {@code AgentResultEvent}／{@code AgentEndEvent}。
         */
        private void publishReturnDirectTextEvents(Msg closingMsg, String replyId) {
            String effectiveReplyId =
                    replyId == null || replyId.isEmpty()
                            ? UUID.randomUUID().toString().replace("-", "")
                            : replyId;
            for (TextBlock block : closingMsg.getContentBlocks(TextBlock.class)) {
                String blockId = UUID.randomUUID().toString().replace("-", "");
                publishEvent(new TextBlockStartEvent(effectiveReplyId, blockId));
                publishEvent(
                        new TextBlockDeltaEvent(effectiveReplyId, blockId, block.getText())
                                .withMetadata(
                                        Map.of(
                                                AgentEvent.METADATA_GENERATE_REASON,
                                                GenerateReason.TOOL_RETURN_DIRECT.name())));
                publishEvent(new TextBlockEndEvent(effectiveReplyId, blockId));
            }
        }

        /**
         * 记录 returnDirect 短路：{@code info} 表示工具结果直接作为最终答案返回，
         * {@code debug} 补充工具数量及名称。
         */
        private void logReturnDirect(List<Map.Entry<ToolUseBlock, ToolResultBlock>> pairs) {
            List<String> names = pairs.stream().map(e -> e.getKey().getName()).toList();
            log.info(
                    "returnDirect: returning tool result(s) for {} directly as the final answer",
                    String.join(", ", names));
            log.debug("returnDirect: {} tool result(s) from tools {}", pairs.size(), names);
        }

        /**
         * 合成携带完整工具结果的结束助手消息，模拟原本由模型生成的最终答案。
         *
         * <p>多个工具的输出块按执行顺序（即 {@code pairs} 顺序，也就是模型 tool_calls 顺序）连接，
         * 不内嵌工具名称、不添加分隔块。与模型生成摘要一样，结束消息不携带逐块来源；
         * 消费者需要时通过块顺序及事件流工具结果事件中的工具 ID／名称关联，不能假设本轮是否短路。
         *
         * <p>结果包含零个内容块的工具（仅自定义转换器返回空输出列表时可能发生）不为结束消息贡献内容，
         * 对应模型摘要跳过无输出工具的行为；事件流也不为这种结果发出文本投影。
         * 空文本块（例如 MCP 工具的 {@code TextBlock("")}）仍是块，会原样通过。
         * 仅当整个批次产生零个块时，才插入单个 {@code "(no output)"} 占位内容，
         * 避免拒绝空助手内容的提供方报错。调用方可通过 {@link GenerateReason#TOOL_RETURN_DIRECT}
         * 及 {@code _tool_return_direct} 元数据将它与真实工具输出区分。
         * 声明 {@code returnDirect} 的工具应保证成功结果始终包含可展示内容块，
         * 使占位内容仅作为防御性回退，而非预期结果。
         *
         * <p>边界：消息根据钩子运行前的执行结果组装。有意忽略已弃用、计划移除的 PostActing 钩子改写，
         * 也忽略 {@code onModelCall} 中间件文本转换，因为这里不调用模型。
         * 内容转换／脱敏应使用工具结果转换器（或 {@code AgentTool} 装饰器）；
         * 在源头转换可同时统一作用于框架内路径与外部恢复路径。
         */
        private Msg buildReturnDirectResultMsg(
                List<Map.Entry<ToolUseBlock, ToolResultBlock>> pairs) {
            List<ContentBlock> content = new ArrayList<>();
            for (Map.Entry<ToolUseBlock, ToolResultBlock> pair : pairs) {
                content.addAll(pair.getValue().getOutput());
            }
            if (content.isEmpty()) {
                content.add(TextBlock.builder().text("(no output)").build());
            }
            return AssistantMessage.builder()
                    .name(getName())
                    .content(content)
                    .metadata(Map.of(MessageMetadataKeys.TOOL_RETURN_DIRECT, true))
                    .generateReason(GenerateReason.TOOL_RETURN_DIRECT)
                    .build();
        }

        /**
         * 达到最大迭代次数时生成摘要。
         */
        protected Mono<Msg> summarizing() {
            log.debug("Maximum iterations reached. Generating summary...");

            // 处理达到最大迭代次数之前尚未完成的工具调用
            List<ToolUseBlock> pendingTools =
                    MessageUtils.extractPendingToolCalls(state.contextMutable(), getName());
            if (!pendingTools.isEmpty()) {
                log.warn(
                        "Max iterations reached with {} pending tool calls. Adding error results.",
                        pendingTools.size());

                for (ToolUseBlock toolUse : pendingTools) {
                    ToolResultBlock errorResult =
                            ToolResultBlock.error(
                                    toolUse.getId(),
                                    "Tool execution cancelled because maximum iterations limit ("
                                            + maxIters
                                            + ") was reached");

                    Msg errorResultMsg =
                            ToolResultMessageBuilder.buildToolResultMsg(
                                    errorResult, toolUse, getName());
                    state.contextMutable().add(errorResultMsg);
                }
            }

            List<Msg> messageList = prepareSummaryMessages();
            GenerateOptions generateOptions = buildGenerateOptions();
            ReasoningContext context = new ReasoningContext(getName());
            Model summaryModel =
                    modelForCall(rc, ModelRequestPreparer.Purpose.SUMMARY, this::publishEvent);
            publishEvent(new ExceedMaxItersEvent("", maxIters, maxIters));

            return hookDispatcher
                    .firePreSummary(
                            messageList,
                            generateOptions,
                            summaryModel.getModelName(),
                            maxIters,
                            systemMsg)
                    .flatMap(
                            preSummaryEvent -> {
                                List<Msg> effectiveMessages =
                                        MessageUtils.prependSystemMessage(
                                                preSummaryEvent.getInputMessages(),
                                                preSummaryEvent.getSystemMessage());
                                GenerateOptions effectiveOptions =
                                        preSummaryEvent.getEffectiveGenerateOptions();

                                return summaryStream(
                                                context,
                                                effectiveMessages,
                                                effectiveOptions,
                                                summaryModel)
                                        .then(
                                                Mono.defer(
                                                        () ->
                                                                Mono.justOrEmpty(
                                                                        context
                                                                                .buildFinalMessage())))
                                        .flatMap(
                                                msg ->
                                                        hookDispatcher
                                                                .firePostSummary(
                                                                        msg,
                                                                        effectiveOptions,
                                                                        summaryModel.getModelName())
                                                                .map(
                                                                        postEvent -> {
                                                                            Msg finalMsg =
                                                                                    postEvent
                                                                                            .getSummaryMessage()
                                                                                            .withGenerateReason(
                                                                                                    GenerateReason
                                                                                                            .MAX_ITERATIONS);
                                                                            state.contextMutable()
                                                                                    .add(finalMsg);
                                                                            return finalMsg;
                                                                        }));
                            })
                    .onErrorResume(this::handleSummaryError);
        }

        /**
         * 流式发送摘要阶段模型调用产生的细粒度 {@link AgentEvent}。
         *
         * <p>结构与 {@link #reasoningStream} 相同，但通知摘要专用钩子（SummaryChunkEvent），不向模型传递工具 schema。
         *
         * @param context 用于片段累积的推理上下文
         * @param messages 要发送给模型的消息
         * @param options 生成选项
         * @param model 本次摘要调用使用的模型
         * @return 摘要模型调用的事件流
         */
        Flux<AgentEvent> summaryStream(
                ReasoningContext context,
                List<Msg> messages,
                GenerateOptions options,
                Model model) {

            Function<ModelCallInput, Flux<AgentEvent>> summaryModelCallCore =
                    mci -> summaryModelCallStream(context, mci, options);

            return MiddlewareChain.build(
                            middlewaresAt(MiddlewareBase.ExtensionPoint.ON_MODEL_CALL),
                            ReActAgent.this,
                            rc,
                            MiddlewareBase::onModelCall,
                            summaryModelCallCore)
                    .apply(new ModelCallInput(messages, List.of(), options, model))
                    .doOnNext(this::publishEvent);
        }

        private Flux<AgentEvent> summaryModelCallStream(
                ReasoningContext context, ModelCallInput mci, GenerateOptions hookOptions) {

            return Flux.defer(
                    () -> {
                        String callId = UUID.randomUUID().toString().replace("-", "");
                        return prepareRequest(mci, callId, ModelRequestPreparer.Purpose.SUMMARY)
                                .flatMapMany(
                                        prepared ->
                                                preparedSummaryModelCallStream(
                                                        context, prepared, hookOptions, callId));
                    });
        }

        private Flux<AgentEvent> preparedSummaryModelCallStream(
                ReasoningContext context,
                ModelCallInput mci,
                GenerateOptions hookOptions,
                String replyId) {

            ModelCallBlockLifecycle blockLifecycle = new ModelCallBlockLifecycle(replyId);

            Flux<AgentEvent> modelEvents =
                    Flux.defer(() -> mci.model().stream(mci.messages(), mci.tools(), mci.options()))
                            .concatMap(chunk -> checkInterrupted().thenReturn(chunk))
                            .concatMap(
                                    chunk ->
                                            Flux.deferContextual(
                                                    parentCtx -> {
                                                        List<Msg> chunkMsgs =
                                                                context.processChunk(chunk);
                                                        for (Msg msg : chunkMsgs) {
                                                            hookDispatcher
                                                                    .fireSummaryChunk(
                                                                            msg,
                                                                            context,
                                                                            hookOptions,
                                                                            mci.model()
                                                                                    .getModelName())
                                                                    .contextWrite(
                                                                            ctx ->
                                                                                    ctx.putAll(
                                                                                            parentCtx))
                                                                    .subscribe();
                                                        }

                                                        List<AgentEvent> events = new ArrayList<>();
                                                        for (ContentBlock block :
                                                                chunk.getContent()) {
                                                            if (block instanceof TextBlock tb) {
                                                                blockLifecycle.startText(events);
                                                                if (tb.getText() != null
                                                                        && !tb.getText()
                                                                                .isEmpty()) {
                                                                    events.add(
                                                                            new TextBlockDeltaEvent(
                                                                                    blockLifecycle
                                                                                            .replyId,
                                                                                    blockLifecycle
                                                                                            .currentTextBlockId(),
                                                                                    tb.getText()));
                                                                }
                                                            } else if (block
                                                                    instanceof ThinkingBlock tb) {
                                                                blockLifecycle.startThinking(
                                                                        events);
                                                                if (tb.getThinking() != null
                                                                        && !tb.getThinking()
                                                                                .isEmpty()) {
                                                                    events.add(
                                                                            new ThinkingBlockDeltaEvent(
                                                                                    blockLifecycle
                                                                                            .replyId,
                                                                                    blockLifecycle
                                                                                            .currentThinkingBlockId(),
                                                                                    tb
                                                                                            .getThinking()));
                                                                }
                                                            }
                                                        }
                                                        return Flux.fromIterable(events);
                                                    }));

            Flux<AgentEvent> endEvents =
                    Flux.defer(
                            () -> {
                                List<AgentEvent> events = new ArrayList<>();
                                blockLifecycle.flushAll(events);
                                events.add(new ModelCallEndEvent(replyId, context.getChatUsage()));
                                return Flux.fromIterable(events);
                            });

            return Flux.concat(Flux.just(modelCallStart(replyId)), modelEvents, endEvents);
        }

        private List<Msg> prepareSummaryMessages() {
            List<Msg> messageList = new ArrayList<>(state.contextMutable());
            messageList.add(
                    UserMessage.builder()
                            .name("user")
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    "You have failed to generate response within"
                                                            + " the maximum iterations. Now respond"
                                                            + " directly by summarizing the current"
                                                            + " situation.")
                                            .build())
                            .build());
            return messageList;
        }

        private Mono<Msg> handleSummaryError(Throwable error) {
            if (error instanceof InterruptedException) {
                return Mono.error(error);
            }
            log.error("Error generating summary", error);
            Msg errorMsg =
                    AssistantMessage.builder()
                            .name(getName())
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    String.format(
                                                            "Maximum iterations (%d) reached. Error"
                                                                    + " generating summary: %s",
                                                            maxIters, error.getMessage()))
                                            .build())
                            .metadata(Map.of(MessageMetadataKeys.SUMMARY_FAILED, true))
                            .generateReason(GenerateReason.MAX_ITERATIONS)
                            .build();
            state.contextMutable().add(errorMsg);
            return Mono.just(errorMsg);
        }

        // ==================== 辅助方法 ====================

        /**
         * 检查 ReAct 循环是否应终止。
         *
         * <p>含未完成调用或本地工具调用的响应继续进入行动；已完成的服务端工具调用无需本地执行。
         * 无工具调用的响应只有携带可见内容才结束；空响应或仅思考响应（答案全在推理通道）
         * 会重新进入推理，受 {@code maxIters} 限制，而非静默以空回复结束。
         *
         * @param msg 推理消息
         * @return 应结束时为 true，应继续行动时为 false
         */
        private boolean isFinished(Msg msg) {
            if (msg == null) {
                return true;
            }
            List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
            if (toolCalls.isEmpty()) {
                return msg.getContentBlocks(TextBlock.class).stream()
                        .anyMatch(
                                textBlock ->
                                        textBlock.getText() != null
                                                && !textBlock.getText().isBlank());
            }

            // 服务端工具由提供方执行，结果随同一
            // 助手消息到达；若每个调用都是有结果的服务端工具，
            // 则无需再执行行动。没有结果的服务端工具调用
            // （例如 pause_turn）会保持循环运行，让会话回到
            // 提供方继续处理。
            Set<String> inlineResultIds = MessageUtils.inlineServerToolResultIds(msg);
            return toolCalls.stream()
                    .allMatch(
                            toolCall ->
                                    toolCall.isServerTool()
                                            && inlineResultIds.contains(toolCall.getId()));
        }

        /**
         * 构建最终回复为空后、重新进入推理前注入的合成 {@code system} 提醒。
         *
         * <p>不同于 {@code TaskReminderMiddleware} 的临时待办提醒，此提醒会写入智能体上下文并持久化
         * 到会话状态（类似 {@code SubagentsMiddleware} 的任务交付提醒），使纠正信号在后续轮次仍可见。
         * 每次调用的累积量受 {@code maxIters} 限制。
         */
        private static Msg buildEmptyResponseReminder() {
            return Msg.builder()
                    .role(MsgRole.USER)
                    .name("system")
                    .content(TextBlock.builder().text(EMPTY_RESPONSE_REMINDER_TEXT).build())
                    .metadata(
                            Map.of(
                                    Msg.METADATA_SYNTHETIC,
                                    true,
                                    Msg.METADATA_REMINDER_KIND,
                                    "empty_response"))
                    .build();
        }

        /**
         * 构建发出 {@link AllToolsDeniedEvent} 的 onActing 中间件链，
         * 给中间件机会发出 {@link RequestStopEvent}。若请求停止，智能体立即返回；否则继续下一轮迭代。
         */
        private Mono<Msg> emitAllToolsDeniedThroughMiddleware(
                List<ToolUseBlock> deniedToolCalls, int iter) {
            AtomicReference<RequestStopEvent> stopRef = new AtomicReference<>();

            Function<ActingInput, Flux<AgentEvent>> core =
                    ai -> Flux.just(new AllToolsDeniedEvent(ai.toolCalls()));

            Flux<AgentEvent> stream =
                    MiddlewareChain.build(
                                    middlewaresAt(MiddlewareBase.ExtensionPoint.ON_ACTING),
                                    ReActAgent.this,
                                    rc,
                                    MiddlewareBase::onActing,
                                    core)
                            .apply(new ActingInput(deniedToolCalls));

            return stream.doOnNext(
                            ev -> {
                                if (ev instanceof RequestStopEvent rs) {
                                    stopRef.compareAndSet(null, rs);
                                }
                            })
                    .then(
                            Mono.defer(
                                    () -> {
                                        RequestStopEvent rs = stopRef.get();
                                        if (rs != null) {
                                            Msg lastMsg =
                                                    MessageUtils.lastAssistantMessage(
                                                            state.contextMutable());
                                            GenerateReason reason =
                                                    rs.getGenerateReason() != null
                                                            ? rs.getGenerateReason()
                                                            : GenerateReason.ALL_TOOLS_DENIED;
                                            if (lastMsg != null) {
                                                return Mono.just(
                                                        lastMsg.withGenerateReason(reason));
                                            }
                                            return Mono.just(
                                                    Msg.builder()
                                                            .role(MsgRole.ASSISTANT)
                                                            .textContent("")
                                                            .generateReason(reason)
                                                            .build());
                                        }
                                        return executeIteration(iter + 1);
                                    }));
        }

        /**
         * 从最近一条助手消息提取工具调用。
         */
        private List<ToolUseBlock> extractRecentToolCalls() {
            return MessageUtils.extractRecentToolCalls(state.contextMutable(), getName());
        }

        // ==================== 工具调用状态辅助方法（权限 HITL） ====================

        /**
         * 定位上下文最后一条助手 Msg，替换 ID 匹配给定映射键的每个 {@link ToolUseBlock} 的 {@code state}。
         * 对应 Python 的 {@code _update_tool_call_state}，但采用批量操作以减少列表重建。
         */
        private void updateToolCallStates(Map<String, ToolCallState> updates) {
            if (updates == null || updates.isEmpty()) {
                return;
            }
            Msg lastAssistant = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (lastAssistant == null) {
                return;
            }
            Map<String, ToolUseBlock> replacements = new HashMap<>();
            for (ToolUseBlock toolUse : lastAssistant.getContentBlocks(ToolUseBlock.class)) {
                ToolCallState newState = updates.get(toolUse.getId());
                if (newState != null) {
                    replacements.put(toolUse.getId(), toolUse.withState(newState));
                }
            }
            MessageUtils.replaceToolUseBlocks(state.contextMutable(), replacements);
        }

        /**
         * 最后一条助手 Msg 中处于 ASKING 状态（HITL 待确认）的 ToolUseBlock。
         */
        private List<ToolUseBlock> askingToolCalls() {
            Msg last = MessageUtils.lastAssistantMessage(state.contextMutable());
            if (last == null) {
                return List.of();
            }
            return last.getContent().stream()
                    .filter(
                            b ->
                                    b instanceof ToolUseBlock t
                                            && t.getState() == ToolCallState.ASKING)
                    .map(ToolUseBlock.class::cast)
                    .toList();
        }
    }

    protected GenerateOptions buildGenerateOptions() {
        // 优先使用用户配置的 generateOptions（如果存在）
        GenerateOptions baseOptions = generateOptions;

        // 将智能体级重试额度叠加到显式单次调用设置之下。
        if (modelConfig != null) {
            GenerateOptions retryBudgetOptions =
                    GenerateOptions.builder()
                            .executionConfig(
                                    ExecutionConfig.builder()
                                            .maxAttempts(modelConfig.maxRetries())
                                            .build())
                            .build();
            baseOptions = GenerateOptions.mergeOptions(baseOptions, retryBudgetOptions);
        }

        // 设置了 modelExecutionConfig 时，将其合并到选项
        if (modelExecutionConfig != null) {
            GenerateOptions execConfigOptions =
                    GenerateOptions.builder().executionConfig(modelExecutionConfig).build();
            baseOptions = GenerateOptions.mergeOptions(execConfigOptions, baseOptions);
        }

        return baseOptions != null ? baseOptions : GenerateOptions.builder().build();
    }

    private Model modelForCall(
            RuntimeContext callContext,
            ModelRequestPreparer.Purpose purpose,
            Consumer<AgentEvent> events) {
        Model fallbackModel = modelConfig.fallbackModel();
        if (fallbackModel == null) {
            return SessionModels.wrap(model, callContext, purpose.name());
        }
        FailoverListener failoverListener = modelConfig.failoverListener();

        AtomicReference<Model> activeModel = new AtomicReference<>(model);
        return new SessionModels.ManagedModel() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                // 将同步模型初始化失败导入相同的首信号回退路径。
                Flux<ChatResponse> primaryFlux =
                        Flux.defer(
                                () -> {
                                    activeModel.set(model);
                                    return SessionModels.wrap(model, callContext, purpose.name())
                                            .stream(messages, tools, options);
                                });
                return primaryFlux.switchOnFirst(
                        (signal, flux) -> {
                            if (signal.isOnError()) {
                                Throwable error = signal.getThrowable();
                                if (SessionLogException.causedBy(error)) return flux;
                                SessionRecorder recorder = SessionRecorder.from(callContext);
                                if (recorder != null)
                                    recorder.append(
                                            "model/retry",
                                            Map.of(
                                                    "reason",
                                                    "fallback",
                                                    "from",
                                                    model.getModelName(),
                                                    "to",
                                                    fallbackModel.getModelName()));
                                activeModel.set(fallbackModel);
                                log.warn(
                                        "Primary model {} failed, switching to fallback {}",
                                        model.getModelName(),
                                        fallbackModel.getModelName(),
                                        error);
                                notifyFailover(failoverListener, model, error);
                                if (modelRequestPreparer == null) {
                                    return SessionModels.wrap(
                                            fallbackModel, callContext, purpose.name())
                                            .stream(messages, tools, options);
                                }
                                RuntimeContext rc =
                                        callContext == null ? RuntimeContext.empty() : callContext;
                                String callId = UUID.randomUUID().toString().replace("-", "");
                                String key =
                                        ModelRequestPreparer.MANIFEST_ATTRIBUTE_PREFIX + callId;
                                return Mono.defer(
                                                () ->
                                                        modelRequestPreparer.prepareObserved(
                                                                ReActAgent.this,
                                                                rc,
                                                                new ModelCallInput(
                                                                        messages,
                                                                        tools,
                                                                        options,
                                                                        fallbackModel),
                                                                callId,
                                                                purpose,
                                                                events))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new IllegalStateException(
                                                                "Fallback preparation returned no"
                                                                        + " request")))
                                        .flatMapMany(
                                                prepared -> {
                                                    ModelCallStartEvent start =
                                                            new ModelCallStartEvent(callId);
                                                    Object manifest = rc.get(key);
                                                    rc.put(key, null);
                                                    if (manifest != null)
                                                        start.withMetadataEntry(
                                                                "contextManifest", manifest);
                                                    events.accept(start);
                                                    return Flux.defer(
                                                                    () ->
                                                                            io
                                                                                    .agentscope
                                                                                    .core
                                                                                    .session
                                                                                    .SessionModels
                                                                                    .wrap(
                                                                                            prepared
                                                                                                    .model(),
                                                                                            rc,
                                                                                            purpose
                                                                                                    .name())
                                                                                    .stream(
                                                                                            prepared
                                                                                                    .messages(),
                                                                                            prepared
                                                                                                    .tools(),
                                                                                            prepared
                                                                                                    .options()))
                                                            .doFinally(
                                                                    ignored ->
                                                                            events.accept(
                                                                                    new ModelCallEndEvent(
                                                                                            callId,
                                                                                            null)));
                                                })
                                        .doFinally(ignored -> rc.put(key, null));
                            }
                            return flux;
                        });
            }

            @Override
            public String getModelName() {
                return activeModel.get().getModelName();
            }

            @Override
            public boolean supportsNativeStructuredOutput() {
                return activeModel.get().supportsNativeStructuredOutput();
            }

            @Override
            public int getContextWindowSize() {
                return activeModel.get().getContextWindowSize();
            }
        };
    }

    /**
     * 在切换位置通知回退监听器。监听器异常在此被隔离：记录日志，但不影响切换及后续回退调用。
     */
    private static void notifyFailover(FailoverListener listener, Model primary, Throwable error) {
        if (listener == null) {
            return;
        }
        try {
            listener.onFailover(primary, error);
        } catch (Exception e) {
            log.warn("Failover listener threw an exception, ignoring", e);
        }
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        return Mono.deferContextual(
                cv -> {
                    CallExecution scope = scopeFrom(cv);
                    // 从本次执行自己的中断信号解析来源。
                    InterruptSource source = scope.interruption.getSource();
                    if (source == InterruptSource.SYSTEM) {
                        String requestId =
                                (String) cv.getOrDefault(AgentBase.SHUTDOWN_REQUEST_ID_KEY, null);
                        shutdownManager.saveOnInterruptObserved(requestId);
                        return Mono.error(new AgentShuttingDownException());
                    }
                    // 追加恢复消息前，协调全部待处理工具调用。
                    // 否则推理写入的 tool_use 会在没有
                    // 匹配工具结果的情况下持久化，使下一次运行继承不一致的 AgentState。
                    scope.synthesizeErrorResultsForPendingToolCalls();
                    String recoveryText =
                            "I noticed that you have interrupted me. What can I do for you?";
                    Msg recoveryMsg =
                            AssistantMessage.builder()
                                    .name(getName())
                                    .content(TextBlock.builder().text(recoveryText).build())
                                    .generateReason(GenerateReason.INTERRUPTED)
                                    .build();
                    scope.state.contextMutable().add(recoveryMsg);
                    return saveStateToSession(scope)
                            .thenReturn(recoveryMsg)
                            .onErrorResume(
                                    e -> {
                                        log.warn(
                                                "Failed to save agent state after user interrupt",
                                                e);
                                        return Mono.just(recoveryMsg);
                                    });
                });
    }

    @Override
    protected Mono<Void> doObserve(Msg msg) {
        if (msg != null && sessionLogEnabled()) {
            return Mono.<Void>fromRunnable(
                            () ->
                                    updateAgentState(
                                            null,
                                            "message_observed",
                                            state -> state.contextMutable().add(msg)))
                    .subscribeOn(Schedulers.boundedElastic());
        }
        if (msg != null) {
            getAgentState().contextMutable().add(msg);
        }
        return Mono.empty();
    }

    // ==================== 访问方法 ====================

    /**
     * 返回智能体工具集；请求专属视图不会修改其注册表。
     */
    public Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * 返回构建器配置的技能视图基准 {@link SkillFilter}。
     * 绝不为 {@code null}，未设置时默认 {@link SkillFilter#all()}。
     * 自动安装的 {@link DynamicSkillMiddleware} 应用该基准，再叠加
     * 通过 {@code RuntimeContext.get(SkillFilter.class)} 提供的单次调用过滤器。
     */
    public SkillFilter getSkillFilter() {
        return skillFilter;
    }

    /**
     * 返回构建器配置的稳定技能实体化目录；未设置时为 {@code null}，使用方回退到自行分配的临时目录。
     */
    public Path getSkillWorkDir() {
        return skillWorkDir;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public Model getModel() {
        return model;
    }

    public int getMaxIters() {
        return maxIters;
    }

    /**
     * 获取此智能体配置的生成选项。
     *
     * @return 生成选项；未配置时为 null
     */
    public GenerateOptions getGenerateOptions() {
        return generateOptions;
    }

    /**
     * @deprecated 请显式提供会话身份，使用 {@link #getAgentState(RuntimeContext)} 或
     * {@link #getAgentState(String, String)}。此方法委托给默认会话槽位。
     */
    @Deprecated
    @Override
    public AgentState getAgentState() {
        return getAgentState(null, defaultSessionId);
    }

    /**
     * 返回给定 {@link RuntimeContext} 所标识会话的 {@link AgentState}。
     *
     * @param ctx 运行时上下文（使用 {@code getUserId()} 和 {@code getSessionId()}）
     * @return 所标识会话的智能体状态
     */
    public AgentState getAgentState(RuntimeContext ctx) {
        if (SessionRecorder.from(ctx) != null && ctx.getAgentState() != null)
            return ctx.getAgentState();
        if (sessionLogEnabled()) return nativeWorkingCopy(stateContext(ctx));
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        return getAgentState(uid, sid);
    }

    /**
     * 从已提交日志读取原生状态，返回独立工作副本。可选的 get/mutate/save 兼容路径在保存时拒绝过期副本。
     * 管理性修改优先使用 {@link #updateAgentState(RuntimeContext, String, Consumer)}。
     * 无原生日志的独立智能体保留状态存储缓存行为。
     */
    public AgentState getAgentState(String userId, String sessionId) {
        if (sessionLogEnabled()) return nativeWorkingCopy(stateContext(userId, sessionId));
        String slot = slotKey(userId, sessionId);
        return stateCache.computeIfAbsent(
                slot,
                k -> {
                    VersionedState<AgentState> versioned =
                            loadOrCreateAgentStateForSlot(
                                    stateStore,
                                    userId,
                                    sessionId,
                                    initialPermissionContext,
                                    getAgentId(),
                                    initialActiveToolGroups);
                    slotVersions.put(slot, versioned.version());
                    return versioned.value();
                });
    }

    private AgentState nativeWorkingCopy(RuntimeContext ctx) {
        SessionStateAccess.Snapshot snapshot = inspectAgentState(ctx);
        nativeStateCopies.put(slotKey(ctx.getUserId(), ctx.getSessionId()), snapshot);
        return snapshot.state();
    }

    private RuntimeContext stateContext(RuntimeContext context) {
        RuntimeContext source = context == null ? RuntimeContext.empty() : context;
        String sid = source.getSessionId();
        return RuntimeContext.builder(source)
                .sessionId(sid == null || sid.isBlank() ? defaultSessionId : sid)
                .build();
    }

    private RuntimeContext stateContext(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId == null || sessionId.isBlank() ? defaultSessionId : sessionId)
                .build();
    }

    private AgentState initialState(RuntimeContext context) {
        return freshState(
                initialPermissionContext,
                getAgentId(),
                context.getUserId(),
                context.getSessionId(),
                initialActiveToolGroups);
    }

    /**
     * 检查独立的原生状态快照，不修改历史或本地工作副本。
     */
    public SessionStateAccess.Snapshot inspectAgentState(RuntimeContext context) {
        RuntimeContext ctx = stateContext(context);
        if (!sessionLogEnabled())
            throw new IllegalStateException("Native session history is disabled");
        return SessionStateAccess.read(sessionLog(ctx), () -> initialState(ctx));
    }

    /**
     * 对最新状态应用管理性变更并立即持久化。原生会话获取写入者租约、拒绝活动执行并提交检查点。
     * 回调必须简短，不得调用模型／工具或产生外部副作用。
     */
    public AgentState updateAgentState(
            RuntimeContext context, String reason, Consumer<AgentState> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        RuntimeContext ctx = stateContext(context);
        if (sessionLogEnabled()) {
            SessionStateAccess.Snapshot result =
                    SessionStateAccess.update(
                            sessionLog(ctx), () -> initialState(ctx), reason, mutation);
            clearStateCache(ctx);
            return result.state();
        }
        AgentState state = getAgentState(ctx);
        mutation.accept(state);
        permissionEngineCache.put(
                slotKey(ctx.getUserId(), ctx.getSessionId()),
                new PermissionEngine(state.getPermissionContext()));
        saveAgentState(ctx);
        return state;
    }

    /**
     * 清空所有本地缓存的会话状态及权限引擎。
     *
     * <p>仅释放此智能体持有的内存缓存，不删除或修改所配置 {@link AgentStateStore} 中的任何状态。
     * 配置存储后，后续 {@code getAgentState(...)} 或 {@code call(...)} 会从存储重新加载会话。
     *
     * <p>应在进行中的调用全部完成后调用。仍持有 {@link AgentState} 引用的调用方或已捕获状态的
     * 进行中调用可能继续保留该对象，直到释放引用。
     */
    public void clearStateCache() {
        stateCache.clear();
        nativeStateCopies.clear();
        permissionEngineCache.clear();
        slotVersions.clear();
    }

    /**
     * 清空 {@code ctx} 所标识会话的本地缓存状态及权限引擎。
     *
     * @param ctx 标识会话的运行时上下文；缺少会话 ID 时使用默认会话 ID
     */
    public void clearStateCache(RuntimeContext ctx) {
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        clearStateCache(uid, sid);
    }

    /**
     * 清空一个 {@code (userId, sessionId)} 槽位的本地缓存状态及权限引擎。
     *
     * <p>仅释放本地引用，不删除配置的 {@link AgentStateStore} 中的对应状态；下次访问会重新加载持久化状态。
     *
     * @param userId 槽位用户身份（{@code null} 表示匿名／单租户）
     * @param sessionId 会话身份；{@code null} 或空白时使用默认会话 ID
     */
    public void clearStateCache(String userId, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        String slot = slotKey(userId, sid);
        stateCache.remove(slot);
        nativeStateCopies.remove(slot);
        permissionEngineCache.remove(slot);
        slotVersions.remove(slot);
    }

    /**
     * 清空 {@code ctx} 所标识会话中模型可见的对话上下文。
     *
     * <p>保留会话身份、权限配置、工具状态、任务及计划模式状态。配置 {@link AgentStateStore} 且会话已持久化时，
     * 先重新加载最新持久化状态，再仅清除对话消息及压缩摘要。更新后立即持久化。
     *
     * <p>原生会话将重置记录为检查点，并拒绝活动写入者或待处理交互。
     * 无缓存也无持久化状态的独立状态存储会话保持不变。
     *
     * <p>此方法不取消进行中调用。应在会话当前调用完成后使用，确保下次调用从空对话上下文开始。
     *
     * @param ctx 标识会话的运行时上下文；缺少会话 ID 时使用默认会话 ID
     */
    public void clearContext(RuntimeContext ctx) {
        if (sessionLogEnabled()) {
            clearNativeContext(ctx);
            return;
        }
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        clearContext(uid, sid);
    }

    /**
     * 清空一个 {@code (userId, sessionId)} 会话中模型可见的对话上下文。
     *
     * <p>会话身份保持不变。与新建会话不同，仅删除对话消息及压缩摘要，保留权限配置、工具状态、任务及计划模式状态。
     * 配置 {@link AgentStateStore} 且会话已持久化时，先重新加载最新持久化状态，再清空并立即持久化更新。
     *
     * <p>原生会话将重置记录为检查点，并拒绝活动写入者或待处理交互。
     * 无缓存也无持久化状态的独立状态存储会话保持不变。
     *
     * <p>此方法不取消进行中调用。应在会话当前调用完成后使用，确保下次调用从空对话上下文开始。
     *
     * @param userId 槽位用户身份（{@code null} 表示匿名／单租户）
     * @param sessionId 会话身份；{@code null} 或空白时使用默认会话 ID
     */
    public void clearContext(String userId, String sessionId) {
        if (sessionLogEnabled()) {
            clearNativeContext(stateContext(userId, sessionId));
            return;
        }
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        String slot = slotKey(userId, sid);
        AgentState state;
        if (stateStore != null) {
            if (stateStore.exists(userId, sid)) {
                VersionedState<AgentState> versioned =
                        loadOrCreateAgentStateForSlot(
                                stateStore,
                                userId,
                                sid,
                                initialPermissionContext,
                                getAgentId(),
                                initialActiveToolGroups);
                state = versioned.value();
                stateCache.put(slot, state);
                slotVersions.put(slot, versioned.version());
            } else {
                state = stateCache.get(slot);
                if (state == null) {
                    return;
                }
            }
        } else {
            state = stateCache.get(slot);
            if (state == null) {
                return;
            }
        }
        state.contextMutable().clear();
        state.setSummary("");
        saveAgentState(userId, sid);
    }

    private void clearNativeContext(RuntimeContext context) {
        updateAgentState(
                context,
                "context_cleared",
                state -> {
                    state.contextMutable().clear();
                    state.setSummary("");
                });
    }

    /**
     * 运行时切换指定 {@code (userId, sessionId)} 会话的 {@link PermissionMode}，
     * 并重建该会话缓存的 {@link PermissionEngine}，让下次工具评估生效。
     * 保留已有规则和工作目录，仅改变模式；变更会持久化，使该会话下一次 {@code call} 能看到。
     *
     * <p>可用于实现用户主动、有意开启的“绕过权限”开关（传入 {@link PermissionMode#BYPASS}），
     * 或随后恢复更严格的执行（传入 {@link PermissionMode#DEFAULT}）。
     * {@code BYPASS} 禁用全部规则评估，应作为显式的会话级操作，最好配合沙箱环境使用。
     *
     * <p>原生会话在执行持有写入者租约时拒绝变更；独立状态存储会话保留当前调用引擎，直到下一次调用。
     *
     * @param userId 槽位用户身份（可以为 {@code null}）
     * @param sessionId 会话身份（{@code null} 时回退到默认会话 ID）
     * @param mode 要切换到的权限模式
     */
    public void setPermissionMode(String userId, String sessionId, PermissionMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        if (sessionLogEnabled()) {
            updateAgentState(
                    stateContext(userId, sessionId),
                    "permission_mode_changed",
                    state ->
                            state.setPermissionContext(
                                    state.getPermissionContext().withMode(mode)));
            return;
        }
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        AgentState state = getAgentState(userId, sid);
        installPermissionContext(userId, sid, state, state.getPermissionContext().withMode(mode));
    }

    /**
     * 替换一个 {@code (userId, sessionId)} 槽位的权限上下文，重建该槽位权限引擎并持久化更新。
     *
     * <p>原生会话在执行持有写入者租约时拒绝变更。替换仅作用于该槽位后续调用，不影响其他会话。
     *
     * @param userId 槽位用户身份（可以为 {@code null}）
     * @param sessionId 会话身份（{@code null} 时回退到默认会话 ID）
     * @param permissionContext 完整替换上下文
     */
    public void replacePermissionContext(
            String userId, String sessionId, PermissionContextState permissionContext) {
        Objects.requireNonNull(permissionContext, "permissionContext must not be null");
        if (sessionLogEnabled()) {
            updateAgentState(
                    stateContext(userId, sessionId),
                    "permission_context_replaced",
                    state -> state.setPermissionContext(permissionContext));
            return;
        }
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        AgentState state = getAgentState(userId, sid);
        installPermissionContext(userId, sid, state, permissionContext);
    }

    private void installPermissionContext(
            String userId,
            String sessionId,
            AgentState state,
            PermissionContextState permissionContext) {
        state.setPermissionContext(permissionContext);
        permissionEngineCache.put(
                slotKey(userId, sessionId), new PermissionEngine(permissionContext));
        saveAgentState(userId, sessionId);
    }

    /**
     * 切换给定 {@link RuntimeContext} 所标识会话的 {@link PermissionMode}。
     * 参见 {@link #setPermissionMode(String, String, PermissionMode)}。
     *
     * @param ctx 标识会话的运行时上下文
     * @param mode 要切换到的权限模式
     */
    public void setPermissionMode(RuntimeContext ctx, PermissionMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        if (sessionLogEnabled()) {
            updateAgentState(
                    ctx,
                    "permission_mode_changed",
                    state ->
                            state.setPermissionContext(
                                    state.getPermissionContext().withMode(mode)));
            return;
        }
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        setPermissionMode(uid, sid, mode);
    }

    /**
     * 返回给定 {@code (userId, sessionId)} 会话当前的 {@link PermissionMode}。
     *
     * @param userId 槽位用户身份（可以为 {@code null}）
     * @param sessionId 会话身份（{@code null} 时回退到默认会话 ID）
     * @return 会话当前权限模式
     */
    public PermissionMode getPermissionMode(String userId, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? defaultSessionId : sessionId;
        return getAgentState(userId, sid).getPermissionContext().getMode();
    }

    /**
     * 持久化给定 {@link RuntimeContext} 所标识会话缓存的 {@link AgentState}。
     * 未配置存储或会话从未加载时不做操作。
     *
     * @param ctx 标识待保存会话的运行时上下文
     */
    public void saveAgentState(RuntimeContext ctx) {
        SessionRecorder recorder = SessionRecorder.from(ctx);
        if (recorder != null && ctx.getAgentState() != null) {
            recorder.checkpointNow(ctx.getAgentState(), "explicit_state_save");
            return;
        }
        if (sessionLogEnabled()) {
            saveNativeWorkingCopy(stateContext(ctx));
            return;
        }
        String uid = ctx != null ? ctx.getUserId() : null;
        String sid = ctx != null ? ctx.getSessionId() : null;
        if (sid == null || sid.isBlank()) {
            sid = defaultSessionId;
        }
        saveAgentState(uid, sid);
    }

    /**
     * 仅当原始已提交前缀仍为当前版本时保存独立原生工作副本；拒绝并发写入者或过期副本。
     * 独立状态存储智能体则按已配置冲突策略持久化缓存状态。缺少工作副本时不做操作。
     * 新代码优先使用 {@link #updateAgentState(RuntimeContext, String, Consumer)}。
     */
    public void saveAgentState(String userId, String sessionId) {
        if (sessionLogEnabled()) {
            saveNativeWorkingCopy(stateContext(userId, sessionId));
            return;
        }
        if (stateStore == null) {
            return;
        }
        String slot = slotKey(userId, sessionId);
        AgentState s = stateCache.get(slot);
        if (s != null) {
            long expected = slotVersions.getOrDefault(slot, AgentStateStore.UNVERSIONED);
            persistAgentStateCas(userId, sessionId, slot, s, expected, s.getContext().size());
        }
    }

    private void saveNativeWorkingCopy(RuntimeContext ctx) {
        String slot = slotKey(ctx.getUserId(), ctx.getSessionId());
        SessionStateAccess.Snapshot snapshot = nativeStateCopies.get(slot);
        if (snapshot != null) {
            SessionStateAccess.Snapshot saved =
                    SessionStateAccess.replace(
                            sessionLog(ctx),
                            () -> initialState(ctx),
                            "explicit_state_save",
                            snapshot);
            nativeStateCopies.replace(
                    slot,
                    snapshot,
                    new SessionStateAccess.Snapshot(saved.asOfSeq(), snapshot.state()));
        }
    }

    /**
     * 返回此智能体已观测到的乐观并发冲突次数。
     */
    public long getStateConflictCount() {
        return stateConflictCount.get();
    }

    /**
     * 返回 agent_state 保存使用的已配置 {@link ConflictPolicy}。
     */
    public ConflictPolicy getConflictPolicy() {
        return conflictPolicy;
    }

    /**
     * 返回用于状态持久化的 {@link AgentStateStore}，或 {@code null}。
     */
    public AgentStateStore getStateStore() {
        return stateStore;
    }

    /**
     * 返回构建器阶段设置的回退 {@code sessionId}，当调用的 {@link RuntimeContext} 未携带 {@code sessionId} 时使用。
     */
    public String getDefaultSessionId() {
        return defaultSessionId;
    }

    /**
     * 返回模型调用配置（重试、超时）。
     */
    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    /**
     * 返回推理循环配置（maxIters、stopOnReject）。
     */
    public ReactConfig getReactConfig() {
        return reactConfig;
    }

    /**
     * @deprecated 请改用 {@code getAgentState(userId, sessionId).getPermissionContext()}。
     */
    @Deprecated
    public PermissionEngine getPermissionEngine() {
        String slot = slotKey(null, defaultSessionId);
        AgentState s = getAgentState(null, defaultSessionId);
        return permissionEngineCache.computeIfAbsent(
                slot, k -> new PermissionEngine(s.getPermissionContext()));
    }

    /**
     * @deprecated 请改用 {@code getAgentState(userId, sessionId).getPermissionContext()}。
     */
    @Deprecated
    public PermissionContextState getPermissionContext() {
        return getAgentState().getPermissionContext();
    }

    /**
     * 返回已注册中间件的不可变列表。
     */
    public List<MiddlewareBase> getMiddlewares() {
        return middlewares;
    }

    public ModelRequestPreparer getModelRequestPreparer() {
        return modelRequestPreparer;
    }

    /**
     * 返回每次模型调用的 {@link ExecutionConfig}；未设置时为 {@code null}。
     */
    public ExecutionConfig getModelExecutionConfig() {
        return modelExecutionConfig;
    }

    /**
     * 返回每次工具调用的 {@link ExecutionConfig}；未设置时为 {@code null}。
     */
    public ExecutionConfig getToolExecutionConfig() {
        return toolExecutionConfig;
    }

    /**
     * 返回构建时绑定的 {@link ToolExecutionContext}；未设置时为 {@code null}。
     */
    public ToolExecutionContext getToolExecutionContext() {
        return toolExecutionContext;
    }

    /**
     * 返回是否启用待处理工具调用恢复（HITL：跨 {@code call()} 调用恢复仍处于待处理状态的工具调用）。
     */
    public boolean isPendingToolRecoveryEnabled() {
        return enablePendingToolRecovery;
    }

    /**
     * 返回系统提示（{@link #getSysPrompt()} 的别名）。
     */
    public String getSystemPrompt() {
        return sysPrompt;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void close() {
        // 释放构造方法中注册的 ShutdownStateSaver，避免临时／
        // 单次调用智能体实例被 GracefulShutdownManager.stateSavers 持有。
        shutdownManager.unbindStateSaver(this);
        clearStateCache();
    }

    // ==================== 构建器 ====================

    @SuppressWarnings("deprecation")
    public static class Builder {
        private SessionHistoryMode sessionHistoryMode = SessionHistoryMode.EVENT_LOG;

        public Builder sessionHistoryMode(SessionHistoryMode mode) {
            this.sessionHistoryMode = Objects.requireNonNull(mode);
            return this;
        }

        private SessionLogStore sessionLogStore;
        private String sessionLogAgentId;

        public Builder sessionLogAgentId(String agentId) {
            this.sessionLogAgentId = Objects.requireNonNull(agentId);
            return this;
        }

        /**
         * 将原生日志设为此智能体会话状态的权威来源。
         */
        public Builder sessionLogStore(SessionLogStore store) {
            this.sessionLogStore = Objects.requireNonNull(store);
            return this;
        }

        String name;
        String agentId;
        String description;
        String sysPrompt;
        Model model;
        Toolkit toolkit = new Toolkit();

        int maxIters = 10;
        ExecutionConfig modelExecutionConfig;
        ExecutionConfig toolExecutionConfig;
        GenerateOptions generateOptions;
        private boolean validateStructuredOutput = true;
        final Set<Hook> hooks = new LinkedHashSet<>();
        private final List<MiddlewareBase> middlewares = new ArrayList<>();
        private ModelRequestPreparer modelRequestPreparer;
        private ActionObserver actionObserver;

        /**
         * 安装已确认行动记录。未配置的 ReAct 智能体保持正常循环。
         */
        public Builder actionObserver(ActionObserver observer) {
            this.actionObserver = Objects.requireNonNull(observer);
            return this;
        }

        /**
         * 安装位于中间件洋葱层之外的最终请求准备边界。
         */
        public Builder modelRequestPreparer(ModelRequestPreparer preparer) {
            this.modelRequestPreparer = Objects.requireNonNull(preparer);
            return this;
        }

        private boolean enableMetaTool = false;
        private boolean taskListEnabled = false;
        private boolean taskRequirementsEnabled = false;

        /**
         * 独立于待办跟踪启用候选需求提议。
         */
        public Builder enableTaskRequirements(boolean enabled) {
            taskRequirementsEnabled = enabled;
            return this;
        }

        private ToolExecutionContext toolExecutionContext;
        private boolean enablePendingToolRecovery = false;

        // 2.0 核心字段
        private PermissionContextState permissionContext;

        // 支持 ModelConfig／ReactConfig 值的扁平设置方法
        private Integer flatMaxRetries;
        private Model flatFallbackModel;
        private FailoverListener flatFailoverListener;
        private Boolean flatStopOnReject;
        private AgentStateStore stateStore;
        private ConflictPolicy conflictPolicy;
        private String defaultSessionId;

        // ==================== 1.x 旧版兼容字段 ====================
        // 以下字段支撑已弃用的 `longTermMemory(...)`、`knowledge(...)`、
        // `skillBox(...)` 设置方法。build() 期间由 configureXxx() 消费，
        // 使旧版 1.x 用户代码保持等价运行时行为。

        @Deprecated(forRemoval = true, since = "2.0.0")
        private LongTermMemory longTermMemory;

        @Deprecated(forRemoval = true, since = "2.0.0")
        private LongTermMemoryMode longTermMemoryMode = LongTermMemoryMode.BOTH;

        @Deprecated(forRemoval = true, since = "2.0.0")
        private boolean longTermMemoryAsyncRecord = false;

        @Deprecated(forRemoval = true, since = "2.0.0")
        private final Set<Knowledge> knowledgeBases = new LinkedHashSet<>();

        @Deprecated(forRemoval = true, since = "2.0.0")
        private RAGMode ragMode = RAGMode.GENERIC;

        @Deprecated(forRemoval = true, since = "2.0.0")
        private RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        @Deprecated(forRemoval = true, since = "2.0.0")
        private SkillBox skillBox;

        // ==================== 2.0 技能仓库入口 ====================
        // 2.0 挂载技能的方式：向智能体提供一个或多个
        // {@link AgentSkillRepository} 实例。{@link #build()} 安装
        // {@link DynamicSkillMiddleware}，在每次调用时重建技能提示，
        // 使按用户隔离的命名空间仓库能够在相同技能名称下切换内容。

        private final List<AgentSkillRepository> skillRepositories = new ArrayList<>();
        private SkillFilter skillFilter;
        private boolean dynamicSkillsEnabled = true;

        /**
         * 为 true 时，{@link DynamicSkillMiddleware} 打开提示提供方的代码执行块
         * （每个技能的 {@code <files-root>} 列表及说明）。默认关闭；
         * 未同时接入类 shell 工具时启用，会生成要求模型执行其无法完成操作的提示。
         */
        private boolean skillCodeExecutionEnabled = false;

        /**
         * 传给 {@link DynamicSkillMiddleware} 的稳定工作目录。{@code null} 表示中间件在首次重载时
         * 通过 mkdtemp 创建目录，并在智能体整个生命周期内复用。
         */
        private Path skillWorkDir;

        private Builder() {}

        /**
         * 复制配置，不共享可变注册集合。配置的模型、工具、中间件及存储实例仍由应用管理生命周期。
         * 共享之前应配置好源对象，不支持并发调用设置方法。
         */
        public synchronized Builder copy() {
            Builder copy = new Builder();
            copy.sessionHistoryMode = this.sessionHistoryMode;
            copy.sessionLogStore = this.sessionLogStore;
            copy.sessionLogAgentId = this.sessionLogAgentId;
            copy.name = this.name;
            copy.agentId = this.agentId;
            copy.description = this.description;
            copy.sysPrompt = this.sysPrompt;
            copy.model = this.model;
            copy.toolkit = this.toolkit;
            copy.maxIters = this.maxIters;
            copy.modelExecutionConfig = this.modelExecutionConfig;
            copy.toolExecutionConfig = this.toolExecutionConfig;
            copy.generateOptions = this.generateOptions;
            copy.validateStructuredOutput = this.validateStructuredOutput;
            copy.modelRequestPreparer = this.modelRequestPreparer;
            copy.actionObserver = this.actionObserver;
            copy.enableMetaTool = this.enableMetaTool;
            copy.taskListEnabled = this.taskListEnabled;
            copy.taskRequirementsEnabled = this.taskRequirementsEnabled;
            copy.toolExecutionContext = this.toolExecutionContext;
            copy.enablePendingToolRecovery = this.enablePendingToolRecovery;
            copy.permissionContext = this.permissionContext;
            copy.flatMaxRetries = this.flatMaxRetries;
            copy.flatFallbackModel = this.flatFallbackModel;
            copy.flatFailoverListener = this.flatFailoverListener;
            copy.flatStopOnReject = this.flatStopOnReject;
            copy.stateStore = this.stateStore;
            copy.conflictPolicy = this.conflictPolicy;
            copy.defaultSessionId = this.defaultSessionId;
            copy.longTermMemory = this.longTermMemory;
            copy.longTermMemoryMode = this.longTermMemoryMode;
            copy.longTermMemoryAsyncRecord = this.longTermMemoryAsyncRecord;
            copy.ragMode = this.ragMode;
            copy.retrieveConfig = this.retrieveConfig;
            copy.skillBox = this.skillBox;
            copy.skillFilter = this.skillFilter;
            copy.dynamicSkillsEnabled = this.dynamicSkillsEnabled;
            copy.skillCodeExecutionEnabled = this.skillCodeExecutionEnabled;
            copy.skillWorkDir = this.skillWorkDir;
            copy.hooks.addAll(this.hooks);
            copy.middlewares.addAll(this.middlewares);
            copy.knowledgeBases.addAll(this.knowledgeBases);
            copy.skillRepositories.addAll(this.skillRepositories);
            return copy;
        }

        /**
         * 设置智能体名称。
         *
         * @param name 智能体名称，不能为 null
         * @return 当前构建器实例，支持链式调用
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置智能体 ID（去掉首尾空白；null／空白时回退到生成 UUID）。
         * 格式契约见 {@link AgentBase#normalizeAgentId(String)}，在 {@code build()} 时验证；框架内部以此为键。
         *
         * <p>通常无需设置：除非需要外部已知的特定 ID，例如重启后保持状态、追踪、路由身份稳定，
         * 否则留空，由框架生成唯一随机 UUID。若显式设置，唯一性由你负责，框架不强制检查；
         * 必须始终保证活动智能体 ID 唯一，否则可能在状态存储、文件系统命名空间或消息路由中冲突。
         *
         * @param agentId 智能体 ID
         * @return 当前构建器实例，支持链式调用
         */
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * @deprecated 不再强制执行；会话级串行化负责处理并发。
         */
        @Deprecated
        public Builder checkRunning(boolean checkRunning) {
            return this;
        }

        /**
         * 设置智能体系统提示。
         *
         * @param sysPrompt 系统提示，可以为 null 或空
         * @return 当前构建器实例，支持链式调用
         */
        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        /**
         * 设置智能体语言模型。
         *
         * @param model 用于推理的语言模型，不能为 null
         * @return 当前构建器实例，支持链式调用
         */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * 通过 {@link ModelRegistry} 解析字符串 ID 来配置模型：可以是命名注册
         * （{@link ModelRegistry#register(String, Model)}），也可以是扩展提供方模式，
         * 例如 {@code openai:gpt-5.5}、{@code dashscope:qwen-max}、{@code anthropic:claude-sonnet-4-5}、
         * {@code gemini:gemini-2.0-flash} 或 {@code ollama:llama3}。
         * 自动创建时，扩展模块从各自标准环境变量读取 API 密钥。
         *
         * @param modelId 注册表 ID 或 {@code provider:model} 字符串
         * @return 当前构建器
         * @throws IllegalArgumentException 无法解析 ID 时抛出
         */
        public Builder model(String modelId) {
            this.model = ModelRegistry.resolve(modelId);
            return this;
        }

        /**
         * 设置包含智能体可用工具的工具集。
         *
         * <p>在构建时、注册钩子／元工具之前复制注册表；已有工具实例按引用共享，必须支持并发使用。
         *
         * @param toolkit 包含可用工具的工具集，不能为 null
         * @return 当前构建器实例，支持链式调用
         */
        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * 设置推理—行动最大迭代次数。
         *
         * @param maxIters 最大迭代次数，必须为正数
         * @return 当前构建器实例，支持链式调用
         */
        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        /**
         * 添加用于监测、拦截智能体执行事件的钩子。
         *
         * <p>钩子可以在推理、行动等阶段观察或修改事件。可添加多个钩子，按优先级顺序执行（数值小者优先）。
         *
         * @param hook 要添加的钩子，不能为 null
         * @return 当前构建器实例，支持链式调用
         * @see Hook
         * @see Hook#tools()
         */
        public Builder hook(Hook hook) {
            this.hooks.add(hook);
            return this;
        }

        /**
         * 添加多个用于监测、拦截智能体执行事件的钩子。
         *
         * <p>钩子可以在推理、行动等阶段观察或修改事件，全部按优先级顺序执行（数值小者优先）。
         *
         * @param hooks 要添加的钩子列表，不能为 null
         * @return 当前构建器实例，支持链式调用
         * @see Hook
         * @see Hook#tools()
         */
        public Builder hooks(List<Hook> hooks) {
            this.hooks.addAll(hooks);
            return this;
        }

        /**
         * 添加拦截智能体执行的中间件。
         *
         * @param middleware 要添加的中间件
         * @return 当前构建器实例，支持链式调用
         */
        public Builder middleware(MiddlewareBase middleware) {
            this.middlewares.add(middleware);
            return this;
        }

        /**
         * 添加多个拦截智能体执行的中间件。
         *
         * @param middlewares 要添加的中间件列表
         * @return 当前构建器实例，支持链式调用
         */
        public Builder middlewares(List<? extends MiddlewareBase> middlewares) {
            this.middlewares.addAll(middlewares);
            return this;
        }

        /**
         * 启用或禁用元工具功能。
         *
         * <p>启用后，工具集自动注册向智能体提供可用工具信息的元工具，
         * 帮助智能体了解可用工具，而不完全依赖系统提示。
         *
         * @param enableMetaTool true 表示启用，false 表示禁用
         * @return 当前构建器实例，支持链式调用
         */
        public Builder enableMetaTool(boolean enableMetaTool) {
            this.enableMetaTool = enableMetaTool;
            return this;
        }

        /**
         * 启用内置任务列表能力（{@code todo_write} 工具及每轮提醒）。
         *
         * <p>等价于 {@link #enableTaskList(boolean) enableTaskList(true)}。
         *
         * @return 当前构建器，支持链式调用
         */
        public Builder enableTaskList() {
            return enableTaskList(true);
        }

        /**
         * 启用或禁用内置任务列表能力。
         *
         * <p>启用后，{@link #build()} 注册 {@code todo_write} 工具
         * （对 {@link io.agentscope.core.state.AgentState#getTasksContext()} 采用完整列表替换语义），
         * 以及在每次推理之前重新呈现当前列表的 {@link io.agentscope.core.middleware.TaskReminderMiddleware}。默认关闭。
         *
         * @param enabled true 表示启用任务列表
         * @return 当前构建器，支持链式调用
         */
        public Builder enableTaskList(boolean enabled) {
            this.taskListEnabled = enabled;
            return this;
        }

        /**
         * 启用或禁用孤立待处理工具调用的自动恢复。
         *
         * <p>启用后，在处理新输入前自动检测孤立待处理调用并补上合成错误结果，
         * 避免工具执行失败、超时或中断时产生 {@link IllegalStateException}。
         *
         * <p>若希望通过 HITL（人在回路中）或自定义错误处理策略手动处理待处理调用，可禁用此功能。
         *
         * @param enable true 表示启用自动恢复，false 表示禁用
         * @return 当前构建器实例，支持链式调用
         */
        public Builder enablePendingToolRecovery(boolean enable) {
            this.enablePendingToolRecovery = enable;
            return this;
        }

        /**
         * 设置模型 API 调用的执行配置。
         *
         * <p>控制推理阶段模型请求的超时、重试及退避策略。未设置时使用模型默认执行配置。
         *
         * @param modelExecutionConfig 模型调用执行配置，可以为 null
         * @return 当前构建器实例，支持链式调用
         * @see ExecutionConfig
         */
        public Builder modelExecutionConfig(ExecutionConfig modelExecutionConfig) {
            this.modelExecutionConfig = modelExecutionConfig;
            return this;
        }

        /**
         * 设置工具执行配置。
         *
         * <p>控制行动阶段工具调用的超时、重试及退避策略。未设置时使用工具集默认执行配置。
         *
         * @param toolExecutionConfig 工具调用执行配置，可以为 null
         * @return 当前构建器实例，支持链式调用
         * @see ExecutionConfig
         */
        public Builder toolExecutionConfig(ExecutionConfig toolExecutionConfig) {
            this.toolExecutionConfig = toolExecutionConfig;
            return this;
        }

        /**
         * 设置模型 API 调用的生成选项。
         *
         * <p>控制 temperature、topP、maxTokens、frequencyPenalty、presencePenalty 等大模型生成参数，
         * 在推理阶段传给模型。
         *
         * <p><b>用法示例：</b>
         * <pre>{@code
         * ReActAgent agent = ReActAgent.builder()
         *     .name("assistant")
         *     .model(model)
         *     .generateOptions(GenerateOptions.builder()
         *         .temperature(0.7)
         *         .topP(0.9)
         *         .maxTokens(1000)
         *         .build())
         *     .build();
         * }</pre>
         *
         * <p><b>注意：</b>同时设置 generateOptions 与 modelExecutionConfig 时，
         * 后者的 executionConfig 会合并到 generateOptions；执行设置以 modelExecutionConfig 为优先。
         *
         * @param generateOptions 模型调用生成选项，可以为 null
         * @return 当前构建器实例，支持链式调用
         * @see GenerateOptions
         */
        public Builder generateOptions(GenerateOptions generateOptions) {
            this.generateOptions = generateOptions;
            return this;
        }

        /**
         * 控制模型返回后是否按请求的 JSON Schema 校验原生结构化输出响应。默认启用。
         * 禁用后，智能体仍要求合法 JSON，仍将解析载荷附加为结构化数据，但信任提供方保证 schema 一致性。
         *
         * @param validateStructuredOutput 是否校验原生结构化输出
         * @return 当前构建器实例，支持链式调用
         */
        public Builder validateStructuredOutput(boolean validateStructuredOutput) {
            this.validateStructuredOutput = validateStructuredOutput;
            return this;
        }

        /**
         * 设置智能体工具执行上下文。
         *
         * @param toolExecutionContext 工具执行上下文
         * @return 当前构建器实例，支持链式调用
         * @deprecated 请通过 {@code agent.call(msg, runtimeContext)} 使用 {@link RuntimeContext}，
         * 或直接通过 {@code RuntimeContext.builder().put(Type, value)} 注册 POJO。
         */
        @Deprecated
        public Builder toolExecutionContext(ToolExecutionContext toolExecutionContext) {
            this.toolExecutionContext = toolExecutionContext;
            return this;
        }

        /**
         * 设置支持自动加载 AgentState（构造时）及保存（每次成功 {@code call()} 后及优雅关闭时）的
         * {@link AgentStateStore}。为 {@code null} 时智能体仅在内存运行，持久化为空操作。
         */
        public Builder stateStore(AgentStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        /**
         * 设置 {@code agent_state} 乐观并发保存与其他写入者更新冲突时的策略。
         * 默认为 {@link ConflictPolicy#OVERWRITE}；启用分布式会话轮次门控时优先使用 {@link ConflictPolicy#FAIL}。
         */
        public Builder conflictPolicy(ConflictPolicy conflictPolicy) {
            this.conflictPolicy = conflictPolicy;
            return this;
        }

        /**
         * 设置构建器阶段的回退 {@code sessionId}，用于调用未在 {@link RuntimeContext} 提供 {@code sessionId} 时
         * 持久化 {@code agent_state}。未设置时默认为智能体名称。
         * 单次调用路由通过 {@code RuntimeContext.sessionId} 完成；这里只是启动／单租户默认值。
         */
        public Builder defaultSessionId(String sessionId) {
            this.defaultSessionId = sessionId;
            return this;
        }

        /**
         * 设置模型调用重试额度（包括首次尝试的最大尝试次数）。
         * 未设置时默认为 {@link ModelConfig#DEFAULT_MAX_RETRIES}。
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries <= 0) {
                throw new IllegalArgumentException("maxRetries must be > 0: " + maxRetries);
            }
            this.flatMaxRetries = maxRetries;
            return this;
        }

        /**
         * 设置主模型耗尽重试额度后调用的回退模型。传入 {@code null} 显式清除（不回退）。
         */
        public Builder fallbackModel(Model fallbackModel) {
            this.flatFallbackModel = fallbackModel;
            return this;
        }

        /**
         * 便捷重载，通过 {@link ModelRegistry#resolve(String)} 解析 {@code modelId}
         * （命名注册，或 {@code provider:model} 模式，例如 {@code openai:gpt-5.5}、{@code dashscope:qwen-max}）。
         *
         * @throws IllegalArgumentException 无法解析 ID 时抛出
         */
        public Builder fallbackModel(String modelId) {
            this.flatFallbackModel = ModelRegistry.resolve(modelId);
            return this;
        }

        /**
         * 设置失败主模型被回退模型接管时通知的监听器。传入 {@code null} 显式清除（不通知）。
         *
         * @see FailoverListener 线程及失败契约
         */
        public Builder failoverListener(FailoverListener failoverListener) {
            this.flatFailoverListener = failoverListener;
            return this;
        }

        /**
         * 控制任一工具调用被权限拒绝时是否终止推理循环，而不是将拒绝反馈到下一轮推理。
         * 默认为 {@link ReactConfig#DEFAULT_STOP_ON_REJECT}。
         */
        public Builder stopOnReject(boolean stopOnReject) {
            this.flatStopOnReject = stopOnReject;
            return this;
        }

        /**
         * 设置工具执行期间 {@link PermissionEngine} 查询的 {@link PermissionContextState}。
         * 未设置时使用空权限上下文（所有工具为 PASSTHROUGH）。
         */
        public Builder permissionContext(PermissionContextState permissionContext) {
            this.permissionContext = permissionContext;
            return this;
        }

        // ==================== 1.x 旧版兼容设置方法 ====================
        // 以下设置方法自 2.0 起弃用，将在下一次要版本移除。
        // 每个方法捕获一个值，稍后在 build() 中由 configureXxx() 使用，
        // 将相应旧版钩子和／或工具接入智能体，使 1.x 用户代码
        // 保持等价运行时行为。内部引用使用 legacy.* 包。

        /**
         * @deprecated 自 2.0.0 起弃用。长期记忆正围绕即将推出的 reme 基类重新设计，通过此路径添加的钩子仍然有效。
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder longTermMemory(LongTermMemory longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        /**
         * @deprecated 自 2.0.0 起弃用。参见 {@link #longTermMemory}。
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder longTermMemoryMode(LongTermMemoryMode mode) {
            this.longTermMemoryMode = mode;
            return this;
        }

        /**
         * @deprecated 自 2.0.0 起弃用。参见 {@link #longTermMemory}。
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder longTermMemoryAsyncRecord(boolean asyncRecord) {
            this.longTermMemoryAsyncRecord = asyncRecord;
            return this;
        }

        /**
         * @deprecated 自 2.0.0 起弃用。RAG 正在重新设计，旧适配器仍然有效。
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder knowledge(Knowledge knowledge) {
            if (knowledge != null) {
                this.knowledgeBases.add(knowledge);
            }
            return this;
        }

        /**
         * @deprecated 自 2.0.0 起弃用。参见 {@link #knowledge}。
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder knowledges(List<Knowledge> knowledges) {
            if (knowledges != null) {
                this.knowledgeBases.addAll(knowledges);
            }
            return this;
        }

        /**
         * @deprecated 自 2.0.0 起弃用。参见 {@link #knowledge}。
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder ragMode(RAGMode mode) {
            if (mode != null) {
                this.ragMode = mode;
            }
            return this;
        }

        /**
         * @deprecated 自 2.0.0 起弃用。参见 {@link #knowledge}。
         */
        @Deprecated(forRemoval = true, since = "2.0.0")
        public Builder retrieveConfig(RetrieveConfig config) {
            if (config != null) {
                this.retrieveConfig = config;
            }
            return this;
        }

        /**
         * @deprecated 自 2.0.0 起弃用。技能现在通过 {@link #skillRepository}／{@link #skillRepositories} 提供。
         * 为源码兼容仍接受旧 {@link io.agentscope.core.skill.SkillBox} 实例，但
         * {@code skillBox(...)} 与 {@code skillRepository(...)} 组合未经测试，新代码应优先使用
         * {@link #skillRepository(AgentSkillRepository)}。
         */
        @Deprecated(since = "2.0.0")
        public Builder skillBox(SkillBox skillBox) {
            this.skillBox = skillBox;
            return this;
        }

        /**
         * 向分层技能栈添加一个 {@link AgentSkillRepository}。多次调用按低到高优先级追加；
         * 两个仓库暴露相同 {@link io.agentscope.core.skill.AgentSkill#getName()} 的技能时，后者（高优先级）生效。
         *
         * <p>至少注册一个仓库且动态技能仍启用时（见 {@link #dynamicSkillsEnabled(boolean)}），
         * {@link #build()} 附加 {@link DynamicSkillMiddleware}，每次 {@code call()} 重建技能提示。
         */
        public Builder skillRepository(AgentSkillRepository repo) {
            if (repo != null) {
                this.skillRepositories.add(repo);
            }
            return this;
        }

        /**
         * 用提供的集合替换当前仓库列表，静默丢弃 {@code null} 条目；传入 {@code null} 则清空列表。
         */
        public Builder skillRepositories(List<AgentSkillRepository> repos) {
            this.skillRepositories.clear();
            if (repos != null) {
                for (AgentSkillRepository r : repos) {
                    if (r != null) {
                        this.skillRepositories.add(r);
                    }
                }
            }
            return this;
        }

        /**
         * 构建器阶段的 {@link SkillFilter} 存于构建后的智能体（见 {@code getSkillFilter()}），
         * 由自动安装的 {@link DynamicSkillMiddleware} 作为基准使用。未设置时默认为 {@link SkillFilter#all()}。
         */
        public Builder skillFilter(SkillFilter filter) {
            this.skillFilter = filter;
            return this;
        }

        /**
         * 控制是否自动安装 {@link DynamicSkillMiddleware}。外部编排器（例如 {@code HarnessAgent}）
         * 希望附加自己的 {@link DynamicSkillMiddleware} 子类，或回退到静态
         * {@link io.agentscope.core.skill.SkillBox} 时，设为 {@code false}。默认为 {@code true}。
         */
        public Builder dynamicSkillsEnabled(boolean enabled) {
            this.dynamicSkillsEnabled = enabled;
            return this;
        }

        /**
         * 启用 {@link io.agentscope.core.skill.AgentSkillPromptProvider} 发出的代码执行提示块。启用后：
         * <ul>
         * <li>所有可见技能都有磁盘来源目录时（例如由 {@link io.agentscope.core.skill.repository.FileSystemSkillRepository}
         * 生成），每个 {@code <skill>} 条目包含带绝对路径的 {@code <files-root>} 子项，使大模型可直接通过 shell 执行脚本。</li>
         * <li>否则提示回退到单个 {@code uploadDir} 根目录。</li>
         * </ul>
         *
         * <p>仅在智能体工具集已接入类 shell 工具时启用，否则提示会要求模型执行无法完成的操作。默认为 {@code false}。
         */
        public Builder skillCodeExecutionEnabled(boolean enabled) {
            this.skillCodeExecutionEnabled = enabled;
            return this;
        }

        /**
         * 设置技能实体化的稳定工作目录，存于构建后的智能体（见 {@code getSkillWorkDir()}），
         * 整个生命周期由调用方管理。为 {@code null}（默认）时，使用方在框架分配的匿名临时根目录下实体化；
         * 框架不会删除该目录。
         */
        public Builder skillWorkDir(Path dir) {
            this.skillWorkDir = dir;
            return this;
        }

        /**
         * 返回新的 {@link Builder}，预填给定智能体可观测的配置：名称、描述、系统提示、模型、
         * maxIters、generateOptions，以及工具集防御性副本。
         *
         * <p>用于派生相关智能体，无需重新指定每个字段。
         */
        public static Builder fromAgent(ReActAgent agent) {
            Builder b = new Builder();
            b.name = agent.getName();
            b.description = agent.getDescription();
            b.sysPrompt = agent.getSysPrompt();
            b.model = agent.getModel();
            b.maxIters = agent.getMaxIters();
            b.generateOptions = agent.getGenerateOptions();
            ModelConfig srcModelConfig = agent.getModelConfig();
            if (srcModelConfig != null) {
                b.flatMaxRetries = srcModelConfig.maxRetries();
                b.flatFallbackModel = srcModelConfig.fallbackModel();
                b.flatFailoverListener = srcModelConfig.failoverListener();
            }
            b.toolkit = agent.getToolkit().copy();
            return b;
        }

        // ==================== 1.x 旧版 configureXxx 辅助方法 ====================
        // 从 1.x ReActAgent.Builder（origin/1.x ReActAgent.java:1762-1925）原样移植，
        // 为 2.0 做了两项调整：
        // 1）所有旧类通过 io.agentscope.core.legacy.* 包引用；
        // 2）configureLongTermMemory 的静态钩子接收 AgentStateMemoryView，
        // 通过与 build() 共享的 `selfRef` 惰性读取 AgentState.context。

        /**
         * 按所选模式配置长期记忆。
         *
         * <p>AGENT_CONTROL 注册供智能体调用的记忆工具；STATIC_CONTROL 添加自动检索／记录记忆的
         * {@link StaticLongTermMemoryHook}；BOTH 结合两者。
         * 钩子从 {@code selfRef.get().getAgentState()} 惰性读取上下文，因此允许它先于智能体本身构造。
         */
        @SuppressWarnings("deprecation")
        private void configureLongTermMemory(
                Toolkit agentToolkit, AtomicReference<ReActAgent> selfRef) {
            if (longTermMemoryMode == LongTermMemoryMode.AGENT_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                agentToolkit.registerTool(new LongTermMemoryTools(longTermMemory));
            }
            if (longTermMemoryMode == LongTermMemoryMode.STATIC_CONTROL
                    || longTermMemoryMode == LongTermMemoryMode.BOTH) {
                Memory contextView =
                        new AgentStateMemoryView(
                                () -> {
                                    ReActAgent a = selfRef.get();
                                    return a == null ? null : a.getAgentState();
                                });
                hooks.add(
                        new StaticLongTermMemoryHook(
                                longTermMemory, contextView, longTermMemoryAsyncRecord));
            }
        }

        /**
         * 按所选模式配置 RAG（检索增强生成）。
         */
        @SuppressWarnings("deprecation")
        private void configureRAG(Toolkit agentToolkit) {
            Knowledge aggregatedKnowledge =
                    knowledgeBases.size() == 1
                            ? knowledgeBases.iterator().next()
                            : buildAggregatedKnowledge();

            switch (ragMode) {
                case GENERIC -> hooks.add(new GenericRAGHook(aggregatedKnowledge, retrieveConfig));
                case AGENTIC ->
                        agentToolkit.registerTool(
                                new KnowledgeRetrievalTools(aggregatedKnowledge, retrieveConfig));
                case NONE -> {
                    // 有意保持空操作
                }
            }
        }

        @SuppressWarnings("deprecation")
        private Knowledge buildAggregatedKnowledge() {
            return new Knowledge() {
                @Override
                public Mono<Void> addDocuments(List<Document> documents) {
                    return reactor.core.publisher.Flux.fromIterable(knowledgeBases)
                            .flatMap(kb -> kb.addDocuments(documents))
                            .then();
                }

                @Override
                public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                    return reactor.core.publisher.Flux.fromIterable(knowledgeBases)
                            .flatMap(kb -> kb.retrieve(query, config))
                            .collectList()
                            .map(this::mergeAndSortResults);
                }

                private List<Document> mergeAndSortResults(List<List<Document>> allResults) {
                    return allResults.stream()
                            .flatMap(List::stream)
                            .collect(
                                    Collectors.toMap(
                                            Document::getId,
                                            d -> d,
                                            (d1, d2) ->
                                                    d1.getScore() != null
                                                                    && d2.getScore() != null
                                                                    && d1.getScore() > d2.getScore()
                                                            ? d1
                                                            : d2))
                            .values()
                            .stream()
                            .sorted(
                                    Comparator.comparing(
                                            Document::getScore,
                                            Comparator.nullsLast(Comparator.reverseOrder())))
                            .limit(retrieveConfig.getLimit())
                            .toList();
                }
            };
        }

        /**
         * 注册内置任务列表工具（{@code todo_write}）及每轮提醒中间件。
         * 通过 {@link #enableTaskList()} 显式启用。
         */
        private void configureTodoTools(Toolkit agentToolkit) {
            if (taskListEnabled) agentToolkit.registerTool(new TodoTools());
            if (taskRequirementsEnabled) agentToolkit.registerTool(new RequirementTools());
            middlewares.add(new TaskReminderMiddleware(taskListEnabled, taskRequirementsEnabled));
        }

        /**
         * 配置 SkillBox：绑定工具集、注册技能加载工具、自动上传启用时上传技能文件，并将 SkillHook 加入链。
         */
        @SuppressWarnings("deprecation")
        private Hook configureSkillBox(Toolkit agentToolkit) {
            SkillBox skillBox = this.skillBox.copyForToolkit(agentToolkit);
            skillBox.registerSkillLoadTool();
            if (skillBox.isAutoUploadSkill()) {
                skillBox.uploadSkillFiles();
            }
            return new SkillHook(skillBox);
        }

        /**
         * 按配置构建并返回新的 ReActAgent 实例。
         *
         * @return 新的 ReActAgent 实例
         * @throws IllegalArgumentException 必填参数缺失或无效时抛出
         */
        public ReActAgent build() {
            return copy().buildInstance();
        }

        private ReActAgent buildInstance() {
            // 隔离此智能体新增的注册项，同时共享已有工具实例。
            // 请求特定可见性在执行时组合，无需复制。
            Toolkit agentToolkit = this.toolkit.copy();

            registerToolsFromHooks(agentToolkit);

            if (enableMetaTool) {
                agentToolkit.registerMetaTool();
            }

            // 1.x 兼容：共享 selfRef 让早于智能体构造的长期记忆钩子
            // 能在智能体存在后惰性解析 AgentState.context。
            AtomicReference<ReActAgent> selfRef = new AtomicReference<>();

            if (longTermMemory != null) {
                configureLongTermMemory(agentToolkit, selfRef);
            }
            if (!knowledgeBases.isEmpty()) {
                configureRAG(agentToolkit);
            }
            if (taskListEnabled || taskRequirementsEnabled) {
                configureTodoTools(agentToolkit);
            }
            Hook skillHook = skillBox != null ? configureSkillBox(agentToolkit) : null;
            if (!skillRepositories.isEmpty() && dynamicSkillsEnabled) {
                middlewares.add(
                        new DynamicSkillMiddleware(
                                List.copyOf(skillRepositories), skillCodeExecutionEnabled));
            }

            // List.sort 是稳定排序：order 相等的中间件保持注册顺序。
            middlewares.sort(Comparator.comparingInt(MiddlewareBase::order).reversed());

            if (skillHook != null) {
                hooks.add(skillHook);
            }
            try {
                ReActAgent agent = new ReActAgent(this, agentToolkit);
                selfRef.set(agent);
                return agent;
            } finally {
                // 生成的钩子只属于此智能体，不属于后续构建器使用。
                if (skillHook != null) {
                    hooks.remove(skillHook);
                }
            }
        }

        /**
         * 将钩子声明的工具对象（{@link Hook#tools()}）注册到智能体工具集。
         *
         * <p>智能体拥有构建时的注册表副本，钩子提供的注册项不会覆盖从同一源工具集构建的其他智能体的工具。
         */
        private void registerToolsFromHooks(Toolkit agentToolkit) {
            for (Hook hook : hooks) {
                List<Object> toolObjects = hook.tools();
                if (toolObjects == null || toolObjects.isEmpty()) {
                    continue;
                }
                for (Object toolObject : toolObjects) {
                    if (toolObject != null) {
                        agentToolkit.registerTool(toolObject);
                    }
                }
            }
        }
    }
}
