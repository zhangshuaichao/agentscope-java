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
package io.agentscope.harness.agent;

import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.util.List;

/**
 * 控制智能体状态在多次调用间如何隔离与共享。
 *
 * <p>此枚举是沙箱文件系统后端（{@link io.agentscope.harness.agent.sandbox.SandboxContext}）
 * 与远程文件系统后端（{@link RemoteFilesystemSpec}）共同使用的隔离作用域定义。
 *
 * <p><b>沙箱语义：</b>作用域决定持久化和加载 {@code _sandbox.json} 状态时使用的键。
 * 解析到<em>同一</em>作用域键的调用会顺序复用沙箱状态，每次调用恢复前次持久化的状态。
 *
 * <p><b>存储命名空间语义：</b>作用域决定 {@link RemoteFilesystem} 向共享键值存储
 * 路由文件时使用的命名空间前缀。不同作用域生成不同前缀，控制哪些调用共享同一文件视图。
 *
 * <p>作用域选择：
 * <ul>
 *   <li>{@link #USER}：同一用户的所有会话共享；默认值。缺少 {@code userId} 时回退到 {@link #SESSION}。</li>
 *   <li>{@link #SESSION}：每个会话独立隔离。</li>
 *   <li>{@link #AGENT}：同一智能体的所有用户和会话共享。</li>
 *   <li>{@link #GLOBAL}：同一工作区／存储实例内全局共享。</li>
 * </ul>
 *
 * <p><b>并发说明：</b>沙箱模式共享的是可顺序复用的状态，不是正在运行的实例。
 * 同一作用域的并发调用各自拥有运行容器，调用结束时以最后持久化的快照汇合。
 */
public enum IsolationScope {

    /**
     * 按会话标识符隔离。
     *
     * <p>每个不同会话有独立沙箱状态／存储命名空间。如果
     * {@link io.agentscope.core.agent.RuntimeContext} 没有会话键，则跳过状态查询，
     * 创建新沙箱（或使用默认存储命名空间）。
     */
    SESSION,

    /**
     * 属于同一 {@link io.agentscope.core.agent.RuntimeContext#getUserId() userId}
     * 的全部会话共享。
     *
     * <p>这是默认作用域。缺少 {@code userId} 时回退到 {@link #SESSION}，改用会话标识符。
     */
    USER,

    /**
     * 同一智能体（按名称标识）的全部用户和会话共享。
     *
     * <p>智能体名称在构建时固定且始终可用，因此不会因缺少上下文字段而降级。
     */
    AGENT,

    /**
     * 同一工作区存储实例内全局共享一份状态／命名空间。
     *
     * <p>谨慎使用：共享同一存储的所有智能体和用户都会竞争写入全局槽位。
     */
    GLOBAL;

    /**
     * 创建 {@link NamespaceFactory}，按本作用域从
     * {@link io.agentscope.core.agent.RuntimeContext} 推导文件系统命名空间前缀。
     *
     * <ul>
     *   <li>{@link #USER}：使用 {@code userId} 前缀；缺少 {@code userId} 时回退到 {@code sessionId}。
     *   <li>{@link #SESSION}：使用 {@code sessionId} 前缀。
     *   <li>{@link #AGENT} / {@link #GLOBAL}：不加前缀（工作区已按智能体划分）。
     * </ul>
     *
     * @return 与此作用域一致的命名空间工厂
     */
    public NamespaceFactory toNamespaceFactory() {
        return switch (this) {
            case USER ->
                    rc -> {
                        if (rc == null) {
                            return List.of();
                        }
                        String uid = rc.getUserId();
                        if (uid != null && !uid.isBlank()) {
                            return List.of(uid);
                        }
                        String sid = rc.getSessionId();
                        return (sid != null && !sid.isBlank()) ? List.of(sid) : List.of();
                    };
            case SESSION ->
                    rc -> {
                        if (rc == null) {
                            return List.of();
                        }
                        String sid = rc.getSessionId();
                        return (sid != null && !sid.isBlank()) ? List.of(sid) : List.of();
                    };
            case AGENT, GLOBAL -> rc -> List.of();
        };
    }
}
