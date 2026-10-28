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

import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit Jupiter 扩展，在每个测试方法后等待 Harness 发出后不等待的记忆刷新／维护后台任务结束。
 *
 * <p>{@link io.agentscope.harness.agent.HarnessAgent#close()} 会等待同一组追踪器，但许多测试构建临时 {@code HarnessAgent}，调用 {@code .block()} 后不关闭而任其回收。{@code MemoryFlushMiddleware#onAgent} 的 {@code doOnComplete} 发起的异步刷新会与 JUnit 的 {@code @TempDir} 清理竞争：删目录时仍持有文件句柄，或在遍历后新建文件，导致 Linux 和 Windows 上间歇出现 {@code "Failed to delete temp directory"} / {@code "Failed to close extension context"}。
 *
 * <p>此回调在测试方法及 {@code @AfterEach} 后、JUnit {@code TempDir} 关闭扩展上下文并删目录前运行，先等待后台写入静止。无在途任务时立即返回，对未触发刷新的测试无影响。超时或线程中断时抛出 {@link AssertionError}，将失败定位到后台任务未停止，而非后续模糊的 {@code "Failed to delete temp directory"}。
 */
public class HarnessBackgroundTaskQuiescenceExtension implements AfterEachCallback {

    private static final long TIMEOUT_SECONDS = 5;

    @Override
    public void afterEach(ExtensionContext context) {
        boolean flushQuiet =
                MemoryBackgroundTasks.awaitQuiescence(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!flushQuiet) {
            throw new AssertionError(
                    "Harness background tasks did not quiesce within "
                            + TIMEOUT_SECONDS
                            + "s; fire-and-forget memory flush may still race with @TempDir"
                            + " teardown");
        }
    }
}
