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
package io.agentscope.core.agent.config;

import io.agentscope.core.model.Model;

/**
 * 当 {@link ModelConfig#fallbackModel() fallback model} 接管失败的主模型时收到通知的观察者。
 * 否则，进程内无法观察到切换及原因：主模型错误在智能体为模型构建的回退包装器内被消费，
 * 该位置低于 {@code onModelCall} 中间件切入点和 {@code AgentEvent} 转换层，现有观察接口无法触及。
 *
 * <p>契约：
 * <ul>
 * <li>在切换位置、Reactor 线程上同步调用；实现必须快速且非阻塞</li>
 * <li>同一监听器由智能体的并发运行共享；实现必须线程安全</li>
 * <li>实现抛出的 {@link Exception} 会被记录并忽略，切换和回退调用不受影响继续执行（Error 不会被拦截）</li>
 * </ul>
 *
 * <p>监听器为 {@code null}（默认）时不改变智能体行为。
 */
@FunctionalInterface
public interface FailoverListener {

    /**
     * 主模型失败、回退模型接管时调用。
     *
     * @param primary 失败的主模型
     * @param error 主模型耗尽重试次数后触发切换的错误
     */
    void onFailover(Model primary, Throwable error);
}
