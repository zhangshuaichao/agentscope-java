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
package io.agentscope.harness.agent.gateway.channel;

import java.util.Objects;

/**
 * 标识对话对端，即消息发送给谁或什么对象（用户私聊、频道、群组或线程）。
 * {@link ChannelRouter} 用它评估绑定并构建会话键。
 *
 * @param kind 对端分类
 * @param id 服务商分配的对端标识（用户 ID、频道 ID、线程 ID 等）
 */
public record Peer(PeerKind kind, String id) {

    public Peer {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    /**
     * 返回绑定匹配使用的组合键 {@code "<kind>:<id>"}。
     */
    public String key() {
        return kind.value() + ":" + id;
    }

    /**
     * 创建直接／私聊对端。
     */
    public static Peer direct(String id) {
        return new Peer(PeerKind.DIRECT, id);
    }

    /**
     * 创建频道对端。
     */
    public static Peer channel(String id) {
        return new Peer(PeerKind.CHANNEL, id);
    }

    /**
     * 创建群组对端。
     */
    public static Peer group(String id) {
        return new Peer(PeerKind.GROUP, id);
    }

    /**
     * 创建线程对端。
     */
    public static Peer thread(String id) {
        return new Peer(PeerKind.THREAD, id);
    }
}
