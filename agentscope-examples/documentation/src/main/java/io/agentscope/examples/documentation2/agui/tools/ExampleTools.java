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
package io.agentscope.examples.documentation2.agui.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;

/**
 * AG-UI 演示工具，展示如何创建智能体在对话中可调用的工具函数。
 */
public class ExampleTools {

    private final Random random = new Random();

    /**
     * 获取城市天气。
     * <p>注意：仅为演示的模拟实现。
     * @param city 要查询天气的城市名
     * @return 天气信息
     */
    @Tool(name = "get_weather", description = "Get current weather information for a city")
    public ToolResultBlock getWeather(
            @ToolParam(name = "city", description = "The city name (e.g., 'Beijing', 'New York')")
                    String city,
            ToolEmitter emitter) {
        // 模拟天气数据
        String[] conditions = {"Sunny", "Cloudy", "Partly Cloudy", "Rainy", "Overcast"};
        String condition = conditions[random.nextInt(conditions.length)];
        int temperature = random.nextInt(35) + 5; // 5 至 40 度
        int humidity = random.nextInt(60) + 30; // 30% 至 90%

        // 在元数据中传递进度信息
        // 要使其生效，必须自定义 ToolResultBlockConverter，覆盖
        // 框架默认实现
        emitter.emit(
                ToolResultBlock.builder()
                        .name("get_weather")
                        .metadata(Map.of("progress", "50%"))
                        .build());

        String result =
                String.format(
                        "Weather in %s:\n- Condition: %s\n- Temperature: %d°C\n- Humidity: %d%%",
                        city, condition, temperature, humidity);

        emitter.emit(
                ToolResultBlock.builder()
                        .name("get_weather")
                        .metadata(Map.of("progress", "100%"))
                        .build());
        return ToolResultBlock.text(result);
    }

    /**
     * 执行简单计算。
     * @param expression 待求值的数学表达式
     * @return 计算结果
     */
    @Tool(
            name = "calculate",
            description = "Perform a simple arithmetic calculation (supports +, -, *, /)")
    public ToolResultBlock calculate(
            @ToolParam(
                            name = "expression",
                            description =
                                    "The arithmetic expression to calculate (e.g., '2 + 3 * 4')")
                    String expression) {
        try {
            // 简单表达式求值器，仅用于演示
            double result = evaluateExpression(expression);
            return ToolResultBlock.text("Result: " + formatNumber(result));
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to calculate: " + e.getMessage());
        }
    }

    /**
     * 获取当前日期与时间。
     * @return 当前日期和时间
     */
    @Tool(name = "get_current_time", description = "Get the current date and time")
    public ToolResultBlock getCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return ToolResultBlock.text("Current time: " + now.format(formatter));
    }

    /**
     * 简单表达式求值器，支持基本四则运算：+、-、*、/。
     */
    private double evaluateExpression(String expression) {
        // 移除空白
        expression = expression.replaceAll("\\s+", "");

        // 处理最低优先级的加减运算
        int lastPlusOrMinus = -1;
        int parenDepth = 0;
        for (int i = expression.length() - 1; i >= 0; i--) {
            char c = expression.charAt(i);
            if (c == ')') parenDepth++;
            else if (c == '(') parenDepth--;
            else if (parenDepth == 0 && (c == '+' || c == '-') && i > 0) {
                lastPlusOrMinus = i;
                break;
            }
        }

        if (lastPlusOrMinus > 0) {
            String left = expression.substring(0, lastPlusOrMinus);
            String right = expression.substring(lastPlusOrMinus + 1);
            char op = expression.charAt(lastPlusOrMinus);
            if (op == '+') {
                return evaluateExpression(left) + evaluateExpression(right);
            } else {
                return evaluateExpression(left) - evaluateExpression(right);
            }
        }

        // 处理乘除运算
        int lastMultOrDiv = -1;
        parenDepth = 0;
        for (int i = expression.length() - 1; i >= 0; i--) {
            char c = expression.charAt(i);
            if (c == ')') parenDepth++;
            else if (c == '(') parenDepth--;
            else if (parenDepth == 0 && (c == '*' || c == '/')) {
                lastMultOrDiv = i;
                break;
            }
        }

        if (lastMultOrDiv > 0) {
            String left = expression.substring(0, lastMultOrDiv);
            String right = expression.substring(lastMultOrDiv + 1);
            char op = expression.charAt(lastMultOrDiv);
            if (op == '*') {
                return evaluateExpression(left) * evaluateExpression(right);
            } else {
                return evaluateExpression(left) / evaluateExpression(right);
            }
        }

        // 处理括号
        if (expression.startsWith("(") && expression.endsWith(")")) {
            return evaluateExpression(expression.substring(1, expression.length() - 1));
        }

        // 解析数字
        return parseNumber(expression);
    }

    private double parseNumber(String expression) {
        try {
            return Double.parseDouble(expression);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid numeric value in expression: '" + expression + "'", e);
        }
    }

    private String formatNumber(double value) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.format("%.4f", value);
    }
}
