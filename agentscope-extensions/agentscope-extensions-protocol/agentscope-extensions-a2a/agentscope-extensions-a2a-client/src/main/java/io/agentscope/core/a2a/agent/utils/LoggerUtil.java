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

package io.agentscope.core.a2a.agent.utils;

import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.a2aproject.sdk.client.ClientEvent;
import org.slf4j.Logger;

/**
 * A2A 智能体日志工具。
 */
public class LoggerUtil {

    /**
     * 记录智能体输出的 AgentScope 事件详情。
     *
     * @param logger 记录日志的 Logger 实例
     * @param event 待记录的 AgentScope 智能体事件对象
     */
    public static void logAgentEventDetail(Logger logger, Event event) {
        if (logger.isDebugEnabled()) {
            debug(logger, "Event: {}", event);
            logTextMsgDetail(logger, List.of(event.getMessage()));
        }
    }

    /**
     * 记录 A2A 客户端事件详情。
     *
     * @param logger 记录日志的 Logger 实例
     * @param event 待记录的客户端事件对象
     */
    public static void logA2aClientEventDetail(Logger logger, ClientEvent event) {
        if (logger.isTraceEnabled()) {
            try {
                String eventDetail = String.valueOf(event);
                trace(logger, "\t {}", eventDetail);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 记录消息列表中的文本内容详情。
     *
     * @param logger 记录日志的 Logger 实例
     * @param msgs 包含待记录内容的消息对象列表
     */
    public static void logTextMsgDetail(Logger logger, List<Msg> msgs) {
        if (logger.isDebugEnabled()) {
            msgs.stream()
                    .map(Msg::getContent)
                    .forEach(
                            contents ->
                                    debug(
                                            logger,
                                            "\t {}",
                                            contents.stream()
                                                    .filter(content -> content instanceof TextBlock)
                                                    .toList()));
        }
    }

    /**
     * 记录 TRACE 级别日志。
     * <p>先检查日志器是否启用 TRACE 级别，再记录格式化日志。
     * 未启用时避免不必要的字符串格式化操作，提高性能。
     * </p>
     *
     * @param logger 执行实际日志记录的日志器实例
     * @param format 遵循 SLF4J 规范的日志格式字符串
     * @param args 替换格式字符串占位符的参数数组
     */
    public static void trace(Logger logger, String format, Object... args) {
        if (logger.isTraceEnabled()) {
            logger.trace(format, args);
        }
    }

    /**
     * 记录 DEBUG 级别日志。
     * <p>先检查日志器是否启用 DEBUG 级别，再记录格式化日志。
     * 未启用时避免不必要的字符串格式化操作，提高性能。
     * </p>
     *
     * @param logger 执行实际日志记录的日志器实例
     * @param format 遵循 SLF4J 规范的日志格式字符串
     * @param args 替换格式字符串占位符的参数数组
     */
    public static void debug(Logger logger, String format, Object... args) {
        if (logger.isDebugEnabled()) {
            logger.debug(format, args);
        }
    }

    /**
     * 记录 INFO 级别日志。
     * <p>先检查日志器是否启用 INFO 级别，再记录格式化日志。
     * 未启用时避免不必要的字符串格式化操作，提高性能。
     * </p>
     *
     * @param logger 执行实际日志记录的日志器实例
     * @param format 遵循 SLF4J 规范的日志格式字符串
     * @param args 替换格式字符串占位符的参数数组
     */
    public static void info(Logger logger, String format, Object... args) {
        if (logger.isInfoEnabled()) {
            logger.info(format, args);
        }
    }

    /**
     * 记录 WARN 级别日志。
     * <p>先检查日志器是否启用 WARN 级别，再记录格式化日志。
     * 未启用时避免不必要的字符串格式化操作，提高性能。
     * </p>
     *
     * @param logger 执行实际日志记录的日志器实例
     * @param format 遵循 SLF4J 规范的日志格式字符串
     * @param args 替换格式字符串占位符的参数数组
     */
    public static void warn(Logger logger, String format, Object... args) {
        if (logger.isWarnEnabled()) {
            logger.warn(format, args);
        }
    }

    /**
     * 记录 ERROR 级别日志。
     * <p>先检查日志器是否启用 ERROR 级别，再记录格式化日志。
     * 未启用时避免不必要的字符串格式化操作，提高性能。
     * </p>
     *
     * @param logger 执行实际日志记录的日志器实例
     * @param format 遵循 SLF4J 规范的日志格式字符串
     * @param args 替换格式字符串占位符的参数数组
     */
    public static void error(Logger logger, String format, Object... args) {
        if (logger.isErrorEnabled()) {
            logger.error(format, args);
        }
    }
}
