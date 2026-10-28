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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 具有有序评分准则的问题。
 *
 * <p>每个 criteria 条目描述一个等级。答案包含各等级概率分布及概率加权分数。
 */
public record ScoreQuestion(Object instructions, List<Object> criteria) implements Question {

    public ScoreQuestion {
        criteria =
                criteria == null ? null : Collections.unmodifiableList(new ArrayList<>(criteria));
    }
}
