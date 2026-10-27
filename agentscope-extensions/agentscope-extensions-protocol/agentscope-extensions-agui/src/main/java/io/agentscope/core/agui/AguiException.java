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
package io.agentscope.core.agui;

/**
 * AG-UI 相关错误的异常基类。
 */
public class AguiException extends RuntimeException {

    /**
     * 根据指定消息创建 AguiException。
     *
     * @param message 错误消息
     */
    public AguiException(String message) {
        super(message);
    }

    /**
     * 根据指定消息和原因创建 AguiException。
     *
     * @param message 错误消息
     * @param cause 底层原因
     */
    public AguiException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 在注册表中找不到智能体时抛出的异常。
     */
    public static class AgentNotFoundException extends AguiException {

        /**
         * 创建 AgentNotFoundException。
         *
         * @param agentId 未找到的智能体 ID
         */
        public AgentNotFoundException(String agentId) {
            super("Agent not found: " + agentId);
        }
    }

    /**
     * 事件编码失败时抛出的异常。
     */
    public static class EncodingException extends AguiException {

        /**
         * 创建 EncodingException。
         *
         * @param message 错误消息
         * @param cause 底层原因
         */
        public EncodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
