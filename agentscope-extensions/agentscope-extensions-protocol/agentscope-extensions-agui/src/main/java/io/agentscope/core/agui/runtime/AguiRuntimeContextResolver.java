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
package io.agentscope.core.agui.runtime;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 为 AG-UI 请求解析调用方提供的 {@link RuntimeContext}。
 *
 * <p>应用可将其暴露为 bean（或编程传给 {@link io.agentscope.core.agui.processor.AguiRequestProcessor}），附加租户信息、追踪数据或工具依赖等请求级值。AG-UI 运行时元数据仍由协议适配器提供，发生冲突时优先使用协议值。
 *
 * <p>原生请求类型取决于传输方式，因此请求参数使用通配符（{@code <?>}）。需要原生请求的实现可通过 {@code request.getNativeRequest()} 获取（返回捕获类型），再以 {@code instanceof} 缩小类型范围。
 */
@FunctionalInterface
public interface AguiRuntimeContextResolver {

    /**
     * 解析当前请求的运行上下文。
     *
     * @param request AG-UI 请求上下文
     * @return 要合并到 AG-UI 运行的上下文，或 null
     */
    RuntimeContext resolve(AguiRuntimeContextRequest<?> request);
}
