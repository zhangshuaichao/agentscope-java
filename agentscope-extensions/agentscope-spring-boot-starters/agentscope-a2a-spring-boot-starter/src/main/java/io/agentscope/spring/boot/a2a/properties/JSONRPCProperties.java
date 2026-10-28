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
package io.agentscope.spring.boot.a2a.properties;

import io.agentscope.core.a2a.server.transport.CustomTransportProperties;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** JSON-RPC 传输的配置属性。 */
@ConfigurationProperties(Constants.A2A_JSON_RPC_SERVER_PREFIX)
public class JSONRPCProperties implements CustomTransportProperties {
    /** 是否启用 JSON-RPC 传输。 */
    private boolean enabled = true;

    /** 部署配置，包括主机与端口。 */
    private DeploymentProperties deploymentProperties;

    /**
     * 转换为 {@link TransportProperties}。
     *
     * @return 传输属性
     */
    @Override
    public TransportProperties toTransportProperties() {
        return TransportProperties.builder(TransportProtocol.JSONRPC.asString())
                .host(deploymentProperties.host())
                .port(deploymentProperties.port())
                .path(deploymentProperties.path())
                .build();
    }

    @Override
    public void setDeploymentProperties(DeploymentProperties deploymentProperties) {
        this.deploymentProperties = deploymentProperties;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
