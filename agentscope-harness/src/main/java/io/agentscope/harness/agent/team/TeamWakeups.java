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
package io.agentscope.harness.agent.team;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 连接 {@link TeamClient} 实现与实际管理队友会话的运行时。
 *
 * <p>{@link TeamClient} 只知道成员名称（如 {@code lead}、{@code w1}），不知道运行时会话 ID，
 * 因此无法自行唤醒队友。承载队友的中间件在此注册 {@link Hook}，
 * 客户端随后即可通知成员，而无需依赖中间件包。
 */
public final class TeamWakeups {

    /** 唤醒队友，可选地将 {@code notice} 注入其下一轮次。 */
    @FunctionalInterface
    public interface Hook {
        boolean wake(String teamName, String memberName, String notice);
    }

    private static final AtomicReference<Hook> HOOK = new AtomicReference<>();

    private TeamWakeups() {}

    /** 注册进程级唤醒钩子。 */
    public static void register(Hook hook) {
        HOOK.set(hook);
    }

    /** 唤醒 {@code memberName}；没有钩子或存活会话时返回 {@code false}。 */
    public static boolean wake(String teamName, String memberName, String notice) {
        Hook hook = HOOK.get();
        if (hook == null || teamName == null || memberName == null || memberName.isBlank()) {
            return false;
        }
        try {
            return hook.wake(teamName, memberName, notice);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
