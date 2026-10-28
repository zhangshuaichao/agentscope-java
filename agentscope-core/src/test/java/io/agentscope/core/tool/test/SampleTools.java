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
package io.agentscope.core.tool.test;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

/**
 * 用于测试的示例工具。
 *
 * <p>提供简单实现，测试工具注册、执行与参数验证。
 */
public class SampleTools {

    /**
     * 简单计算器工具：两数相加。
     */
    @Tool(name = "add", description = "Add two numbers together")
    public int add(
            @ToolParam(name = "a", description = "First number") int a,
            @ToolParam(name = "b", description = "Second number") int b) {
        return a + b;
    }

    /**
     * 字符串操作工具：连接字符串。
     */
    @Tool(name = "concat", description = "Concatenate two strings")
    public String concat(
            @ToolParam(name = "str1", description = "First string") String str1,
            @ToolParam(name = "str2", description = "Second string") String str2) {
        return str1 + str2;
    }

    /**
     * 抛异常的工具。
     */
    @Tool(name = "error_tool", description = "A tool that always throws an error")
    public String errorTool(
            @ToolParam(name = "message", description = "Error message") String message) {
        throw new RuntimeException("Tool error: " + message);
    }

    /**
     * 多参数工具。
     */
    @Tool(name = "multi_param", description = "Tool with multiple parameters")
    public String multiParam(
            @ToolParam(name = "str", description = "String parameter") String str,
            @ToolParam(name = "num", description = "Number parameter") int num,
            @ToolParam(name = "flag", description = "Boolean parameter") boolean flag) {
        return String.format("str=%s, num=%d, flag=%s", str, num, flag);
    }

    /**
     * 模拟缓慢执行的工具。
     */
    @Tool(name = "slow_tool", description = "A tool that takes time to execute")
    public String slowTool(
            @ToolParam(name = "delayMs", description = "Delay in milliseconds") int delayMs) {
        try {
            Thread.sleep(delayMs);
            return "Completed after " + delayMs + "ms";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
    }

    /**
     * 无参数工具。
     */
    @Tool(name = "no_param", description = "Tool without parameters")
    public String noParamTool() {
        return "No parameters";
    }

    /**
     * 返回复杂对象的工具。
     */
    @Tool(name = "complex_return", description = "Tool that returns complex data")
    public Object complexReturn(
            @ToolParam(name = "type", description = "Return type") String type) {
        switch (type) {
            case "string":
                return "test string";
            case "number":
                return 42;
            case "boolean":
                return true;
            case "array":
                return new int[] {1, 2, 3};
            default:
                return null;
        }
    }

    /**
     * 使用 CompletableFuture 的异步工具：两数相加。
     */
    @Tool(name = "async_add", description = "Asynchronously add two numbers")
    public CompletableFuture<Integer> asyncAdd(
            @ToolParam(name = "a", description = "First number") int a,
            @ToolParam(name = "b", description = "Second number") int b) {
        return CompletableFuture.supplyAsync(() -> a + b);
    }

    /**
     * 使用 Mono 的异步工具：连接字符串。
     */
    @Tool(name = "async_concat", description = "Asynchronously concatenate two strings")
    public Mono<String> asyncConcat(
            @ToolParam(name = "str1", description = "First string") String str1,
            @ToolParam(name = "str2", description = "Second string") String str2) {
        return Mono.fromCallable(() -> str1 + str2);
    }

    /**
     * 使用 Mono 模拟延迟的异步工具。
     */
    @Tool(name = "async_delayed", description = "Async tool with simulated delay")
    public Mono<String> asyncDelayed(
            @ToolParam(name = "delayMs", description = "Delay in milliseconds") int delayMs) {
        return Mono.delay(Duration.ofMillis(delayMs))
                .map(tick -> "Completed after " + delayMs + "ms");
    }

    /**
     * 抛出错误的异步工具。
     */
    @Tool(name = "async_error", description = "Async tool that fails")
    public CompletableFuture<String> asyncError(
            @ToolParam(name = "message", description = "Error message") String message) {
        CompletableFuture<String> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Async error: " + message));
        return future;
    }
}
