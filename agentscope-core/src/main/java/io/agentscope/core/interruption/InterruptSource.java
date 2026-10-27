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
package io.agentscope.core.interruption;

/**
 * 表示中断来源的枚举。
 *
 * <p>说明由谁或什么事件发起中断：
 * <ul>
 *   <li>{@link #USER}: 用户请求触发的中断</li>
 *   <li>{@link #TOOL}: 工具执行逻辑触发的中断</li>
 *   <li>{@link #SYSTEM}: 系统触发的中断（超时、资源限制等）</li>
 * </ul>
 */
public enum InterruptSource {
    /** 由用户请求发起的中断。 */
    USER,

    /** 由工具执行逻辑发起的中断。 */
    TOOL,

    /** 由系统发起的中断（超时、资源限制等）。 */
    SYSTEM
}
