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
package io.agentscope.extensions.sandbox.kubernetes.client.internal.strategy;

/**
 * 发现访问沙箱所用 HTTP 基础 URL 的策略。
 * 与 Python SDK 的 {@code ConnectionStrategy} 对齐。
 */
public interface ConnectionStrategy {

    /**
     * 发现并返回基础 URL。
     *
     * @return 基础 URL（例如 {@code http://127.0.0.1:12345}）
     * @throws Exception 发现失败时抛出
     */
    String connect() throws Exception;

    /**
     * 释放此策略持有的资源（例如端口转发隧道）。
     */
    default void close() {
        // 默认不执行操作
    }

    /**
     * 是否向请求注入 {@code X-Sandbox-*} 路由器请求头。
     *
     * @return 基于路由器的策略为 true，集群内直连为 false
     */
    default boolean shouldInjectRouterHeaders() {
        return true;
    }
}
