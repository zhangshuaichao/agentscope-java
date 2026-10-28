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

package io.agentscope.extensions.judge.jev;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 是/否问题，其答案为回答“是”的概率。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NoulQuestion(Object instructions, NoulCriteria criteria) implements Question {

    /**
     * 对“是”和“否”含义的可选描述。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NoulCriteria(
            @JsonProperty("true") Object trueValue, @JsonProperty("false") Object falseValue) {}
}
