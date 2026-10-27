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
package io.agentscope.extensions.model.anthropic.formatter;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import java.time.Instant;
import java.util.List;

/**
 * Anthropic Messages API 的格式化器，在 AgentScope Msg 与 Anthropic SDK 类型间转换。
 *
 * <p>重要协议要求：
 * <ul><li>系统消息通过独立 system 参数处理，而非普通消息列表角色。</li>
 * <li>工具结果必须放在独立的用户消息中。</li><li>原生支持扩展思考内容块。</li></ul>
 */
public class AnthropicChatFormatter extends AnthropicBaseFormatter {

    @Override
    public List<MessageParam> doFormat(List<Msg> msgs) {
        return messageConverter.convert(msgs);
    }

    @Override
    public ChatResponse parseResponse(Object response, Instant startTime) {
        if (response instanceof Message message) {
            return AnthropicResponseParser.parseMessage(message, startTime);
        } else {
            throw new IllegalArgumentException("Unsupported response type: " + response.getClass());
        }
    }
}
