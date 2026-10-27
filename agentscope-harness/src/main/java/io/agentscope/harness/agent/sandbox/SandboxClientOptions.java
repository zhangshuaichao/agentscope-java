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
package io.agentscope.harness.agent.sandbox;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;

/**
 * 沙箱客户端配置选项基类。
 *
 * <p>每个具体子类描述一种沙箱后端（例如 Docker），并能通过 {@link #createClient()} 自行实例化对应的 {@link SandboxClient}。调用方因此只需配置选项对象，由 {@link io.agentscope.harness.agent.HarnessAgent.Builder} 自动推导客户端。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DockerSandboxClientOptions.class, name = "docker"),
})
public abstract class SandboxClientOptions {

    /**
     * 返回 JSON 序列化使用的类型判别标记。
     *
     * @return 类型字符串（例如 "docker"）
     */
    public abstract String getType();

    /**
     * 创建与这些选项对应的 {@link SandboxClient} 实现。
     *
     * <p>未显式提供客户端时，由 {@link io.agentscope.harness.agent.HarnessAgent.Builder} 调用，因此调用方只需配置选项对象。
     *
     * @return 可直接使用的新客户端实例
     */
    public abstract SandboxClient<? extends SandboxClientOptions> createClient();

    /**
     * 返回沙箱容器内的工作区绝对根路径。
     *
     * <p>默认为 {@code /workspace}。后端使用不同根目录时，由具体子类覆盖（例如 AgentRun 使用 {@code /home/agentscope/workspace}）。
     *
     * @return 工作区根路径字符串
     */
    public String getWorkspaceRoot() {
        return "/workspace";
    }
}
