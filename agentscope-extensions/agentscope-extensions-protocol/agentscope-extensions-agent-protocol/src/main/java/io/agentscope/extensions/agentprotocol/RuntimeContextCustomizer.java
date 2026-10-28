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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于调整 Agent Protocol 任务运行 {@link RuntimeContext} 的钩子。
 *
 * <p>存储始终将调用方的 {@code context.attributes} 作为整体映射放在 {@link AgentProtocolConstants#RUNTIME_CONTEXT_ATTRIBUTES_KEY} 下，避免与框架键冲突。可注册按 {@code @Order} 排序的 customizer bean，进一步将选定属性提升为独立键、转换为有类型的值，或派生新属性：
 *
 * <pre>{@code
 * @Bean
 * RuntimeContextCustomizer tenantContext() {
 *     return (request, builder) -> {
 *         String tenant = request.attributeString("tenant");
 *         if (tenant != null) {
 *             builder.put(TenantInfo.class, tenantService.load(tenant));
 *         }
 *     };
 * }
 * }</pre>
 *
 * <p>【学习提示】属性命名空间与保留键过滤只防止意外覆盖框架路由键，不验证租户身份或授权；
 * 自定义认证与属性值校验仍应由应用承担。
 *
 * <p>customizer 在命名空间注入后按 bean 顺序执行，因此后者会覆盖前者。它们属于受信任代码：与 {@link #flatten(String...)} 不同，手写 customizer 可以写任意键，包括框架保留键。
 */
@FunctionalInterface
public interface RuntimeContextCustomizer {

    /** 调整正在为 {@code request} 构建的上下文。每轮运行调用一次，包括提交及每次恢复。 */
    void customize(AgentRequest request, RuntimeContext.Builder builder);

    /**
     * 将指定 {@code context.attributes} 条目复制到同名顶层运行时上下文键，供通过 {@code ctx.get("tenant")} 等普通键读取的工具使用。
     *
     * <p>仅复制列出的名称；框架自身读取的名称（参见 {@link AgentProtocolConstants#isReservedRuntimeContextKey(String)}）会记录警告并跳过，避免调用方通过同名属性劫持 {@code agentId} 等路由键。
     */
    static RuntimeContextCustomizer flatten(String... keys) {
        Logger log = LoggerFactory.getLogger(RuntimeContextCustomizer.class);
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(keys));
        allowed.removeIf(
                key -> {
                    if (AgentProtocolConstants.isReservedRuntimeContextKey(key)) {
                        log.warn(
                                "Ignoring reserved runtime context key '{}' in"
                                        + " RuntimeContextCustomizer.flatten",
                                key);
                        return true;
                    }
                    return false;
                });
        return (request, builder) -> {
            Map<String, Object> attributes = request.attributes();
            for (String key : allowed) {
                Object value = attributes.get(key);
                if (value != null) {
                    builder.put(key, value);
                }
            }
        };
    }
}
