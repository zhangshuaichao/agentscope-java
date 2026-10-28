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

/**
 * 提供方 token 计数；缺失计数保留为 {@code null}，绝不推断为零。
 */
public record DecisionUsage(
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        Long cachedTokens,
        Long cacheWriteTokens,
        Long reasoningTokens) {}
