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
package io.agentscope.builder.web.managed;

/**
 * 共享会话状态词汇。
 * <p>各平面的职责：
 * <ul>
 *   <li>生命周期状态（{@link #CREATED}、{@link #ARCHIVED}、{@link #TERMINATED}）— 控制平面
 *   <li>运行时状态（{@link #RUNNING}、{@link #IDLE}、{@link #REQUIRES_ACTION}、{@link
 *       #RESCHEDULED}）— 数据平面
 * </ul>
 */
public final class SessionStatuses {

    private SessionStatuses() {}

    public static final String CREATED = "created";
    public static final String RUNNING = "running";
    public static final String IDLE = "idle";
    public static final String REQUIRES_ACTION = "requires_action";
    public static final String TERMINATED = "terminated";
    public static final String RESCHEDULED = "rescheduled";
    public static final String ARCHIVED = "archived";

    /** 仅应由数据平面写入的运行时状态。 */
    public static boolean isRuntimeStatus(String status) {
        return RUNNING.equals(status)
                || IDLE.equals(status)
                || REQUIRES_ACTION.equals(status)
                || RESCHEDULED.equals(status);
    }

    /** 仅应由控制平面写入的生命周期状态。 */
    public static boolean isLifecycleStatus(String status) {
        return CREATED.equals(status) || ARCHIVED.equals(status) || TERMINATED.equals(status);
    }
}
