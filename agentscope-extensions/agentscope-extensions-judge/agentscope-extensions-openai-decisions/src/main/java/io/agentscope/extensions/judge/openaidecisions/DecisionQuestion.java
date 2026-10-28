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

package io.agentscope.extensions.judge.openaidecisions;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 针对共享证据的类型化问题。名称由 {@link DecisionRequest} 提供。
 */
public sealed interface DecisionQuestion
        permits DecisionQuestion.Predicate, DecisionQuestion.Choice, DecisionQuestion.Score {
    String instructions();

    /**
     * 估计指令所描述条件为真的概率。
     */
    record Predicate(String instructions) implements DecisionQuestion {
        public Predicate {
            Objects.requireNonNull(instructions, "instructions");
        }
    }

    /**
     * 从 2–255 个互不相同的字符串或布尔值中选择一个。
     */
    record Choice(String instructions, List<Option> choices) implements DecisionQuestion {
        public Choice {
            Objects.requireNonNull(instructions, "instructions");
            choices = List.copyOf(choices);
            if (choices.size() < 2 || choices.size() > 255) {
                throw new IllegalArgumentException("choices must contain 2–255 options");
            }
            var values = new HashSet<Object>();
            for (Option option : choices) {
                if (!values.add(option.value())) {
                    throw new IllegalArgumentException("choice values must be unique");
                }
            }
        }
    }

    /**
     * 一个选项；拼写相同的字符串与布尔值仍视为不同。
     */
    record Option(Object value, String description) {
        public Option {
            if (!(value instanceof String) && !(value instanceof Boolean)) {
                throw new IllegalArgumentException("choice value must be a string or boolean");
            }
        }

        public Option(Object value) {
            this(value, null);
        }
    }

    /**
     * 使用从零开始的有序等级对输入评分。
     */
    record Score(String instructions, List<Level> levels) implements DecisionQuestion {
        public Score {
            Objects.requireNonNull(instructions, "instructions");
            levels = List.copyOf(levels);
            if (levels.isEmpty()) {
                throw new IllegalArgumentException("levels must not be empty");
            }
        }
    }

    /**
     * 评分准则等级，按从低到高排列。
     */
    record Level(String label, String description) {
        public Level {
            Objects.requireNonNull(label, "label");
        }

        public Level(String label) {
            this(label, null);
        }
    }
}
