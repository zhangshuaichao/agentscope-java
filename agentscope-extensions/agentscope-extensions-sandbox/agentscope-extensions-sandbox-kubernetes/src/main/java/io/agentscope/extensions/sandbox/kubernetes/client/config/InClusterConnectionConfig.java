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
package io.agentscope.extensions.sandbox.kubernetes.client.config;

import io.agentscope.extensions.sandbox.kubernetes.client.Constants;

/**
 * 绕过路由器，在集群内直连沙箱 Pod。
 *
 * <p>优先使用 Sandbox 状态中的 Pod IP；回退为 {@code http://{sandboxId}.{namespace}.svc.cluster.local:{serverPort}}。
 */
public final class InClusterConnectionConfig implements SandboxConnectionConfig {

    private final int serverPort;

    public InClusterConnectionConfig() {
        this(Constants.DEFAULT_SERVER_PORT);
    }

    public InClusterConnectionConfig(int serverPort) {
        this.serverPort = serverPort > 0 ? serverPort : Constants.DEFAULT_SERVER_PORT;
    }

    @Override
    public int serverPort() {
        return serverPort;
    }
}
