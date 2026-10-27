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
package io.agentscope.harness.agent.subagent.protocol;

import java.util.Locale;

/**
 * 调用方期望的远程子智能体事件流详细程度，通过 {@code context.detail} 发送。
 *
 * <p>每一级都是前一级的超集。传输值为小写枚举名称。
 */
public enum RemoteStreamDetail {

    /**
     * 生命周期、工具调用边界、工具结果和确认请求。
     */
    STATUS,

    /**
     * {@link #STATUS} 加上文本和思考增量。流式父级的默认级别。
     */
    FULL,

    /**
     * {@link #FULL} 加上其余所有 {@link io.agentscope.core.event.AgentEvent}：
     * 块边界、工具参数和工具输出增量、带 token 用量的模型调用、提示、自定义事件，
     * 均以 {@link RemoteEventType#AGENT_EVENT} 转发。
     *
     * <p>这是唯一能完整重现本地子智能体流的级别，代价是高频运行中明显增加的流量。
     */
    VERBOSE;

    /**
     * 小写传输值，例如 {@code "verbose"}。
     */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 此级别是否包含至少需要 {@code required} 级别的事件。
     */
    public boolean includes(RemoteStreamDetail required) {
        return required != null && ordinal() >= required.ordinal();
    }

    /**
     * 解析传输值，null 或未知输入回退到 {@link #STATUS}。
     */
    public static RemoteStreamDetail parse(String value) {
        if (value == null || value.isBlank()) {
            return STATUS;
        }
        for (RemoteStreamDetail level : values()) {
            if (level.name().equalsIgnoreCase(value.trim())) {
                return level;
            }
        }
        return STATUS;
    }
}
