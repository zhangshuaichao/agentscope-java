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

package io.agentscope.core.training.util;

import java.time.Duration;

/**
 * Training 模块单元测试常量。
 */
public final class TrainingTestConstants {

    private TrainingTestConstants() {}

    // Trinity 服务
    public static final String TEST_TRINITY_ENDPOINT = "http://mock-trinity:8080";
    public static final String TEST_MODEL_NAME = "test-model";
    public static final String TEST_API_KEY = "test-api-key";

    // 任务／运行 ID
    public static final String TEST_TASK_ID = "test-task-001";
    public static final String TEST_RUN_ID = "0";

    // 超时时间
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration SHORT_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration LONG_TIMEOUT = Duration.ofSeconds(10);

    // 采样
    public static final double DEFAULT_SAMPLE_RATE = 0.1;
    public static final double HIGH_SAMPLE_RATE = 0.9;
    public static final double LOW_SAMPLE_RATE = 0.01;

    // 提交间隔
    public static final long DEFAULT_COMMIT_INTERVAL = 300;
    public static final long SHORT_COMMIT_INTERVAL = 60;

    // 池大小
    public static final int DEFAULT_SHADOW_POOL_SIZE = 10;
    public static final int DEFAULT_SHADOW_POOL_CAPACITY = 1000;

    // 重复次数
    public static final int DEFAULT_REPEAT_TIME = 1;
    public static final int MULTI_REPEAT_TIME = 3;

    // 测试消息
    public static final String TEST_MSG_ID_1 = "msg-001";
    public static final String TEST_MSG_ID_2 = "msg-002";
    public static final String TEST_MSG_ID_3 = "msg-003";

    // 智能体名称
    public static final String TEST_AGENT_NAME = "TestAgent";
    public static final String TEST_SHADOW_AGENT_NAME = "TestAgent-shadow";
}
