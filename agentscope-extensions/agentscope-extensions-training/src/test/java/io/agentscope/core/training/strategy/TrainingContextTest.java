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

package io.agentscope.core.training.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

@DisplayName("TrainingContext Unit Tests")
class TrainingContextTest {

    @Test
    @DisplayName("Should create mark function")
    void shouldCreateMarkFunction() {
        // 执行
        Function<Context, Context> markFn = TrainingContext.mark();

        // 断言
        assertNotNull(markFn);

        // 应用函数。
        Context context = markFn.apply(Context.empty());
        assertTrue(context.hasKey(TrainingContext.REACTOR_KEY));
    }

    @Test
    @DisplayName("Should create mark function with labels")
    void shouldCreateMarkFunctionWithLabels() {
        // 执行
        Function<Context, Context> markFn = TrainingContext.mark("high-quality", "production");

        // 断言
        Context context = markFn.apply(Context.empty());
        TrainingAnnotation annotation = context.get(TrainingContext.REACTOR_KEY);

        assertTrue(annotation.isEnabled());
        assertEquals(2, annotation.getLabels().size());
        assertTrue(annotation.getLabels().contains("high-quality"));
        assertTrue(annotation.getLabels().contains("production"));
    }

    @Test
    @DisplayName("Should create mark function with labels and metadata")
    void shouldCreateMarkFunctionWithLabelsAndMetadata() {
        // 准备
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", "user-123");
        metadata.put("taskId", "custom-task-id");

        // 执行
        Function<Context, Context> markFn =
                TrainingContext.mark(Arrays.asList("important"), metadata);

        // 断言
        Context context = markFn.apply(Context.empty());
        TrainingAnnotation annotation = context.get(TrainingContext.REACTOR_KEY);

        assertTrue(annotation.isEnabled());
        assertEquals("user-123", annotation.getMetadata().get("userId"));
        assertEquals("custom-task-id", annotation.getMetadata().get("taskId"));
    }

    @Test
    @DisplayName("Should create mark function with only metadata")
    void shouldCreateMarkFunctionWithOnlyMetadata() {
        // 准备
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");

        // 执行
        Function<Context, Context> markFn = TrainingContext.mark(metadata);

        // 断言
        Context context = markFn.apply(Context.empty());
        TrainingAnnotation annotation = context.get(TrainingContext.REACTOR_KEY);

        assertTrue(annotation.isEnabled());
        assertTrue(annotation.getLabels().isEmpty());
        assertEquals("value", annotation.getMetadata().get("key"));
    }

    @Test
    @DisplayName("Should get current annotation from context")
    void shouldGetCurrentAnnotationFromContext() {
        // 准备
        TrainingAnnotation annotation = TrainingAnnotation.withLabels("test-label");
        ContextView context = Context.of(TrainingContext.REACTOR_KEY, annotation);

        // 执行
        TrainingAnnotation result = TrainingContext.getCurrent(context);

        // 断言
        assertNotNull(result);
        assertTrue(result.isEnabled());
        assertTrue(result.getLabels().contains("test-label"));
    }

    @Test
    @DisplayName("Should return null when no annotation in context")
    void shouldReturnNullWhenNoAnnotationInContext() {
        // 执行
        TrainingAnnotation result = TrainingContext.getCurrent(Context.empty());

        // 断言
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for null context")
    void shouldReturnNullForNullContext() {
        // 执行
        TrainingAnnotation result = TrainingContext.getCurrent(null);

        // 断言
        assertNull(result);
    }

    @Test
    @DisplayName("Should check expiration correctly")
    void shouldCheckExpirationCorrectly() {
        // 准备：创建过期注解。
        TrainingAnnotation expiredAnnotation =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .timestamp(System.currentTimeMillis() - 2000) // 2 秒前
                        .build();

        TrainingAnnotation validAnnotation = TrainingAnnotation.enabled();

        // 断言
        assertTrue(TrainingContext.isExpired(expiredAnnotation, 1000)); // 1 秒 TTL
        assertFalse(TrainingContext.isExpired(validAnnotation, 60000)); // 1 分钟 TTL
        assertTrue(TrainingContext.isExpired(null, 60000)); // null 始终视为过期。
    }
}
