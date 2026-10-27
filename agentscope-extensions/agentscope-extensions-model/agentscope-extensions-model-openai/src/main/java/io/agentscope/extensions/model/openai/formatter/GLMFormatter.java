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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智谱 GLM 模型（glm-4、glm-3-turbo 等）的格式化器。
 *
 * <p>智谱 GLM API 的特定要求：
 * <ul><li>至少一条用户消息，否则返回错误 1214。</li><li>tool_choice 仅支持 "auto"。</li>
 * <li>工具定义不支持 strict 参数。</li><li>支持 temperature、top_p、max_tokens、seed，不支持频率/出现惩罚。</li></ul>
 *
 * <p>用法：
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new GLMFormatter())
 *     .modelName("glm-4")
 *     .baseUrl("https://open.bigmodel.cn/api/paas/v4/")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @deprecated 请改用 {@link io.agentscope.extensions.model.openai.compat.glm.GLMFormatter}。
 */
@Deprecated
public class GLMFormatter extends OpenAIChatFormatter {

    private static final Logger log = LoggerFactory.getLogger(GLMFormatter.class);

    public GLMFormatter() {
        super();
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> messages = super.doFormat(msgs);
        return ensureUserMessage(messages);
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        applyGLMToolChoice(request, toolChoice);
    }

    /**
     * 确保对话中至少存在一条用户消息。
     *
     * <p>GLM API 要求至少一条用户消息；若不存在，则在末尾添加占位消息。
     *
     * <p>此方法为静态方法，以便与 {@link GLMMultiAgentFormatter} 共享。
     *
     * @param messages 待检查的消息
     * @return 已确保存在用户消息的列表
     */
    static List<OpenAIMessage> ensureUserMessage(List<OpenAIMessage> messages) {
        boolean hasUserMessage = messages.stream().anyMatch(msg -> "user".equals(msg.getRole()));

        if (hasUserMessage) {
            return messages;
        }

        // 没有用户消息时，GLM API 返回错误 1214。
        log.debug("GLM: adding placeholder user message to satisfy API requirement");
        List<OpenAIMessage> result = new ArrayList<>(messages);
        result.add(OpenAIMessage.builder().role("user").content("").build());
        return result;
    }

    /**
     * 应用 GLM 专用工具选择处理。
     *
     * <p>GLM 的 tool_choice 仅支持 "auto"，其他选项均降级为 "auto"。
     *
     * <p>此方法为静态方法，以便与 {@link GLMMultiAgentFormatter} 共享。
     *
     * @param request 要应用工具选择的请求
     * @param toolChoice 请求的工具选择
     */
    static void applyGLMToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }

        if (toolChoice != null && !(toolChoice instanceof ToolChoice.Auto)) {
            log.info(
                    "GLM only supports tool_choice='auto', degrading from '{}'",
                    toolChoice.getClass().getSimpleName());
        }

        request.setToolChoice("auto");
    }
}
