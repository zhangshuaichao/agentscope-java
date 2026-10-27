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

package io.agentscope.core.a2a.server.executor.runner;

/**
 * 每次智能体请求的选项，例如 taskId、sessionId、userId。
 */
public class AgentRequestOptions {

    /**
     * 任务 ID，通常为 UUID。
     */
    private String taskId;

    /**
     * 传给运行器的会话标识符。非空白的旧版消息元数据 {@code sessionId}
     * 优先，否则使用 SDK 解析的 A2A {@code contextId}。null 或空表示没有可用会话标识符。
     */
    private String sessionId;

    /**
     * 用户 ID，null 或空字符串表示未从请求中找到用户 ID。
     */
    private String userId;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
