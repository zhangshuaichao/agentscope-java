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
package io.agentscope.harness.agent.artifact;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 将智能体生成的产物（通常位于沙箱内）交付到智能体文件系统之外的目标的 SPI，
 * 例如宿主机、项目产物库、对象存储或 WebDAV。
 *
 * <p>框架保持业务无关：通用 {@code deliver_artifact} 工具从文件系统下载文件字节，
 * 再把实际传输委派给应用实现并配置的 {@code ArtifactDeliveryTarget}。
 *
 * <p>通过 {@link io.agentscope.harness.agent.HarnessAgent.Builder#artifactDeliveryTarget(ArtifactDeliveryTarget)}
 * 配置。配置后注册 {@code deliver_artifact} 工具，沙箱系统提示词引导模型使用它；
 * 未配置时，不暴露交付工具。
 */
@FunctionalInterface
public interface ArtifactDeliveryTarget {

    /**
     * 将 {@code request} 携带的产物交付到目标。
     *
     * @param runtimeContext 本次调用的智能体运行上下文；没有合并后的上下文时可为 {@code null}
     * @param request 要交付的产物（源文件路径、字节、目标文件名、说明与 {@code force} 标记）。
     *      工具已验证 {@code request.fileName()} 是普通文件名，不含路径分隔符、{@code .} 或 {@code ..}；
     *      由目标实现决定如何映射到自己的目录布局
     * @return 交付结果；实现不得返回 {@code null}
     */
    ArtifactDeliveryResult deliver(RuntimeContext runtimeContext, ArtifactDeliveryRequest request);
}
