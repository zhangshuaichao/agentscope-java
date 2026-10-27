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
package io.agentscope.core.agui.runtime;

import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.util.JsonUtils;
import java.util.Objects;

/**
 * 使用 AgentScope JSON 编解码器解析 AG-UI 请求体。
 *
 * <p>AgentScope 默认编解码器为 Jackson 2。将转换保留在 AG-UI 边界，可避免为带 Jackson 2 注解的 AG-UI 模型选择 Spring Boot 4 的 Jackson 3 编解码器，同时不改变应用的其他 HTTP 转换器。
 *
 * <p>Spring Boot starter 将此类型暴露为 bean，并通过 {@code @ConditionalOnMissingBean} 提供默认实例。应用可注册自己的 {@code AguiRequestBodyParser} bean 以定制请求体转换。
 */
public class AguiRequestBodyParser {

    /**
     * 将 JSON 请求体解析为 {@link RunAgentInput}。
     *
     * @param body 原始 JSON 请求体
     * @return 解析后的 AG-UI 输入
     */
    public RunAgentInput parse(String body) {
        Objects.requireNonNull(body, "body cannot be null");
        return JsonUtils.getJsonCodec().fromJson(body, RunAgentInput.class);
    }
}
