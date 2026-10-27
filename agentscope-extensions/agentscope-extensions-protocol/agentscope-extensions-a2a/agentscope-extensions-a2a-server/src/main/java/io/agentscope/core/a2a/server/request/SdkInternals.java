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

package io.agentscope.core.a2a.server.request;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;

/**
 * AgentScope 通过反射访问的 A2A SDK 成员，因为 SDK 未公开这些成员。
 * 集中放在此处，使升级 SDK 时只需检查此类。
 */
final class SdkInternals {

    private SdkInternals() {}

    /**
     * 设置请求处理器超时。SDK 构建器固定设置几秒超时，
     * 对需要调用模型的智能体太短，且未提供修改方式。
     *
     * @param handler 请求处理器
     * @param agentCompletionTimeoutSeconds 阻塞请求等待智能体的时长
     * @param consumptionCompletionTimeoutSeconds 之后等待事件消费完成的时长
     * @throws IllegalStateException SDK 不存在这些字段时抛出
     */
    static void setTimeouts(
            DefaultRequestHandler handler,
            int agentCompletionTimeoutSeconds,
            int consumptionCompletionTimeoutSeconds) {
        try {
            setInt(handler, "agentCompletionTimeoutSeconds", agentCompletionTimeoutSeconds);
            setInt(
                    handler,
                    "consumptionCompletionTimeoutSeconds",
                    consumptionCompletionTimeoutSeconds);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set the A2A request timeouts", e);
        }
    }

    /**
     * 停止持久化及分派事件的线程。此方法非公开，SDK 通过 CDI 停止它。
     *
     * @param processor 事件处理器
     * @throws IllegalStateException SDK 不存在此方法时抛出
     */
    static void stop(MainEventBusProcessor processor) {
        try {
            Method stop = MainEventBusProcessor.class.getDeclaredMethod("stop");
            stop.setAccessible(true);
            stop.invoke(processor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to stop the A2A event processor", e);
        }
    }

    private static void setInt(DefaultRequestHandler handler, String name, int value)
            throws ReflectiveOperationException {
        Field field = DefaultRequestHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(handler, value);
    }
}
