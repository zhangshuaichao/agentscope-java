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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrainingAnnotation Unit Tests")
class TrainingAnnotationTest {

    @Test
    @DisplayName("Should create enabled annotation")
    void shouldCreateEnabledAnnotation() {
        // 执行
        TrainingAnnotation annotation = TrainingAnnotation.enabled();

        // 断言
        assertTrue(annotation.isEnabled());
        assertTrue(annotation.getLabels().isEmpty());
        assertTrue(annotation.getMetadata().isEmpty());
        assertNull(annotation.getTaskId());
        assertTrue(annotation.getTimestamp() > 0);
    }

    @Test
    @DisplayName("Should create annotation with labels")
    void shouldCreateAnnotationWithLabels() {
        // 执行
        TrainingAnnotation annotation = TrainingAnnotation.withLabels("high-quality", "production");

        // 断言
        assertTrue(annotation.isEnabled());
        assertEquals(2, annotation.getLabels().size());
        assertTrue(annotation.getLabels().contains("high-quality"));
        assertTrue(annotation.getLabels().contains("production"));
    }

    @Test
    @DisplayName("Should create annotation with labels and metadata")
    void shouldCreateAnnotationWithLabelsAndMetadata() {
        // 准备
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", "user-123");
        metadata.put("sessionId", "session-456");

        // 执行
        TrainingAnnotation annotation =
                TrainingAnnotation.withLabelsAndMetadata(
                        Arrays.asList("important", "review"), metadata);

        // 断言
        assertTrue(annotation.isEnabled());
        assertEquals(2, annotation.getLabels().size());
        assertEquals("user-123", annotation.getMetadata().get("userId"));
        assertEquals("session-456", annotation.getMetadata().get("sessionId"));
    }

    @Test
    @DisplayName("Should build annotation with builder")
    void shouldBuildAnnotationWithBuilder() {
        // 执行
        TrainingAnnotation annotation =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .taskId("custom-task-id")
                        .labels(Arrays.asList("label1", "label2"))
                        .metadata(Map.of("key", "value"))
                        .build();

        // 断言
        assertTrue(annotation.isEnabled());
        assertEquals("custom-task-id", annotation.getTaskId());
        assertEquals(2, annotation.getLabels().size());
        assertEquals("value", annotation.getMetadata().get("key"));
    }

    @Test
    @DisplayName("Should build annotation with custom timestamp")
    void shouldBuildAnnotationWithCustomTimestamp() {
        // 准备
        long customTimestamp = 1000000L;

        // 执行
        TrainingAnnotation annotation =
                TrainingAnnotation.builder().enabled(true).timestamp(customTimestamp).build();

        // 断言
        assertEquals(customTimestamp, annotation.getTimestamp());
    }

    @Test
    @DisplayName("Should check expiration correctly")
    void shouldCheckExpirationCorrectly() {
        // 准备：创建带旧时间戳的注解。
        TrainingAnnotation annotation =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .timestamp(System.currentTimeMillis() - 2000) // 2 秒前
                        .build();

        // 断言
        assertTrue(annotation.isExpired(1000)); // 1 秒 TTL，应已过期。
        assertFalse(annotation.isExpired(5000)); // 5 秒 TTL，应未过期。
    }

    @Test
    @DisplayName("Should not be expired with future timestamp")
    void shouldNotBeExpiredWithFutureTimestamp() {
        // 准备
        TrainingAnnotation annotation = TrainingAnnotation.enabled();

        // 断言：刚创建，不应过期。
        assertFalse(annotation.isExpired(60000)); // 1 分钟 TTL
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEqualsCorrectly() {
        // 准备
        long timestamp = System.currentTimeMillis();
        TrainingAnnotation annotation1 =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .taskId("task-1")
                        .labels(Arrays.asList("label"))
                        .timestamp(timestamp)
                        .build();

        TrainingAnnotation annotation2 =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .taskId("task-1")
                        .labels(Arrays.asList("label"))
                        .timestamp(timestamp)
                        .build();

        TrainingAnnotation annotation3 =
                TrainingAnnotation.builder().enabled(false).timestamp(timestamp).build();

        // 断言
        assertEquals(annotation1, annotation2);
        assertNotEquals(annotation1, annotation3);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCodeCorrectly() {
        // 准备
        long timestamp = System.currentTimeMillis();
        TrainingAnnotation annotation1 =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .taskId("task-1")
                        .timestamp(timestamp)
                        .build();

        TrainingAnnotation annotation2 =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .taskId("task-1")
                        .timestamp(timestamp)
                        .build();

        // 断言
        assertEquals(annotation1.hashCode(), annotation2.hashCode());
    }

    @Test
    @DisplayName("Should produce readable toString")
    void shouldProduceReadableToString() {
        // 准备
        TrainingAnnotation annotation =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .taskId("task-123")
                        .labels(Arrays.asList("important"))
                        .build();

        // 执行
        String str = annotation.toString();

        // 断言
        assertTrue(str.contains("enabled=true"));
        assertTrue(str.contains("taskId=task-123"));
        assertTrue(str.contains("important"));
    }

    @Test
    @DisplayName("Should handle null labels in withLabelsAndMetadata")
    void shouldHandleNullLabelsInWithLabelsAndMetadata() {
        // 执行
        TrainingAnnotation annotation =
                TrainingAnnotation.withLabelsAndMetadata(null, Map.of("key", "value"));

        // 断言
        assertNotNull(annotation.getLabels());
        assertTrue(annotation.getLabels().isEmpty());
    }

    @Test
    @DisplayName("Should handle null metadata in withLabelsAndMetadata")
    void shouldHandleNullMetadataInWithLabelsAndMetadata() {
        // 执行
        TrainingAnnotation annotation =
                TrainingAnnotation.withLabelsAndMetadata(Arrays.asList("label"), null);

        // 断言
        assertNotNull(annotation.getMetadata());
        assertTrue(annotation.getMetadata().isEmpty());
    }
}
