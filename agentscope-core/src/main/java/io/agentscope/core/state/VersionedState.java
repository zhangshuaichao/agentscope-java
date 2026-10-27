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
package io.agentscope.core.state;

/**
 * 状态值及其在 {@link AgentStateStore} 中对应的乐观并发版本。
 *
 * @param <T> 状态类型
 * @param value 加载的状态；键不存在时为 {@code null}
 * @param version 存储版本；后端不支持版本控制时为 {@link AgentStateStore#UNVERSIONED}，
 *     支持版本控制但键尚不存在时为 {@code 0}
 */
public record VersionedState<T extends State>(T value, long version) {

    /** 存储中存在值时返回 {@code true}。 */
    public boolean isPresent() {
        return value != null;
    }
}
