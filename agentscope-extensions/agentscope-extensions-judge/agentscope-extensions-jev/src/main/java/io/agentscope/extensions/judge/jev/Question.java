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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 发往 TypeSafe System One 模型的类型化问题。
 *
 * <p>问题有意采用封闭世界：调用方声明合法选项或等级，模型返回这些选项上的概率分布。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = NoulQuestion.class, name = "noul"),
    @JsonSubTypes.Type(value = ChoiceQuestion.class, name = "choice"),
    @JsonSubTypes.Type(value = ScoreQuestion.class, name = "score")
})
public sealed interface Question permits NoulQuestion, ChoiceQuestion, ScoreQuestion {}
