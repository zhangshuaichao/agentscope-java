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
package io.agentscope.core.memory.reme;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * ReMe API 格式的轨迹，即对话序列。
 *
 * <p>轨迹包含形成对话序列的消息列表，用于向 ReMe 记录记忆。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeTrajectory {

    /** 此轨迹的消息列表。 */
    private List<ReMeMessage> messages;

    /** 供 Jackson 反序列化使用的默认构造器。 */
    public ReMeTrajectory() {}

    /**
     * 使用指定消息创建 ReMeTrajectory。
     *
     * @param messages 消息列表
     */
    public ReMeTrajectory(List<ReMeMessage> messages) {
        this.messages = messages;
    }

    // 读取与设置方法。

    public List<ReMeMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ReMeMessage> messages) {
        this.messages = messages;
    }

    /**
     * 创建 ReMeTrajectory 实例的构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** ReMeTrajectory 的构建器类。 */
    public static class Builder {
        private List<ReMeMessage> messages;

        public Builder messages(List<ReMeMessage> messages) {
            this.messages = messages;
            return this;
        }

        public ReMeTrajectory build() {
            return new ReMeTrajectory(messages);
        }
    }
}
