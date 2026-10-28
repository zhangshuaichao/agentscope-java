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
package io.agentscope.extensions.controlplane.transport;

import io.agentscope.controlplane.proto.AgentDataPlaneServiceGrpc;
import io.agentscope.controlplane.proto.ConnectRequest;
import io.agentscope.controlplane.proto.ContextReport;
import io.agentscope.controlplane.proto.ConversationTurnCommand;
import io.agentscope.controlplane.proto.ConversationTurnReport;
import io.agentscope.controlplane.proto.Downstream;
import io.agentscope.controlplane.proto.EventReport;
import io.agentscope.controlplane.proto.EventReportAck;
import io.agentscope.controlplane.proto.ExecutionAttemptCommand;
import io.agentscope.controlplane.proto.ExecutionAttemptReport;
import io.agentscope.controlplane.proto.Heartbeat;
import io.agentscope.controlplane.proto.InventoryReport;
import io.agentscope.controlplane.proto.SessionEventMsg;
import io.agentscope.controlplane.proto.SessionReport;
import io.agentscope.controlplane.proto.SessionSnapshot;
import io.agentscope.controlplane.proto.Upstream;
import io.agentscope.controlplane.proto.UpstreamMeta;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ASDP 上行通道：在单条双向 gRPC 流中多路复用所有报告类型，与 Go 控制平面及 Python SDK 采用相同结构。
 *
 * <p>使用带上限的指数退避重连。桥接器拥有持久事件发件箱，仅收到 {@link EventReportAck} 后才推进；非事件遥测仍为尽力而为。
 */
public final class GrpcTransport implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(GrpcTransport.class.getName());

    private static final long RECONNECT_BASE_MS = 1_000L;
    private static final long RECONNECT_MAX_MS = 30_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    /**
 * 接收控制平面下推的 {@code SessionCommand}。
 */
    @FunctionalInterface
    public interface SessionCommandHandler {
        void onCommand(String sessionId, String command, byte[] params);
    }

    /**
 * 接收控制平面带隔离令牌的执行尝试命令。
 */
    @FunctionalInterface
    public interface ExecutionAttemptCommandHandler {
        void onExecutionAttempt(ExecutionAttemptCommand command);
    }

    /**
 * 接收控制平面带隔离令牌的在线对话轮次。
 */
    @FunctionalInterface
    public interface ConversationTurnCommandHandler {
        void onConversationTurn(ConversationTurnCommand command);
    }

    /**
 * 接收二级事件报告的持久提交确认。
 */
    @FunctionalInterface
    public interface EventAckHandler {
        void onAck(EventReportAck ack);
    }

    private final String target;
    private volatile String credential;
    private volatile String agentId;
    private final String agentKey;
    private volatile String bindingId;
    private final String tenant;
    private final String namespace;
    private volatile String instanceKey;
    private volatile long generation;
    private final String runtime;
    private final String sdkVersion;
    private final List<String> capabilities;
    private final String sessionAffinity;

    private final AtomicReference<StreamObserver<Upstream>> stream = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "controlplane-asdp");
                        t.setDaemon(true);
                        return t;
                    });

    private volatile ManagedChannel channel;
    private volatile SessionCommandHandler commandHandler;
    private volatile ExecutionAttemptCommandHandler executionAttemptHandler;
    private volatile ConversationTurnCommandHandler conversationTurnHandler;
    private volatile EventAckHandler eventAckHandler;

    public GrpcTransport(
            String target,
            String credential,
            String agentId,
            String agentKey,
            String bindingId,
            String tenant,
            String namespace,
            String instanceKey,
            long generation,
            String runtime,
            String sdkVersion,
            Collection<String> capabilities,
            String sessionAffinity) {
        this.target = target;
        this.credential = credential == null ? "" : credential;
        this.agentId = agentId;
        this.agentKey = agentKey;
        this.bindingId = bindingId;
        this.tenant = tenant;
        this.namespace = namespace;
        this.instanceKey = instanceKey;
        this.generation = generation;
        this.runtime = runtime;
        this.sdkVersion = sdkVersion;
        this.capabilities = List.copyOf(capabilities);
        this.sessionAffinity = sessionAffinity == null ? "" : sessionAffinity;
    }

    public void setSessionCommandHandler(SessionCommandHandler handler) {
        this.commandHandler = handler;
    }

    public void setExecutionAttemptHandler(ExecutionAttemptCommandHandler handler) {
        this.executionAttemptHandler = handler;
    }

    public void setConversationTurnHandler(ConversationTurnCommandHandler handler) {
        this.conversationTurnHandler = handler;
    }

    public void setEventAckHandler(EventAckHandler handler) {
        this.eventAckHandler = handler;
    }

    public boolean isConnected() {
        return connected.get();
    }

    /**
     * 刷新下次连接尝试使用的带隔离令牌 Catalog 身份。控制平面故障后传输重连期间，HTTP 自注册可能推进实例代次；若这些值不可更新，后续握手会一直过期，直至整个智能体进程重启。
     */
    public void updateIdentity(
            String credential,
            String agentId,
            String bindingId,
            String instanceKey,
            long generation) {
        if (agentId == null
                || agentId.isBlank()
                || bindingId == null
                || bindingId.isBlank()
                || instanceKey == null
                || instanceKey.isBlank()
                || generation <= 0) {
            return;
        }
        this.credential = credential == null ? "" : credential;
        this.agentId = agentId;
        this.bindingId = bindingId;
        this.instanceKey = instanceKey;
        this.generation = generation;
    }

    public void start() {
        if (stopped.get()) {
            throw new IllegalStateException("transport already stopped");
        }
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        connect();
        scheduler.scheduleWithFixedDelay(
                this::sendHeartbeat,
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void connect() {
        if (stopped.get()) {
            return;
        }
        try {
            AgentDataPlaneServiceGrpc.AgentDataPlaneServiceStub stub =
                    AgentDataPlaneServiceGrpc.newStub(channel);
            if (!credential.isBlank()) {
                Metadata metadata = new Metadata();
                metadata.put(
                        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer " + credential);
                stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
            }
            StreamObserver<Upstream> requests = stub.connect(new DownstreamObserver());
            stream.set(requests);
            requests.onNext(
                    Upstream.newBuilder()
                            .setMeta(meta())
                            .setConnect(
                                    ConnectRequest.newBuilder()
                                            .setRuntime(runtime)
                                            .setSdkVersion(sdkVersion)
                                            .addAllCapabilities(capabilities)
                                            .setSessionAffinity(sessionAffinity)
                                            .build())
                            .build());
            connected.set(true);
            reconnectAttempts.set(0);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "controlplane: ASDP connect failed", e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        connected.set(false);
        stream.set(null);
        if (stopped.get()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        long delay = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * (1L << Math.min(attempt, 5)));
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private UpstreamMeta meta() {
        return UpstreamMeta.newBuilder()
                .setAgentId(agentId)
                .setAgentKey(agentKey)
                .setBindingId(bindingId)
                .setTenant(tenant)
                .setInstanceKey(instanceKey)
                .setGeneration(generation)
                .setNamespace(namespace)
                .setTimestamp(System.currentTimeMillis())
                .build();
    }

    // ─── 上报 ───

    public void reportSessions(List<SessionSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        send(
                Upstream.newBuilder()
                        .setMeta(meta())
                        .setSessionReport(
                                SessionReport.newBuilder().addAllSessions(snapshots).build())
                        .build());
    }

    public boolean reportEvents(String reportId, List<SessionEventMsg> events) {
        if (events.isEmpty()) {
            return true;
        }
        return send(
                Upstream.newBuilder()
                        .setMeta(meta())
                        .setEventReport(
                                EventReport.newBuilder()
                                        .setReportId(reportId)
                                        .addAllEvents(events)
                                        .build())
                        .build());
    }

    public void reportContext(ContextReport report) {
        send(Upstream.newBuilder().setMeta(meta()).setContextReport(report).build());
    }

    public void reportInventory(InventoryReport report) {
        send(Upstream.newBuilder().setMeta(meta()).setInventory(report).build());
    }

    public void reportExecutionAttempt(ExecutionAttemptReport report) {
        send(Upstream.newBuilder().setMeta(meta()).setExecutionAttempt(report).build());
    }

    public void reportConversationTurn(ConversationTurnReport report) {
        send(Upstream.newBuilder().setMeta(meta()).setConversationTurn(report).build());
    }

    private void sendHeartbeat() {
        if (!connected.get()) {
            return;
        }
        send(
                Upstream.newBuilder()
                        .setMeta(meta())
                        .setHeartbeat(
                                Heartbeat.newBuilder()
                                        .setTimestamp(System.currentTimeMillis())
                                        .build())
                        .build());
    }

    private boolean send(Upstream message) {
        StreamObserver<Upstream> observer = stream.get();
        if (observer == null || !connected.get()) {
            return false;
        }
        try {
            synchronized (this) {
                observer.onNext(message);
            }
            return true;
        } catch (RuntimeException e) {
            // 桥接器保留待确认事件报告，并在重连后重试。
            LOG.log(Level.FINE, "controlplane: ASDP send failed", e);
            scheduleReconnect();
            return false;
        }
    }

    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        connected.set(false);
        StreamObserver<Upstream> observer = stream.getAndSet(null);
        if (observer != null) {
            try {
                observer.onCompleted();
            } catch (RuntimeException ignored) {
                // 连接已损坏，关闭时无需再做其他处理。
            }
        }
        scheduler.shutdownNow();
        ManagedChannel ch = channel;
        if (ch != null) {
            ch.shutdown();
            try {
                ch.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final class DownstreamObserver implements StreamObserver<Downstream> {

        @Override
        public void onNext(Downstream message) {
            if (message.hasSessionCmd()) {
                SessionCommandHandler handler = commandHandler;
                if (handler == null) {
                    return;
                }
                try {
                    handler.onCommand(
                            message.getSessionCmd().getSessionId(),
                            message.getSessionCmd().getCommand(),
                            message.getSessionCmd().getParams().toByteArray());
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "controlplane: session command handler failed", e);
                }
            } else if (message.hasExecutionAttempt()) {
                ExecutionAttemptCommandHandler handler = executionAttemptHandler;
                if (handler == null) {
                    return;
                }
                try {
                    handler.onExecutionAttempt(message.getExecutionAttempt());
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "controlplane: ExecutionAttempt command handler failed", e);
                }
            } else if (message.hasConversationTurn()) {
                ConversationTurnCommandHandler handler = conversationTurnHandler;
                if (handler == null) {
                    return;
                }
                try {
                    handler.onConversationTurn(message.getConversationTurn());
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "controlplane: conversation turn handler failed", e);
                }
            } else if (message.hasEventAck()) {
                EventAckHandler handler = eventAckHandler;
                if (handler == null) {
                    return;
                }
                try {
                    handler.onAck(message.getEventAck());
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "controlplane: event acknowledgement handler failed", e);
                }
            } else if (message.hasConnectAck() && !message.getConnectAck().getAccepted()) {
                LOG.log(
                        Level.WARNING,
                        "controlplane: control plane rejected connect: {0}",
                        message.getConnectAck().getRejectReason());
            }
        }

        @Override
        public void onError(Throwable t) {
            LOG.log(Level.FINE, "controlplane: ASDP stream error", t);
            scheduleReconnect();
        }

        @Override
        public void onCompleted() {
            scheduleReconnect();
        }
    }
}
