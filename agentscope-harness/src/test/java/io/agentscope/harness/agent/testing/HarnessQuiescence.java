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
package io.agentscope.harness.agent.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 注册 {@link HarnessBackgroundTaskQuiescenceExtension} 的组合注解，在每个测试后、JUnit 删除 {@code @TempDir} 前等待 Harness 记忆刷新／维护后台任务结束。
 *
 * <p>适用于构建 {@code HarnessAgent}、通过 {@code .block()} / {@code .stream()...block()} 驱动完成，并将 {@code @TempDir} 用作工作区或状态目录的测试。否则 {@code MemoryFlushMiddleware#onAgent} 的 {@code doOnComplete} 发起的异步刷新会与 {@code @TempDir} 清理竞争，出现间歇性的 {@code "Failed to delete temp directory"} / {@code "Failed to close extension context"}。调用 {@code HarnessAgent.close()} 效果相同；此注解覆盖创建临时智能体却不关闭的测试。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(HarnessBackgroundTaskQuiescenceExtension.class)
public @interface HarnessQuiescence {}
