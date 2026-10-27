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
package io.agentscope.core.agui.encoder;

import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;

/**
 * 将 AG-UI 事件编码为服务器发送事件（SSE）格式的编码器。
 *
 * <p>【学习提示】协议事件 JSON 与 SSE 传输帧是两层：直接传输使用完整 data 帧，交给 WebFlux 时只传 JSON 负载，避免双重包装。SSE 保活注释仅维持连接，不是智能体进度或运行成功信号。
 *
 * <p>将 AG-UI 事件序列化为 SSE 传输格式：
 * <pre>
 * data: {"type":"EVENT_TYPE",...}\n\n
 * </pre>
 *
 * <p>编码器线程安全，可由多个请求共享。
 */
public class AguiEventEncoder {

    /**
     * 创建 AguiEventEncoder。
     */
    public AguiEventEncoder() {}

    /**
     * 将 AG-UI 事件编码为 SSE 格式。
     *
     * <p>输出格式：
     * <pre>
     * data: {"type":"EVENT_TYPE",...}\n\n
     * </pre>
     *
     * @param event 要编码的事件
     * @return SSE 格式字符串
     * @throws AguiException.EncodingException 无法序列化事件时抛出
     */
    public String encode(AguiEvent event) {
        try {
            String json = JsonUtils.getJsonCodec().toJson(event);
            return "data: " + json + "\n\n";
        } catch (JsonException e) {
            throw new AguiException.EncodingException("Failed to encode AG-UI event", e);
        }
    }

    /**
     * 仅将 AG-UI 事件编码为 JSON 字符串（不包含 SSE 包装）。
     *
     * <p>注意：返回字符串以空格开头，以兼容要求 SSE 格式为 "data: {...}"（带空格）的 AG-UI 客户端库。Spring WebFlux 生成 "data:" 前缀，因此前导空格使最终格式符合预期。
     *
     * @param event 要编码的事件
     * @return 带前导空格以兼容 SSE 的 JSON 字符串
     * @throws AguiException.EncodingException 无法序列化事件时抛出
     */
    public String encodeToJson(AguiEvent event) {
        try {
            // 添加前导空格以兼容 SSE："data:" + " {...}" = "data: {...}"
            return " " + JsonUtils.getJsonCodec().toJson(event);
        } catch (JsonException e) {
            throw new AguiException.EncodingException("Failed to encode AG-UI event to JSON", e);
        }
    }

    /**
     * 将注释编码为 SSE 格式。
     *
     * <p>注释可作为保活信号：
     * <pre>
     * : comment text\n\n
     * </pre>
     *
     * @param comment 注释文本
     * @return SSE 格式注释
     */
    public String encodeComment(String comment) {
        return ": " + comment + "\n\n";
    }

    /**
     * 生成保活注释。
     *
     * @return SSE 保活注释
     */
    public String keepAlive() {
        return ": keep-alive\n\n";
    }
}
