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
package io.agentscope.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 能够被调用以处理消息的智能体接口。
 *
 * <p>定义智能体的核心调用能力，包括：
 * <ul>
 * <li>通过 {@link #call(List)} 处理基本消息</li>
 * <li>通过 {@link #call(List, Class)} 和 {@link #call(List, JsonNode)} 生成结构化输出</li>
 * </ul>
 *
 * <p>便捷方法提供默认实现，并委托给核心 {@link #call(List)} 方法。
 */
public interface CallableAgent {

    /**
     * 基于当前状态继续生成，不添加新输入。
     *
     * @return 回复消息
     */
    default Mono<Msg> call() {
        return call(List.of());
    }

    /**
     * 基于当前状态，使用 JSON schema 继续生成。
     *
     * @param schema 定义结构的 JSON schema
     * @return 在元数据中携带结构化数据的回复消息
     */
    default Mono<Msg> call(JsonNode schema) {
        return call(List.of(), schema);
    }

    /**
     * 基于当前状态，使用结构化模型继续生成。
     *
     * @param structuredModel 定义结构的类
     * @return 在元数据中携带结构化数据的回复消息
     */
    default Mono<Msg> call(Class<?> structuredModel) {
        return call(List.of(), structuredModel);
    }

    /**
     * 处理单条输入消息并生成回复。
     *
     * @param msg 输入消息
     * @return 回复消息
     */
    default Mono<Msg> call(Msg msg) {
        return call(msg == null ? List.of() : List.of(msg));
    }

    /**
     * 使用结构化模型处理单条输入消息并生成回复。
     *
     * @param msg 输入消息
     * @param structuredModel 定义结构的类
     * @return 在元数据中携带结构化数据的回复消息
     */
    default Mono<Msg> call(Msg msg, Class<?> structuredModel) {
        return call(msg == null ? List.of() : List.of(msg), structuredModel);
    }

    /**
     * 使用 JSON schema 处理单条输入消息并生成回复。
     *
     * @param msg 输入消息
     * @param schema 定义结构的 JSON schema
     * @return 在元数据中携带结构化数据的回复消息
     */
    default Mono<Msg> call(Msg msg, JsonNode schema) {
        return call(msg == null ? List.of() : List.of(msg), schema);
    }

    /**
     * 处理纯文本输入并生成回复。
     *
     * @param text 输入文本（包装为 {@link UserMessage}）
     * @return 回复消息
     */
    default Mono<Msg> call(String text) {
        return call(new UserMessage(text));
    }

    /**
     * 使用结构化模型处理纯文本输入并生成回复。
     *
     * @param text 输入文本（包装为 {@link UserMessage}）
     * @param structuredModel 定义结构的类
     * @return 在元数据中携带结构化数据的回复消息
     */
    default Mono<Msg> call(String text, Class<?> structuredModel) {
        return call(new UserMessage(text), structuredModel);
    }

    /**
     * 使用 JSON schema 处理纯文本输入并生成回复。
     *
     * @param text 输入文本（包装为 {@link UserMessage}）
     * @param schema 定义结构的 JSON schema
     * @return 在元数据中携带结构化数据的回复消息
     */
    default Mono<Msg> call(String text, JsonNode schema) {
        return call(new UserMessage(text), schema);
    }

    /**
     * 处理多条输入消息（可变参数）并生成回复。
     *
     * @param msgs 输入消息（可变参数）
     * @return 回复消息
     */
    default Mono<Msg> call(Msg... msgs) {
        return call(List.of(msgs));
    }

    /**
     * 处理输入消息列表并生成回复。
     *
     * @param msgs 输入消息列表
     * @return 回复消息
     */
    Mono<Msg> call(List<Msg> msgs);

    /**
     * 使用结构化模型处理多条输入消息并生成回复。
     *
     * <p>结构化模型参数定义输出数据的预期结构，结构化数据将存入返回消息的元数据字段。
     *
     * @param msgs 输入消息列表
     * @param structuredModel 定义结构的类
     * @return 在元数据中携带结构化数据的回复消息
     */
    Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel);

    /**
     * 使用 JSON schema 处理多条输入消息并生成回复。
     *
     * <p>schema 参数定义输出数据的预期结构，结构化数据将存入返回消息的元数据字段。
     *
     * @param msgs 输入消息列表
     * @param schema 定义结构的 JSON schema
     * @return 在元数据中携带结构化数据的回复消息
     */
    Mono<Msg> call(List<Msg> msgs, JsonNode schema);
}
