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
package io.agentscope.extensions.controlplane;

/**
 * {@link SessionBridge} 的连接与上报设置。
 *
 * <p>独立自带应用模式（BYO，本地推荐）：设置 {@code controlPlaneHttp} 和 {@code internalToken}，保持 {@code startGrpc=false}。
 * 桥接器提供 {@code /agentscope/*}，并通过 {@code POST /api/v1/agent-registrations} 自注册。
 *
 * <p>可选 ASDP gRPC：设置 {@code controlPlane}（{@code host:port}）和 {@code startGrpc=true}。
 *
 * <p>【学习提示】EventJournal 当前实际默认目录为用户主目录下 .agentscope/controlplane/event-journal；不要仅按旧参数说明寻找恢复文件。
 *
 * @param controlPlane service-controlplane ASDP gRPC 端点，{@code host:port}，可选
 * @param controlPlaneHttp 用于 HTTP 自注册的 service-controlplane REST 基础 URL，例如 {@code http://localhost:8081}
 * @param internalToken 首次注册使用的可信引导／工作负载令牌
 * @param registrationCredential 已有逻辑身份的智能体注册凭据
 * @param agentId 带外注册时的稳定 Catalog Agent UUID
 * @param bindingId 带外注册时的稳定外部 Binding UUID
 * @param generation 带外注册时已注册的 AgentInstance 代次
 * @param agentKey 在控制平面注册的稳定逻辑键
 * @param tenant 协作租户
 * @param namespace 协作／Kubernetes 命名空间
 * @param instanceKey 当前副本的稳定键，优先默认使用 {@code HOSTNAME}，其次本地主机名
 * @param enableEvents 是否持久化并推送二级事件流；启用 ASDP gRPC 时默认开启，因为它是重连与重放所用的权威对话历史
 * @param eventJournalDir 持久事件发件箱目录；上游旧说明为 {@code ~/.agentscope/service-controlplane/event-journal}，实际默认值见上方提示
 * @param contractHttpPort 进程内 {@code /agentscope/*} 契约服务端口；{@code 0} 绑定临时端口
 * @param contractHttpHost 绑定地址，空值表示所有接口
 * @param publicBaseUrl 控制平面访问契约服务器使用的 URL；空值推导为 {@code http://localhost:<boundPort>}
 * @param sessionAffinity 控制平面路由会话命令时使用的亲和性提示
 * @param startHttp 是否启动契约服务器
 * @param startGrpc 是否打开 ASDP 上行通道
 * @param startHttpRegister 是否 POST /api/v1/agent-registrations
 * */
public record ControlPlaneConfig(
        String controlPlane,
        String controlPlaneHttp,
        String internalToken,
        String registrationCredential,
        String agentId,
        String bindingId,
        long generation,
        String agentKey,
        String tenant,
        String namespace,
        String instanceKey,
        boolean enableEvents,
        String eventJournalDir,
        int contractHttpPort,
        String contractHttpHost,
        String publicBaseUrl,
        String sessionAffinity,
        boolean startHttp,
        boolean startGrpc,
        boolean startHttpRegister) {

    public ControlPlaneConfig {
        if (agentKey == null || agentKey.isBlank()) {
            throw new IllegalArgumentException("agentKey is required");
        }
        tenant = (tenant == null || tenant.isBlank()) ? "default" : tenant;
        namespace = (namespace == null || namespace.isBlank()) ? "default" : namespace;
        instanceKey =
                (instanceKey == null || instanceKey.isBlank()) ? defaultInstanceId() : instanceKey;
        controlPlane = controlPlane == null ? "" : controlPlane;
        controlPlaneHttp = controlPlaneHttp == null ? "" : controlPlaneHttp.trim();
        internalToken = internalToken == null ? "" : internalToken;
        registrationCredential =
                registrationCredential == null ? "" : registrationCredential.trim();
        agentId = agentId == null ? "" : agentId.trim();
        bindingId = bindingId == null ? "" : bindingId.trim();
        eventJournalDir = eventJournalDir == null ? "" : eventJournalDir.trim();
        contractHttpHost = contractHttpHost == null ? "" : contractHttpHost;
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        sessionAffinity = sessionAffinity == null ? "" : sessionAffinity;
        if (startGrpc && controlPlane.isBlank()) {
            throw new IllegalArgumentException("controlPlane is required when gRPC is enabled");
        }
        if (startGrpc
                && !startHttpRegister
                && (agentId.isBlank() || bindingId.isBlank() || generation <= 0)) {
            throw new IllegalArgumentException(
                    "agentId, bindingId, and generation are required for gRPC without"
                            + " registration");
        }
        if (startHttpRegister && controlPlaneHttp.isBlank()) {
            throw new IllegalArgumentException(
                    "controlPlaneHttp is required when HTTP self-register is enabled");
        }
    }

    public static Builder builder(String agentKey) {
        return new Builder(agentKey);
    }

    private static String defaultInstanceId() {
        String fromEnv = System.getenv("HOSTNAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown";
        }
    }

    /**
 * {@link ControlPlaneConfig} 的可变构建器。
 */
    public static final class Builder {
        private final String agentKey;
        private String controlPlane = "";
        private String controlPlaneHttp = "";
        private String internalToken = "";
        private String registrationCredential = "";
        private String agentId = "";
        private String bindingId = "";
        private long generation;
        private String tenant = "default";
        private String namespace = "default";
        private String instanceKey = "";
        private Boolean enableEvents;
        private String eventJournalDir = "";
        private int contractHttpPort = 18090;
        private String contractHttpHost = "";
        private String publicBaseUrl = "";
        private String sessionAffinity = "";
        private boolean startHttp = true;
        private boolean startGrpc = false;
        private Boolean startHttpRegister;

        private Builder(String agentKey) {
            this.agentKey = agentKey;
        }

        public Builder controlPlane(String controlPlane) {
            this.controlPlane = controlPlane;
            return this;
        }

        /**
 * 用于 {@code /api/v1/dataplanes/*} 自注册的 service-controlplane REST 基础 URL。
 */
        public Builder controlPlaneHttp(String controlPlaneHttp) {
            this.controlPlaneHttp = controlPlaneHttp;
            return this;
        }

        public Builder internalToken(String internalToken) {
            this.internalToken = internalToken;
            return this;
        }

        public Builder registrationCredential(String registrationCredential) {
            this.registrationCredential = registrationCredential;
            return this;
        }

        public Builder registeredIdentity(String agentId, String bindingId, long generation) {
            this.agentId = agentId;
            this.bindingId = bindingId;
            this.generation = generation;
            return this;
        }

        public Builder tenant(String tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder instanceKey(String instanceKey) {
            this.instanceKey = instanceKey;
            return this;
        }

        public Builder enableEvents(boolean enableEvents) {
            this.enableEvents = enableEvents;
            return this;
        }

        /**
 * 持久化且带确认机制的二级事件发件箱目录。
 */
        public Builder eventJournalDir(String eventJournalDir) {
            this.eventJournalDir = eventJournalDir;
            return this;
        }

        public Builder contractHttpPort(int contractHttpPort) {
            this.contractHttpPort = contractHttpPort;
            return this;
        }

        public Builder contractHttpHost(String contractHttpHost) {
            this.contractHttpHost = contractHttpHost;
            return this;
        }

        public Builder publicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
            return this;
        }

        public Builder sessionAffinity(String sessionAffinity) {
            this.sessionAffinity = sessionAffinity;
            return this;
        }

        public Builder startHttp(boolean startHttp) {
            this.startHttp = startHttp;
            return this;
        }

        public Builder startGrpc(boolean startGrpc) {
            this.startGrpc = startGrpc;
            return this;
        }

        public Builder startHttpRegister(boolean startHttpRegister) {
            this.startHttpRegister = startHttpRegister;
            return this;
        }

        public ControlPlaneConfig build() {
            boolean register =
                    startHttpRegister != null
                            ? startHttpRegister
                            : controlPlaneHttp != null && !controlPlaneHttp.isBlank();
            return new ControlPlaneConfig(
                    controlPlane,
                    controlPlaneHttp,
                    internalToken,
                    registrationCredential,
                    agentId,
                    bindingId,
                    generation,
                    agentKey,
                    tenant,
                    namespace,
                    instanceKey,
                    enableEvents != null ? enableEvents : startGrpc,
                    eventJournalDir,
                    contractHttpPort,
                    contractHttpHost,
                    publicBaseUrl,
                    sessionAffinity,
                    startHttp,
                    startGrpc,
                    register);
        }
    }
}
