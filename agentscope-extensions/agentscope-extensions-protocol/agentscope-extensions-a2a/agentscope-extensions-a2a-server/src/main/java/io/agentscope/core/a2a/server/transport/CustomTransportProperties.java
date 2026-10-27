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
package io.agentscope.core.a2a.server.transport;

/**
 * 可转换为标准 TransportProperties 的自定义传输属性接口。
 */
public interface CustomTransportProperties {
    /**
     * 将此自定义属性对象转换为标准 TransportProperties。
     *
     * @return 此自定义属性的 TransportProperties 表示
     */
    TransportProperties toTransportProperties();

    /**
     * 设置部署属性。
     * @param deploymentProperties 部署属性
     */
    void setDeploymentProperties(DeploymentProperties deploymentProperties);

    /**
     * 此传输是否启用。
     * @return 启用时为 true，否则为 false
     */
    boolean isEnabled();
}
