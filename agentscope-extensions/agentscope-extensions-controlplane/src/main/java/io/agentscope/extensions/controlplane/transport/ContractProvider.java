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
package io.agentscope.extensions.controlplane.transport;

import java.util.List;
import java.util.Map;

/**
 * 由桥接器实现的 {@code /agentscope/*} HTTP 契约底层数据接口。
 *
 * <p>每个方法返回可序列化为 JSON 的映射和列表。{@link NotFoundException} 映射为 404，{@link UnsupportedOperationException} 映射为 501，其余异常变为 500。
 */
public interface ContractProvider {

    Map<String, Object> info();

    List<Map<String, Object>> sessions();

    Map<String, Object> sessionState(String sessionId);

    Map<String, Object> context(String sessionId);

    Map<String, Object> messages(String sessionId, int offset, int limit);

    List<Map<String, Object>> subagents();

    List<Map<String, Object>> workspaces();

    void compress(String sessionId);

    void terminate(String sessionId);

    /**
 * 中止当前轮次但不终止会话，能力为 {@code session-abort}。
 */
    void abort(String sessionId);

    /**
     * 会话任务清单，能力为 {@code task-query}，返回包含 {@code tasks} 列表的映射。
     */
    Map<String, Object> tasks(String sessionId);

    /**
     * 后台子智能体任务（不是待办列表），能力为 {@code subagent-task-query}。
     */
    default Map<String, Object> subagentTasks(String sessionId) {
        throw new UnsupportedOperationException("subagent-task-query is not supported");
    }

    /**
 * 取消后台子智能体任务，能力为 {@code subagent-task-command}。
 */
    default void cancelSubagentTask(String sessionId, String taskId) {
        throw new UnsupportedOperationException("subagent-task-command is not supported");
    }

    /**
 * 进入／退出计划模式，正文为 JSON {@code {"active":true|false}}，能力为 {@code plan-mode}。
 */
    default void planMode(String sessionId, byte[] body) {
        throw new UnsupportedOperationException("plan-mode is not supported");
    }

    /**
     * 向活动会话注入用户消息，正文为 JSON {@code {"content":"..."}}。忙碌会话应抛出 {@link BusyException}。
     */
    default void postMessage(String sessionId, byte[] body) {
        throw new UnsupportedOperationException("inbound messages are not supported");
    }

    /**
 * 可选对话记录导出，能力为 {@code export-transcript}。
 */
    default Map<String, Object> exportTranscript(String sessionId) {
        throw new UnsupportedOperationException("export-transcript is not supported");
    }

    /**
 * 命令成功响应所用的当前阶段字符串。
 */
    default String sessionPhase(String sessionId) {
        return "running";
    }

    /**
 * 表示请求的会话或资源在此实例不存在。
 */
    class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /**
 * 表示会话忙碌，调用方应等待空闲。
 */
    class BusyException extends RuntimeException {
        public BusyException(String message) {
            super(message);
        }
    }

    /**
 * 表示写端点缺少内部令牌或令牌无效。
 */
    class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }
}
