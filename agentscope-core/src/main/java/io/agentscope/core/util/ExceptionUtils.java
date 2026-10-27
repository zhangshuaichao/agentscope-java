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
package io.agentscope.core.util;

import java.util.IdentityHashMap;

/**
 * 异常处理辅助方法。
 */
public final class ExceptionUtils {

    private ExceptionUtils() {
        // 禁止实例化
    }

    /**
     * 从异常中提取尽可能有信息量的错误消息。
     *
     * <p>按以下顺序尝试：
     * <ol>
     * <li>异常自身的消息</li>
     * <li>原因异常的消息（自身消息为空时）</li>
     * <li>异常的简单类名（回退）</li>
     * </ol>
     *
     * @param throwable 要提取消息的异常（可以为 null）
     * @return 非 null 的错误消息字符串
     */
    public static String getErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }

        // 尝试获取异常自身的消息
        String message = throwable.getMessage();
        if (message != null && !message.isEmpty()) {
            return message;
        }

        // 没有消息时尝试获取原因异常的消息
        Throwable cause = throwable.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isEmpty()) {
            return cause.getMessage();
        }

        // 回退到异常类名
        return throwable.getClass().getSimpleName();
    }

    /**
     * 判断给定异常或其原因链中的任意异常是否为 {@link InterruptedException}。
     * 遍历原因链时使用按对象身份判重的集合，防止循环原因关系造成死循环。
     *
     * @param error 要检查的异常（可以为 {@code null}）
     * @return 原因链包含 {@link InterruptedException} 时为 {@code true}
     */
    public static boolean containsInterruptedException(Throwable error) {
        // 【学习提示】按对象身份而非 equals 判重，是为了追踪同一个异常实例是否再次出现。
        // 这既允许不同异常具有相同消息，也能截断人为构造的循环 cause 链。
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        Throwable current = error;
        while (current != null && visited.put(current, Boolean.TRUE) == null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
