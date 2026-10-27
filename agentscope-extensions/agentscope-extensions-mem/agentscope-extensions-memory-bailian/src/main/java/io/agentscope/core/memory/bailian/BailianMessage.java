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
package io.agentscope.core.memory.bailian;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 百炼记忆 API 格式的消息。
 *
 * <p>用于添加记忆和检索相关记忆，每条消息具有 user 或 assistant 角色及内容。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BailianMessage {

    /** 发送者角色，"user" 或 "assistant"。 */
    private String role;

    /** 消息的实际文本内容。 */
    private String content;

    /** 供 Jackson 反序列化使用的默认构造器。 */
    public BailianMessage() {}

    /**
     * 使用指定角色与内容创建 BailianMessage。
     *
     * @param role 角色，例如 "user"、"assistant"
     * @param content 消息内容
     */
    public BailianMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 获取角色。
     *
     * @return 角色
     */
    public String getRole() {
        return role;
    }

    /**
     * 设置角色。
     *
     * @param role 角色
     */
    public void setRole(String role) {
        this.role = role;
    }

    /**
     * 获取内容。
     *
     * @return 内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 设置内容。
     *
     * @param content 内容
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * 创建 BailianMessage 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** BailianMessage 的构建器。 */
    public static class Builder {
        private String role;
        private String content;

        /**
         * 设置角色。
         *
         * @param role 角色
         * @return 当前构建器
         */
        public Builder role(String role) {
            this.role = role;
            return this;
        }

        /**
         * 设置内容。
         *
         * @param content 内容
         * @return 当前构建器
         */
        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /**
         * 构建 BailianMessage 实例。
         *
         * @return 新的 BailianMessage 实例
         */
        public BailianMessage build() {
            return new BailianMessage(role, content);
        }
    }
}
