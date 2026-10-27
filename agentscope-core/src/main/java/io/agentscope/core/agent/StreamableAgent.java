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
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 支持在执行期间流式发送事件的智能体接口。
 *
 * <p>智能体处理输入时，此接口允许实时发送执行事件，包括推理步骤、工具结果和最终输出。
 *
 * <p>流式处理适用于：
 * <ul>
 * <li>向用户展示增量进度</li>
 * <li>实时监测智能体推理</li>
 * <li>构建交互式聊天界面</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用，将在未来的次要版本中移除。此接口所有 {@code stream(...)} 方法均返回
 * 粗粒度的 {@link Event} 类型，这是 v1 的遗留接口。请改用 {@code ReActAgent#streamEvents(...)}，
 * 它返回 {@code Flux<io.agentscope.core.event.AgentEvent>}，提供覆盖完整智能体生命周期的细粒度事件层次
 * （包括 HITL 在内的 28 种类型化事件），并将成为唯一的流式接口。迁移详情见 v2 更新日志 B.4 节。
 */
@Deprecated(since = "2.0.0", forRemoval = true)
public interface StreamableAgent {

    /**
     * 基于当前状态流式发送执行事件，不添加新输入。
     *
     * @param options 流式配置选项
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(...)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    default Flux<Event> stream(StreamOptions options) {
        return stream(List.of(), options);
    }

    /**
     * 基于当前状态流式发送执行事件，并支持结构化输出。
     *
     * @param structuredModel 定义输出结构的类
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(...)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    default Flux<Event> stream(Class<?> structuredModel) {
        return stream(List.of(), StreamOptions.defaults(), structuredModel);
    }

    /**
     * 基于当前状态流式发送执行事件，并支持结构化输出。
     *
     * @param options 流式配置选项
     * @param structuredModel 定义输出结构的类
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(...)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    default Flux<Event> stream(StreamOptions options, Class<?> structuredModel) {
        return stream(List.of(), options, structuredModel);
    }

    /**
     * 使用默认选项为单条消息流式发送执行事件。
     *
     * @param msg 输入消息
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(Msg)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    default Flux<Event> stream(Msg msg) {
        return stream(msg, StreamOptions.defaults());
    }

    /**
     * 为单条消息流式发送执行事件。
     *
     * @param msg 输入消息
     * @param options 流式配置选项
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(Msg)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    default Flux<Event> stream(Msg msg, StreamOptions options) {
        return stream(List.of(msg), options);
    }

    /**
     * 为单条消息流式发送执行事件，并支持结构化输出。
     *
     * @param msg 输入消息
     * @param options 流式配置选项
     * @param structuredModel 定义输出结构的类
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(...)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    default Flux<Event> stream(Msg msg, StreamOptions options, Class<?> structuredModel) {
        return stream(List.of(msg), options, structuredModel);
    }

    /**
     * 为单条消息流式发送执行事件，并支持 JSON schema。
     *
     * @param msg 输入消息
     * @param options 流式配置选项
     * @param schema 定义结构的 JSON schema
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(...)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    default Flux<Event> stream(Msg msg, StreamOptions options, JsonNode schema) {
        return stream(List.of(msg), options, schema);
    }

    /**
     * 使用默认选项为多条消息流式发送执行事件。
     *
     * @param msgs 输入消息列表
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(List)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    default Flux<Event> stream(List<Msg> msgs) {
        return stream(msgs, StreamOptions.defaults());
    }

    /**
     * 在智能体处理输入时实时流式发送执行事件。
     *
     * @param msgs 输入消息列表
     * @param options 流式配置选项
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(List)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    Flux<Event> stream(List<Msg> msgs, StreamOptions options);

    /**
     * 使用调用方为本次调用提供的运行时上下文，流式发送执行事件。
     *
     * <p>不使用 {@link RuntimeContext} 的实现可以依靠此默认桥接，继续使用原来的双参数 stream 方法。
     *
     * @param msgs 输入消息列表
     * @param options 流式配置选项
     * @param context 本次调用的运行时元数据
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(...)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    default Flux<Event> stream(List<Msg> msgs, StreamOptions options, RuntimeContext context) {
        return stream(msgs, options);
    }

    /**
     * 流式发送执行事件，并支持结构化输出。
     *
     * @param msgs 输入消息列表
     * @param options 流式配置选项
     * @param structuredModel 定义输出结构的类
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(...)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel);

    /**
     * 流式发送执行事件，并支持 JSON schema。
     *
     * @param msgs 输入消息列表
     * @param options 流式配置选项
     * @param schema 定义结构的 JSON schema
     * @return 执行期间发出的事件 Flux
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@code ReActAgent#streamEvents(...)} 获取细粒度的 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema);
}
