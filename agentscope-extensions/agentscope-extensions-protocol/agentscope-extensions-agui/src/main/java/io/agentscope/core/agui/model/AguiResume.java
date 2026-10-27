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
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 表示对先前中断的一条官方 AG-UI 恢复响应。
 *
 * <p>恢复条目随同一线程的下一次 {@link RunAgentInput} 发送，并通过 {@code interruptId} 关联被中断的运行。
 *
 * <p><b>重要区别：</b>用户显式拒绝请求的响应（例如 {@code { "approved": false }}）仍视为 {@code resolved}，<b>而非</b> {@code cancelled}。{@code cancelled} 状态用于用户未提供有意义输入且省略 {@code payload} 的情况。
 */
public class AguiResume {

    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_CANCELLED = "cancelled";

    private final String interruptId;
    private final String status;
    private final Object payload;

    /**
     * 创建 AG-UI 恢复条目。
     *
     * @param interruptId 来自 {@code RunFinished.outcome.interrupts[]} 的中断 ID
     * @param status 恢复状态，通常为 {@code resolved} 或 {@code cancelled}
     * @param payload 已解决中断的可选响应载荷
     */
    @JsonCreator
    public AguiResume(
            @JsonProperty("interruptId") String interruptId,
            @JsonProperty("status") String status,
            @JsonProperty("payload") Object payload) {
        this.interruptId = Objects.requireNonNull(interruptId, "interruptId cannot be null");
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.payload = payload;
    }

    /**
     * 获取本恢复条目回答的中断 ID。
     *
     * @return 中断 ID
     */
    public String getInterruptId() {
        return interruptId;
    }

    /**
     * 获取恢复状态。
     *
     * @return 状态
     */
    public String getStatus() {
        return status;
    }

    /**
     * 获取恢复载荷。
     *
     * @return 载荷；未提供时为 null
     */
    public Object getPayload() {
        return payload;
    }

    /**
     * 检查本条目是否解决了中断。
     *
     * @return 状态为 {@code resolved} 时为 true
     */
    public boolean isResolved() {
        return STATUS_RESOLVED.equals(status);
    }

    /**
     * 检查本条目是否取消了中断。
     *
     * @return 状态为 {@code cancelled} 时为 true
     */
    public boolean isCancelled() {
        return STATUS_CANCELLED.equals(status);
    }

    @Override
    public String toString() {
        return "AguiResume{interruptId='"
                + interruptId
                + "', status='"
                + status
                + "', payload="
                + payload
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiResume that = (AguiResume) o;
        return Objects.equals(interruptId, that.interruptId)
                && Objects.equals(status, that.status)
                && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(interruptId, status, payload);
    }
}
