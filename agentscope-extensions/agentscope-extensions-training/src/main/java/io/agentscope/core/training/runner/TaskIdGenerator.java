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

package io.agentscope.core.training.runner;

import java.util.UUID;

/**
 * 任务 ID 生成器，为智能体调用生成训练数据的顶层关联 ID。
 * <p>内部流程使用 UUID 并添加 task- 前缀，降低全局碰撞概率，便于识别。
 * @see RunExecutionContext
 * @see RunRegistry
 */
class TaskIdGenerator {

    private static final String PREFIX = "task";

    // 私有构造器，禁止实例化。
    private TaskIdGenerator() {}

    /**
     * 生成任务 ID，形式为 {@code task-{uuid}}。
     * <p>例如 {@code task-3e4f5a6b-7c8d-9e0f-1a2b-3c4d5e6f7a8b}。
     * @return 新任务 ID
     */
    public static String generate() {
        return PREFIX + "-" + UUID.randomUUID().toString();
    }

    /**
     * 生成带时间戳的替代任务 ID，形式为 {@code task-{timestamp}-{random}}，便于按时间观察分布。
     * <p>例如 {@code task-1704067200000-3e4f5a6b}。
     * @return 带时间戳的任务 ID
     */
    public static String generateWithTimestamp() {
        long timestamp = System.currentTimeMillis();
        String randomPart = UUID.randomUUID().toString().substring(0, 8);
        return PREFIX + "-" + timestamp + "-" + randomPart;
    }
}
