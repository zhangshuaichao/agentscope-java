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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 为给定 {@link IsolationScope} 唯一标识沙箱状态槽位的不可变键。
 *
 * <p>使用 {@link #resolve} 从 {@link RuntimeContext} 获取键。缺少所需上下文字段时返回 {@link Optional#empty()}（例如 {@code USER} 作用域既没有 {@code userId}，也没有可回退的 sessionId）；这意味着应跳过状态查找。具体 USER 回退行为见 resolve 方法。
 */
public final class SandboxIsolationKey {

    private static final Logger log = LoggerFactory.getLogger(SandboxIsolationKey.class);

    static final String GLOBAL_VALUE = "__global__";

    private final IsolationScope scope;
    private final String value;

    private SandboxIsolationKey(IsolationScope scope, String value) {
        this.scope = scope;
        this.value = value;
    }

    /**
     * 根据给定作用域、运行时上下文和智能体 ID 解析隔离键。
     *
     * <p>解析规则：
     * <ul>
     *   <li>{@code SESSION}：要求非 null 的 {@code sessionId}，值为 {@code sessionId}；缺失时返回空。</li>
     *   <li>{@code USER}：存在 {@code userId} 时使用它；userId 缺失时回退为 {@code SESSION} 作用域并使用 sessionId。只有二者都缺失时返回空。</li>
     *   <li>{@code AGENT}：值为 {@code agentId}，始终存在。</li>
     *   <li>{@code GLOBAL}：值为 {@value #GLOBAL_VALUE}，始终存在。</li>
     *   <li>{@code null} 作用域：按 {@code USER} 处理。</li>
     * </ul>
     *
     * @param scope 所需隔离作用域；{@code null} 默认使用 {@code USER}
     * @param ctx 当前调用的运行时上下文；可为 {@code null}
     * @param agentId 构建时解析的智能体名称，不得为 null
     * @return 解析出的键；缺少所需上下文字段时返回空
     */
    public static Optional<SandboxIsolationKey> resolve(
            IsolationScope scope, RuntimeContext ctx, String agentId) {
        IsolationScope effective = scope != null ? scope : IsolationScope.USER;
        return switch (effective) {
            case SESSION -> {
                if (ctx == null || ctx.getSessionId() == null || ctx.getSessionId().isBlank()) {
                    yield Optional.empty();
                }
                yield Optional.of(
                        new SandboxIsolationKey(IsolationScope.SESSION, ctx.getSessionId()));
            }
            case USER -> {
                if (ctx != null && ctx.getUserId() != null && !ctx.getUserId().isBlank()) {
                    yield Optional.of(
                            new SandboxIsolationKey(IsolationScope.USER, ctx.getUserId()));
                }
                // userId 缺失时回退到 SESSION
                if (ctx != null && ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
                    log.debug(
                            "[sandbox] USER isolation scope requested but userId is absent"
                                    + " — falling back to SESSION scope using sessionId");
                    yield Optional.of(
                            new SandboxIsolationKey(IsolationScope.SESSION, ctx.getSessionId()));
                }
                log.warn(
                        "[sandbox] USER isolation scope requested but both userId and sessionId"
                                + " are absent — skipping state lookup; a fresh sandbox will be"
                                + " created");
                yield Optional.empty();
            }
            case AGENT ->
                    Optional.of(
                            new SandboxIsolationKey(
                                    IsolationScope.AGENT, Objects.requireNonNull(agentId)));
            case GLOBAL ->
                    Optional.of(new SandboxIsolationKey(IsolationScope.GLOBAL, GLOBAL_VALUE));
        };
    }

    /**
     * 返回隔离作用域。
     *
     * @return 作用域
     */
    public IsolationScope getScope() {
        return scope;
    }

    /**
     * 返回作用域内的区分值（例如会话 ID、用户 ID、智能体名称）。
     *
     * @return 值字符串
     */
    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SandboxIsolationKey that)) return false;
        return scope == that.scope && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, value);
    }

    @Override
    public String toString() {
        return "SandboxIsolationKey{scope=" + scope + ", value='" + value + "'}";
    }
}
