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

import io.agentscope.extensions.controlplane.adapter.AgentScopeAdapter;

/**
 * 将自行部署的智能体接入控制平面上报的入口。
 *
 * <p>完整观测（包括二级事件流）需要在构建智能体时注册观察中间件，因为 {@code ReActAgent} 的中间件列表在构造时固定：
 * <pre>{@code
 * AgentScopeAdapter adapter = new AgentScopeAdapter();
 * ReActAgent agent = ReActAgent.builder()
 *         .name("Friday")
 *         .model(model)
 *         .middleware(adapter.middleware())
 *         .build();
 *
 * SessionBridge bridge = ControlPlane.instrument(agent,
 *         ControlPlaneConfig.builder("my-agentscope-agent")
 *                 .controlPlaneHttp("http://localhost:8081")
 *                 .internalToken(System.getenv("BUILDER_INTERNAL_TOKEN"))
 *                 .namespace("default")
 *                 .enableEvents(true)
 *                 .contractHttpPort(18090)
 *                 .build(),
 *         adapter);
 * }</pre>
 *
 * <p>{@link #instrument(Object, ControlPlaneConfig)} 也可用于已经构建的智能体；它上报会话快照、有效上下文、历史和命令等实时状态读取能力，但不发出事件，因为构造后无法追加中间件。
 *
 * <p>返回的桥接器实现 {@link AutoCloseable}，关闭它可解除挂接并释放两个通道。
 */
public final class ControlPlane {

    private ControlPlane() {}

    /**
 * 为已构建智能体接入观测，不提供二级事件；参见类文档。
 */
    public static SessionBridge instrument(Object target, ControlPlaneConfig config) {
        return instrument(target, config, new AgentScopeAdapter());
    }

    /**
     * 使用预构建适配器为 {@code target} 接入观测，通常该适配器已作为中间件接入智能体。
     */
    public static SessionBridge instrument(
            Object target, ControlPlaneConfig config, FrameworkAdapter adapter) {
        return new SessionBridge(config).attach(target, adapter).start();
    }

    /**
     * 创建桥接器但不挂接或启动，供需要在智能体存在前持有适配器的调用方使用。随后调用 {@link SessionBridge#attach} 和 {@link SessionBridge#start}。
     */
    public static SessionBridge newBridge(ControlPlaneConfig config) {
        return new SessionBridge(config);
    }
}
