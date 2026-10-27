/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.transport.websocket;

/**
 * WebSocket 连接关闭信息。
 *
 * <p>包含 WebSocket 连接关闭时的关闭码和原因。
 *
 * @param code WebSocket 关闭码
 * @param reason 关闭原因消息
 */
public record CloseInfo(int code, String reason) {

    /** 正常关闭（1000）。 */
    public static final int NORMAL_CLOSURE = 1000;

    /** 端点离开（1001）。 */
    public static final int GOING_AWAY = 1001;

    /** 协议错误（1002）。 */
    public static final int PROTOCOL_ERROR = 1002;

    /** 异常关闭（1006）。 */
    public static final int ABNORMAL_CLOSURE = 1006;

    /**
     * 检查是否为正常关闭。
     *
     * @return 关闭码为 1000（正常关闭）时返回 true
     */
    public boolean isNormal() {
        return code == NORMAL_CLOSURE;
    }

    /**
     * 创建正常关闭的 CloseInfo。
     *
     * @param reason 关闭原因
     * @return CloseInfo 实例
     */
    public static CloseInfo normal(String reason) {
        return new CloseInfo(NORMAL_CLOSURE, reason);
    }

    /**
     * 创建异常关闭的 CloseInfo。
     *
     * @param reason 关闭原因
     * @return CloseInfo 实例
     */
    public static CloseInfo abnormal(String reason) {
        return new CloseInfo(ABNORMAL_CLOSURE, reason);
    }
}
