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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 通用的可扩展事件，用于不适合特定 {@link AgentEvent} 子类型的信号。
 *
 * <p>服务层中间件使用它向前端订阅方通知状态变化（任务进度、团队成员、权限更新等），
 * 避免将应用专用类型混入核心智能体事件枚举。
 *
 * <p>前端实现应平稳处理未知的 {@link #getName()} 值：直接跳过，不报错。
 *
 * <p>已知的 {@code name} 值：
 * <ul>
 *   <li>{@code "state_updated"}：工具调用期间智能体状态（任务／权限）发生变化</li>
 *   <li>{@code "team_updated"}：团队成员关系发生变化（增加成员、创建或解散团队）</li>
 * </ul>
 */
public class CustomEvent extends AgentEvent {

    private final String name;
    private final Map<String, Object> value;

    @JsonCreator
    public CustomEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("name") String name,
            @JsonProperty("value") Map<String, Object> value) {
        super(id, createdAt);
        this.name = name;
        this.value = value != null ? value : Map.of();
    }

    public CustomEvent(String name, Map<String, Object> value) {
        this.name = name;
        this.value = value != null ? value : Map.of();
    }

    public CustomEvent(String name) {
        this(name, Map.of());
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.CUSTOM;
    }

    /**
     * 返回通知种类。已知值见类级 Javadoc。
     */
    public String getName() {
        return name;
    }

    /**
     * 返回可序列化为 JSON 的任意载荷，其模式由 {@link #getName()} 决定。
     */
    public Map<String, Object> getValue() {
        return value;
    }
}
