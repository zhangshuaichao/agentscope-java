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
package io.agentscope.dataagent.runtime.session;

/**
 * 根据 {@link SessionResetPolicy} 评估会话有效性的结果。
 * @param fresh 会话仍可复用时为 true，应切换到新会话时为 false
 * @param dailyResetAtMs 每日重置边界时间戳（未配置每日重置时为 null）
 * @param idleExpiresAtMs 空闲过期时间戳（未配置空闲重置时为 null）
 */
public record SessionFreshness(boolean fresh, Long dailyResetAtMs, Long idleExpiresAtMs) {}
