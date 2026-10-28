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
package io.agentscope.spring.boot.admin.dto;

import io.agentscope.core.message.Msg;

/**
 * 供管理列表使用的 {@link io.agentscope.core.message.Msg} 精简投影，避免通过管理接口暴露内部内容块层级。
 */
public record MessageView(String id, String role, String name, String timestamp, String text) {

    public static MessageView of(Msg msg) {
        String text;
        try {
            text = msg.getTextContent();
        } catch (RuntimeException e) {
            text = "";
        }
        return new MessageView(
                msg.getId(),
                msg.getRole() == null ? null : msg.getRole().name(),
                msg.getName(),
                msg.getTimestamp(),
                text == null ? "" : text);
    }
}
