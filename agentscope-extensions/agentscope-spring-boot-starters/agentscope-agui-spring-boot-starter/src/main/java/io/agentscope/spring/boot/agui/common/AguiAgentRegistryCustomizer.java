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
package io.agentscope.spring.boot.agui.common;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.util.function.Consumer;

/**
 * {@link AguiAgentRegistry} 的定制器。
 *
 * <p>用法示例：
 *
 * <pre>{@code
 *     public AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer() {
 *         AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer = new AguiAgentRegistryCustomizer() {
 *             @Override
 *             public void customize(AguiAgentRegistry registry) {
 *                 registry.registerFactory("chat", () -> createChatAgent());
 *             }
 *         };
 *         return aguiAgentRegistryCustomizer;
 *     }
 * }</pre>
 */
@FunctionalInterface
public interface AguiAgentRegistryCustomizer extends Consumer<AguiAgentRegistry> {

    /**
     * 定制 {@link AguiAgentRegistry}。
     *
     * @param registry 待定制的注册表
     */
    void customize(AguiAgentRegistry registry);

    /**
     * 接收给定注册表并执行定制。
     * @param registry 智能体注册表
     */
    default void accept(AguiAgentRegistry registry) {
        this.customize(registry);
    }
}
