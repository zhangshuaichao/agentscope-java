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

import java.util.Map;

/**
 * 不同 A2A 传输的接口，例如 JSON-RPC、GRPC、REST 和其他自定义传输，
 * 用于将请求接入与请求处理分离。
 *
 * <p>开发者通过端点（Controller、Route、StreamObserver 等）获取请求体与请求头，
 * 再调用对应接口实现实际处理目标传输。
 */
public interface TransportWrapper<T, R> {

    /**
     * 获取包装器的传输类型名称。
     *
     * @return 传输类型名称
     */
    String getTransportType();

    /**
     * 处理目标传输请求，包括流式与非流式请求。
     *
     * @param body 传输请求体
     * @param headers 传输请求头映射
     * @param metadata 来自请求或开发者的其他传输请求元数据
     * @return 根据不同传输请求返回不同响应
     */
    R handleRequest(T body, Map<String, String> headers, Map<String, Object> metadata);
}
