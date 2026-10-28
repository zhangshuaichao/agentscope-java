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
package io.agentscope.extensions.scheduler.quartz;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link QuartzAgentScheduler} 实例注册表。
 * <p>使 {@link AgentQuartzJob} 能找到创建它的调度器，获取任务信息并执行智能体逻辑。
 */
class QuartzAgentSchedulerRegistry {

    private static final Map<String, QuartzAgentScheduler> REGISTRY = new ConcurrentHashMap<>();

    /**
     * 按给定 ID 注册 QuartzAgentScheduler。
     * @param id 关联调度器的 ID
     * @param scheduler 待注册实例
     */
    static void register(String id, QuartzAgentScheduler scheduler) {
        REGISTRY.put(id, scheduler);
    }

    /**
     * 注销给定 ID 对应的 QuartzAgentScheduler。
     * @param id 待注销调度器 ID
     */
    static void unregister(String id) {
        REGISTRY.remove(id);
    }

    /**
     * 获取给定 ID 对应的 QuartzAgentScheduler。
     * @param id 待获取调度器 ID
     * @return 对应实例，不存在时为 null
     */
    static QuartzAgentScheduler get(String id) {
        return REGISTRY.get(id);
    }
}
