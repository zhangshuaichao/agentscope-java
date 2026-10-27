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
package io.agentscope.core.credential;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 某服务商下一个候选聊天模型的描述。
 *
 * <p>这是最小占位类型，用作 {@link CredentialBase#listModels()} 的返回值。
 * 随着模型发现基础设施完善，将进一步添加能力标记和参数模式。
 */
public record ModelCard(
        @JsonProperty("model_name") String modelName,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("context_size") Integer contextSize) {

    @JsonCreator
    public ModelCard {}
}
