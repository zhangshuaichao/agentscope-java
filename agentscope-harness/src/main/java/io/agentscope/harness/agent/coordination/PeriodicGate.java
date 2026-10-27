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
package io.agentscope.harness.agent.coordination;

import java.time.Duration;

/**
 * 使用最小间隔节流，在多个调用方之间（也可跨节点）协调周期性后台工作。
 * {@link #tryClaim} 成功表示调用方可以继续；返回 {@code false} 表示
 * 其他调用方最近已领取该槽位，或最小间隔尚未过去。
 */
public interface PeriodicGate {

    /**
     * 尝试领取指定名称的周期性槽位。
     *
     * @param name 逻辑槽位标识符，例如 {@code "memory-flush:USER:alice"} 或 {@code "skill-curator"}
     * @param minGap 距上次成功领取所需的最小时间间隔
     * @return 成功领取且可以继续时为 {@code true}，否则为 {@code false}
     */
    boolean tryClaim(String name, Duration minGap);
}
