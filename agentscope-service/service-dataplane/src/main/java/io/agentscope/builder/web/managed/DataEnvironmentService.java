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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.control.ControlPlaneClient;
import org.springframework.stereotype.Service;

/**
 * 数据平面对环境模板的读取访问。环境行位于控制平面模式中，本服务通过 {@link ControlPlaneClient} 获取。
 */
@Service
public class DataEnvironmentService {

    private final ControlPlaneClient controlPlaneClient;

    public DataEnvironmentService(ControlPlaneClient controlPlaneClient) {
        this.controlPlaneClient = controlPlaneClient;
    }

    /**
     * 返回环境，不存在时返回 404。
     */
    public EnvironmentDto requireById(String environmentId) {
        return controlPlaneClient.getEnvironment(environmentId);
    }

    /**
     * 返回用于构建轮次的环境。控制平面在创建会话时已经验证访问，因此这里不重复进行每次调用的 ACL 检查。
     */
    public EnvironmentDto get(String ownerId, String environmentId) {
        return controlPlaneClient.getEnvironment(environmentId, ownerId);
    }
}
