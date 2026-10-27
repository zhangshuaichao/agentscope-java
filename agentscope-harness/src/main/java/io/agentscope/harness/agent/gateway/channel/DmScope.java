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

/**
 * 控制私聊（{@link PeerKind#DIRECT}）会话键的作用域。
 *
 * <ul>
 *   <li>{@link #MAIN} — 指定智能体的全部私聊共享单个会话，适合单用户或共享助手场景。
 *   <li>{@link #PER_PEER} — 每个对端 ID 一个会话，多用户部署最常用。
 *   <li>{@link #PER_CHANNEL_PEER} — 类似 {@code PER_PEER}，但键中包含渠道名，
 *       区分同一对端在不同渠道的对话。
 *   <li>{@link #PER_ACCOUNT_CHANNEL_PEER} — 在 {@code PER_CHANNEL_PEER} 基础上加入账户 ID，
 *       适合同一渠道平台中的多账户（多机器人）部署。
 * </ul>
 */
public enum DmScope {
    MAIN,
    PER_PEER,
    PER_CHANNEL_PEER,
    PER_ACCOUNT_CHANNEL_PEER;

    /**
     * 未配置时使用的默认作用域。
     */
    public static DmScope defaultScope() {
        return MAIN;
    }
}
