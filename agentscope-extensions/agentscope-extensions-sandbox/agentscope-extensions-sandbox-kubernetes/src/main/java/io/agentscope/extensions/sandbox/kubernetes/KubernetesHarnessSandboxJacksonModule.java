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
package io.agentscope.extensions.sandbox.kubernetes;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * 为 Kubernetes 沙箱存储注册 Jackson {@link com.fasterxml.jackson.databind.jsontype.NamedType} 条目。混合使用 Docker 与 Kubernetes 沙箱时，应在同一个 {@link com.fasterxml.jackson.databind.ObjectMapper} 上结合 {@link io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule} 使用。
 */
public final class KubernetesHarnessSandboxJacksonModule extends SimpleModule {

    public KubernetesHarnessSandboxJacksonModule() {
        super("harness-sandbox-kubernetes");
        registerSubtypes(new NamedType(KubernetesSandboxState.class, "kubernetes"));
    }
}
