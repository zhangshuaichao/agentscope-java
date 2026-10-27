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
package io.agentscope.core.tool;

/**
 * 从外部来源解析 ToolExecutionContext 的提供者接口。
 *
 * <p>支持与 Spring 等依赖注入框架集成，从 IoC 容器、请求作用域、线程局部存储或其他上下文管理机制解析工具执行上下文。
 *
 * <p><b>Spring 集成示例：</b>
 *
 * <pre>{@code
 * // 1. 定义带 Spring 作用域的上下文 POJO
 * @Component
 * @Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
 * public class UserContext {
 *     private String userId;
 *     private String sessionId;
 *     private Map<String, String> permissions;
 *     // 读取与设置方法……
 * }
 *
 * // 2. 实现 ToolExecutionContextProvider
 * public class SpringContextProvider implements ToolExecutionContextProvider {
 *     private final ApplicationContext applicationContext;
 *
 *     public SpringContextProvider(ApplicationContext applicationContext) {
 *         this.applicationContext = applicationContext;
 *     }
 *
 *     @Override
 *     public <T> T resolveContext(Class<T> targetType) {
 *         try {
 *             // 尝试从 Spring 容器解析
 *             if (targetType == ToolExecutionContext.class) {
 *                 // 将 Spring Bean 转换为 ToolExecutionContext
 *                 UserContext userCtx = applicationContext.getBean(UserContext.class);
 *                 return (T) ToolExecutionContext.of(userCtx);
 *             } else {
 *                 // 为自定义类型直接解析 Bean
 *                 return applicationContext.getBean(targetType);
 *             }
 *         } catch (Exception e) {
 *             return null; // 上下文不可用
 *         }
 *     }
 * }
 *
 * // 3. 在 Spring 配置中注册提供者
 * @Configuration
 * public class AgentScopeConfig {
 *     @Bean
 *     public ToolExecutionContextProvider contextProvider(ApplicationContext ctx) {
 *         SpringContextProvider provider = new SpringContextProvider(ctx);
 *         ToolExecutionContext.setContextProvider(provider);
 *         return provider;
 *     }
 * }
 *
 * // 4. 在工具方法中使用，上下文自动从 Spring 解析
 * @Tool(name = "query_db", description = "Query database")
 * public ToolResultBlock queryDb(
 *     @ToolParam(name = "query") String query,
 *     UserContext context  // 自动从 Spring RequestScope 解析
 * ) {
 *     String userId = context.getUserId();
 *     // ...
 * }
 * }</pre>
 *
 * <p><b>ThreadLocal 示例：</b>
 *
 * <pre>{@code
 * public class ThreadLocalContextProvider implements ToolExecutionContextProvider {
 *     private static final ThreadLocal<ToolExecutionContext> CONTEXT = new ThreadLocal<>();
 *
 *     public static void setContext(ToolExecutionContext ctx) {
 *         CONTEXT.set(ctx);
 *     }
 *
 *     public static void clear() {
 *         CONTEXT.remove();
 *     }
 *
 *     @Override
 *     public <T> T resolveContext(Class<T> targetType) {
 *         ToolExecutionContext ctx = CONTEXT.get();
 *         if (ctx == null) return null;
 *
 *         if (targetType == ToolExecutionContext.class) {
 *             return (T) ctx;
 *         } else {
 *             return ctx.as(targetType);
 *         }
 *     }
 * }
 * }</pre>
 */
/**
 * @deprecated 属于 {@link ToolExecutionContext} 层，已由 {@link io.agentscope.core.agent.RuntimeContext} 取代。
 */
@Deprecated
@FunctionalInterface
public interface ToolExecutionContextProvider {

    /**
     * 解析指定类型的上下文实例。
     *
     * <p>工具方法参数需要上下文注入，而调用处未显式提供上下文时，框架调用此方法。实现应尝试从其管理的数据源（Spring 容器、ThreadLocal 等）解析上下文，并返回所需类型的实例。
     *
     * @param targetType 要解析的目标类型（ToolExecutionContext 或自定义 POJO）
     * @param <T> 目标类型
     * @return 解析后的上下文实例；不可用时为 null
     */
    <T> T resolveContext(Class<T> targetType);
}
