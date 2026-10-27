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
package io.agentscope.harness.agent.bus;

import java.util.Map;

/**
 * 从 {@link MessageBus} 队列或日志中读取的一条记录。
 * @param entryId 传输层记录标识符，例如 Redis Stream 记录 ID
 * @param payload 可序列化为 JSON 的载荷
 */
public record BusEntry(String entryId, Map<String, Object> payload) {}
