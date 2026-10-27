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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.ExceptionUtils;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

/**
 * 调用工具方法并处理类型转换和错误，负责基于反射的方法调用及参数转换。
 *
 * <p>【学习提示】Mono.fromCallable 延迟到订阅时才调用同步方法，但它本身不切换线程。
 * 真正的调度、超时和重试由 ToolExecutor 的基础设施路径负责。区分“返回 Mono”和
 * “已经异步执行”，可避免在响应式线程上误跑阻塞任务。参数注入则是另一条边界：
 * 模型输入从 input 映射转换，运行时上下文由框架提供，不应让模型伪造身份上下文。
 */
class ToolMethodInvoker {

    private final ToolResultConverter defaultConverter;

    ToolMethodInvoker(ToolResultConverter resultConverter) {
        this.defaultConverter = resultConverter;
    }

    /**
     * 异步调用工具方法，支持自定义转换器。
     *
     * @param toolObject 包含方法的对象
     * @param method 要调用的方法
     * @param param 包含 input、toolUseBlock、agent 和 context 的工具调用参数
     * @param customConverter 本次调用的自定义转换器（null 表示使用默认值）
     * @return 包含 ToolResultBlock 的 Mono
     */
    Mono<ToolResultBlock> invokeAsync(
            Object toolObject,
            Method method,
            ToolCallParam param,
            ToolResultConverter customConverter) {
        // 提供了自定义转换器则使用它，否则使用默认转换器
        final ToolResultConverter converter =
                customConverter != null ? customConverter : defaultConverter;

        Map<String, Object> input = param.getInput();
        Agent agent = param.getAgent();
        RuntimeContext runtimeContext = param.getRuntimeContext();
        ToolEmitter emitter = param.getEmitter();

        Class<?> returnType = method.getReturnType();

        if (returnType == CompletableFuture.class) {
            // 返回 CompletableFuture 的异步方法：调用并转换为 Mono
            return Mono.fromCallable(
                            () -> {
                                method.setAccessible(true);
                                Object[] args =
                                        convertParameters(
                                                method, input, agent, runtimeContext, emitter);
                                @SuppressWarnings("unchecked")
                                CompletableFuture<Object> future =
                                        (CompletableFuture<Object>) method.invoke(toolObject, args);
                                return future;
                            })
                    .flatMap(
                            future ->
                                    Mono.fromFuture(future)
                                            .map(
                                                    r ->
                                                            converter.convert(
                                                                    r, extractGenericType(method)))
                                            .onErrorResume(this::handleError))
                    .onErrorResume(this::handleError);

        } else if (returnType == Mono.class) {
            // 返回 Mono 的异步方法：调用并使用 flatMap
            return Mono.fromCallable(
                            () -> {
                                method.setAccessible(true);
                                Object[] args =
                                        convertParameters(
                                                method, input, agent, runtimeContext, emitter);
                                @SuppressWarnings("unchecked")
                                Mono<Object> mono = (Mono<Object>) method.invoke(toolObject, args);
                                return mono;
                            })
                    .flatMap(
                            mono ->
                                    mono.map(r -> converter.convert(r, extractGenericType(method)))
                                            .onErrorResume(this::handleError))
                    .onErrorResume(this::handleError);

        } else {
            // 同步方法：包装在 Mono.fromCallable 中
            return Mono.fromCallable(
                            () -> {
                                method.setAccessible(true);
                                Object[] args =
                                        convertParameters(
                                                method, input, agent, runtimeContext, emitter);
                                Object result = method.invoke(toolObject, args);
                                return converter.convert(result, method.getGenericReturnType());
                            })
                    .onErrorResume(this::handleError);
        }
    }

    /**
     * 将输入参数转换为方法实参，支持自动注入。
     *
     * <p>自动注入框架管理的对象：
     * <ul>
     * <li>{@link ToolEmitter}：流式输出发射器</li>
     * <li>{@link Agent}：当前智能体实例</li>
     * <li>{@link RuntimeContext}：逐调用运行时上下文</li>
     * <li>{@link ToolExecutionContext}：业务上下文（已弃用）</li>
     * <li>自定义 POJO：从 RuntimeContext 按类型获取</li>
     * </ul>
     *
     * <p>未带 {@link ToolParam} 注解的参数视为自动注入类型；带 {@link ToolParam} 的参数从输入映射转换。
     *
     * @param method 方法
     * @param input 输入映射
     * @param agent 用于 Agent 注入的智能体（可为 null）
     * @param runtimeContext 用于注入的运行时上下文（可为 null）
     * @param emitter 用于 ToolEmitter 注入的发射器（可为 null）
     * @return 转换后的实参数组
     */
    private Object[] convertParameters(
            Method method,
            Map<String, Object> input,
            Agent agent,
            RuntimeContext runtimeContext,
            ToolEmitter emitter) {
        Parameter[] parameters = method.getParameters();

        if (parameters.length == 0) {
            return new Object[0];
        }

        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];

            // 特殊处理：自动注入 ToolEmitter
            if (param.getType() == ToolEmitter.class) {
                args[i] = emitter;
            }
            // 特殊处理：自动注入 Agent
            else if (param.getType() == Agent.class) {
                args[i] = agent;
            }
            // 特殊处理：注入 AgentState（匹配 @Tool(stateInjected=true)）。优先使用
            // RuntimeContext 携带的调用级状态（并发安全），仅当上下文
            // 未携带状态时回退到智能体当前状态。
            else if (param.getType() == AgentState.class) {
                AgentState rcState = runtimeContext != null ? runtimeContext.getAgentState() : null;
                args[i] =
                        rcState != null ? rcState : (agent != null ? agent.getAgentState() : null);
            }
            // 直接注入 RuntimeContext
            else if (param.getType() == RuntimeContext.class) {
                args[i] = runtimeContext;
            }
            // 已弃用：注入 ToolExecutionContext（从 RuntimeContext 桥接）
            else if (param.getType() == ToolExecutionContext.class) {
                args[i] = runtimeContext != null ? runtimeContext.asToolExecutionContext() : null;
            }
            // 用户定义 POJO：尝试从 RuntimeContext 解析
            else if (isUserContextPojo(param)) {
                args[i] = resolveContextParameter(param, runtimeContext);
            } else {
                args[i] = convertSingleParameter(param, input);
            }
        }

        return args;
    }

    /**
     * 检查参数是否为应从 ToolExecutionContext 解析的用户定义上下文 POJO。
     *
     * <p>注意：假定 ToolEmitter、Agent、RuntimeContext 和 ToolExecutionContext 已在调用处过滤。
     *
     * <p>满足以下条件时视为用户上下文 POJO：
     * <ul>
     * <li>没有 @ToolParam 注解（不是来自模型的工具输入）</li>
     * <li>不是基本类型（基本类型必须为工具输入）</li>
     * <li>不是框架消息类型（ContentBlock 子类、Msg 等）</li>
     * <li>不是常见 Java 库类型（String、List、Map 等）</li>
     * </ul>
     *
     * @param param 待检查参数
     * @return 应作为用户 POJO 从上下文解析时为 true
     */
    private boolean isUserContextPojo(Parameter param) {
        // 1. 显式标记 @ToolParam：来自模型的工具输入
        if (param.getAnnotation(ToolParam.class) != null) {
            return false;
        }

        Class<?> type = param.getType();

        // 2. 基本类型：必须为工具输入
        if (type.isPrimitive()) {
            return false;
        }

        // 3. 框架消息类型（ContentBlock、Msg 等）：不是用户 POJO
        // 按类继承层次而非包名检查，避免排除测试类
        try {
            if (ContentBlock.class.isAssignableFrom(type) || type == Msg.class) {
                return false;
            }
        } catch (Exception e) {
            // 类加载失败时继续
        }

        String packageName = type.getPackage() != null ? type.getPackage().getName() : "";

        // 4. Java 标准库类型：通常为工具输入
        if (packageName.startsWith("java.") || packageName.startsWith("javax.")) {
            return false;
        }

        // 5. 其他类型：用户定义上下文 POJO
        return true;
    }

    /**
     * 从 RuntimeContext 解析上下文参数。
     *
     * @param param 待解析参数
     * @param runtimeContext 运行时上下文（可为 null）
     * @return 解析后的参数值；失败时为 null
     */
    private Object resolveContextParameter(Parameter param, RuntimeContext runtimeContext) {
        Class<?> targetType = param.getType();

        if (runtimeContext != null) {
            Object value = runtimeContext.get(targetType);
            if (value != null) {
                return value;
            }
            ToolExecutionContext tec = runtimeContext.asToolExecutionContext();
            if (tec != null) {
                return tec.get(targetType);
            }
        }

        return null;
    }

    /**
     * 从输入映射转换单个参数。
     *
     * @param parameter 待转换参数
     * @param input 输入映射
     * @return 转换后的参数值
     */
    private Object convertSingleParameter(Parameter parameter, Map<String, Object> input) {
        // 先检查 @ToolParam 注解，获取显式参数名
        String paramName = parameter.getName(); // 回退到反射参数名
        ToolParam toolParamAnnotation = parameter.getAnnotation(ToolParam.class);
        if (toolParamAnnotation != null && !toolParamAnnotation.name().isEmpty()) {
            paramName = toolParamAnnotation.name();
        }

        Object value = input.get(paramName);

        if (value == null) {
            return null;
        }

        Class<?> rawType = parameter.getType();
        Type paramType = parameter.getParameterizedType();

        // 只有满足以下条件才能直接赋值：
        // 1. 原始类型匹配，并且
        // 2. 参数不是参数化类型（无需保留泛型信息）
        if (rawType.isAssignableFrom(value.getClass())
                && !(paramType instanceof ParameterizedType)) {
            return value;
        }

        // 使用携带完整类型信息的 JsonCodec 转换，以保留泛型。
        try {
            return JsonUtils.getJsonCodec().convertValue(value, paramType);
        } catch (Exception e) {
            // 基本类型回退到基于字符串的转换
            return convertFromString(value.toString(), rawType);
        }
    }

    /**
     * 将字符串值转换为目标类型（基本类型的回退方案）。
     *
     * @param stringValue 字符串值
     * @param targetType 目标类型
     * @return 转换后的值
     */
    private Object convertFromString(String stringValue, Class<?> targetType) {
        if (targetType == Integer.class || targetType == int.class) {
            return Integer.parseInt(stringValue);
        } else if (targetType == Long.class || targetType == long.class) {
            return Long.parseLong(stringValue);
        } else if (targetType == Double.class || targetType == double.class) {
            return Double.parseDouble(stringValue);
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.parseBoolean(stringValue);
        } else if (targetType == Float.class || targetType == float.class) {
            return Float.parseFloat(stringValue);
        }
        return stringValue;
    }

    /**
     * 供 {@code onErrorResume} 使用的响应式错误处理器。
     *
     * <p>委托给 {@link #handleInvocationError(Throwable)}，并将结果包装为 {@code Mono.just(...)}。
     *
     * @param e 响应式链中的错误
     * @return 包含错误信息 ToolResultBlock 的 Mono
     * @throws ToolSuspendException 当异常链中存在此异常时
     */
    private Mono<ToolResultBlock> handleError(Throwable e) {
        return Mono.just(handleInvocationError(e));
    }

    /**
     * 使用有用的错误消息处理调用错误。
     *
     * <p>特殊处理 {@link ToolSuspendException}：若异常链中存在，则重新抛出，以便 {@link ToolExecutor} 正确处理挂起。
     *
     * @param e 异常
     * @return 包含错误消息的 ToolResultBlock
     * @throws ToolSuspendException 当异常链中存在此异常时
     */
    private ToolResultBlock handleInvocationError(Throwable e) {
        // 检查异常本身是否为 ToolSuspendException
        if (e instanceof ToolSuspendException) {
            throw (ToolSuspendException) e;
        }

        Throwable cause = e.getCause();
        // 在异常链中查找 ToolSuspendException
        if (cause instanceof ToolSuspendException) {
            throw (ToolSuspendException) cause;
        }

        String errorMsg =
                cause != null
                        ? ExceptionUtils.getErrorMessage(cause)
                        : ExceptionUtils.getErrorMessage(e);
        return ToolResultBlock.error("Tool execution failed: " + errorMsg);
    }

    /**
     * 从方法返回类型提取泛型类型（用于 CompletableFuture<T> 或 Mono<T>）。
     *
     * @param method 方法
     * @return 泛型类型；未找到时为 null
     */
    private Type extractGenericType(Method method) {
        Type genericReturnType = method.getGenericReturnType();
        if (genericReturnType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) genericReturnType;
            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
            if (actualTypeArguments.length > 0) {
                return actualTypeArguments[0];
            }
        }
        return null;
    }
}
