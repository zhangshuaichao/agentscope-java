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
package io.agentscope.core.rag.store.dto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link SearchDocumentDto} 单元测试。
 */
@Tag("unit")
@DisplayName("SearchDocumentDto Tests")
class SearchDocumentDtoTest {

    // ==================== 构建器模式测试 ====================

    @Test
    @DisplayName("should create instance with all fields using builder")
    void shouldCreateInstanceWithAllFields() {
        // 给定条件
        String vectorName = "test-vector";
        double[] queryEmbedding = {0.1, 0.2, 0.3};
        int limit = 10;
        Double scoreThreshold = 0.8;

        // 执行操作
        SearchDocumentDto dto =
                SearchDocumentDto.builder()
                        .vectorName(vectorName)
                        .queryEmbedding(queryEmbedding)
                        .limit(limit)
                        .scoreThreshold(scoreThreshold)
                        .build();

        // 验证结果
        assertNotNull(dto);
        assertEquals(vectorName, dto.getVectorName());
        assertArrayEquals(queryEmbedding, dto.getQueryEmbedding());
        assertEquals(limit, dto.getLimit());
        assertEquals(scoreThreshold, dto.getScoreThreshold());
    }

    @Test
    @DisplayName("should create instance with null vectorName")
    void shouldCreateInstanceWithNullVectorName() {
        // 给定条件
        double[] queryEmbedding = {0.5, 0.6};
        int limit = 5;

        // 执行操作
        SearchDocumentDto dto =
                SearchDocumentDto.builder()
                        .vectorName(null)
                        .queryEmbedding(queryEmbedding)
                        .limit(limit)
                        .build();

        // 验证结果
        assertNotNull(dto);
        assertNull(dto.getVectorName());
        assertArrayEquals(queryEmbedding, dto.getQueryEmbedding());
        assertEquals(limit, dto.getLimit());
        assertNull(dto.getScoreThreshold());
    }

    @Test
    @DisplayName("should create instance with null scoreThreshold")
    void shouldCreateInstanceWithNullScoreThreshold() {
        // 给定条件
        String vectorName = "embedding";
        double[] queryEmbedding = {1.0, 2.0, 3.0};
        int limit = 20;

        // 执行操作
        SearchDocumentDto dto =
                SearchDocumentDto.builder()
                        .vectorName(vectorName)
                        .queryEmbedding(queryEmbedding)
                        .limit(limit)
                        .scoreThreshold(null)
                        .build();

        // 验证结果
        assertNotNull(dto);
        assertEquals(vectorName, dto.getVectorName());
        assertNull(dto.getScoreThreshold());
    }

    @Test
    @DisplayName("should create builder instance")
    void shouldCreateBuilderInstance() {
        // 执行操作
        SearchDocumentDto.SearchDocumentDtoBuilder builder = SearchDocumentDto.builder();

        // 验证结果
        assertNotNull(builder);
    }

    @Test
    @DisplayName("builder methods should return the same builder instance for chaining")
    void builderMethodsShouldReturnSameInstance() {
        // 给定条件
        SearchDocumentDto.SearchDocumentDtoBuilder builder = SearchDocumentDto.builder();

        // 执行操作
        SearchDocumentDto.SearchDocumentDtoBuilder result1 = builder.vectorName("test");
        SearchDocumentDto.SearchDocumentDtoBuilder result2 =
                result1.queryEmbedding(new double[] {1.0});
        SearchDocumentDto.SearchDocumentDtoBuilder result3 = result2.limit(10);
        SearchDocumentDto.SearchDocumentDtoBuilder result4 = result3.scoreThreshold(0.5);

        // 验证结果
        assertEquals(builder, result1);
        assertEquals(result1, result2);
        assertEquals(result2, result3);
        assertEquals(result3, result4);
    }

    // ==================== Getter 与 Setter 测试 ====================

    @Test
    @DisplayName("should set and get vectorName")
    void shouldSetAndGetVectorName() {
        // 给定条件
        SearchDocumentDto dto =
                SearchDocumentDto.builder().queryEmbedding(new double[] {1.0}).limit(1).build();
        String newVectorName = "updated-vector";

        // 执行操作
        dto.setVectorName(newVectorName);

        // 验证结果
        assertEquals(newVectorName, dto.getVectorName());
    }

    @Test
    @DisplayName("should set and get queryEmbedding")
    void shouldSetAndGetQueryEmbedding() {
        // 给定条件
        SearchDocumentDto dto =
                SearchDocumentDto.builder().queryEmbedding(new double[] {1.0}).limit(1).build();
        double[] newEmbedding = {0.1, 0.2, 0.3, 0.4, 0.5};

        // 执行操作
        dto.setQueryEmbedding(newEmbedding);

        // 验证结果
        assertArrayEquals(newEmbedding, dto.getQueryEmbedding());
    }

    @Test
    @DisplayName("should set and get limit")
    void shouldSetAndGetLimit() {
        // 给定条件
        SearchDocumentDto dto =
                SearchDocumentDto.builder().queryEmbedding(new double[] {1.0}).limit(1).build();
        int newLimit = 100;

        // 执行操作
        dto.setLimit(newLimit);

        // 验证结果
        assertEquals(newLimit, dto.getLimit());
    }

    @Test
    @DisplayName("should set and get scoreThreshold")
    void shouldSetAndGetScoreThreshold() {
        // 给定条件
        SearchDocumentDto dto =
                SearchDocumentDto.builder().queryEmbedding(new double[] {1.0}).limit(1).build();
        Double newThreshold = 0.95;

        // 执行操作
        dto.setScoreThreshold(newThreshold);

        // 验证结果
        assertEquals(newThreshold, dto.getScoreThreshold());
    }

    @Test
    @DisplayName("should allow setting vectorName to null")
    void shouldAllowSettingVectorNameToNull() {
        // 给定条件
        SearchDocumentDto dto =
                SearchDocumentDto.builder()
                        .vectorName("initial")
                        .queryEmbedding(new double[] {1.0})
                        .limit(1)
                        .build();

        // 执行操作
        dto.setVectorName(null);

        // 验证结果
        assertNull(dto.getVectorName());
    }

    @Test
    @DisplayName("should allow setting scoreThreshold to null")
    void shouldAllowSettingScoreThresholdToNull() {
        // 给定条件
        SearchDocumentDto dto =
                SearchDocumentDto.builder()
                        .scoreThreshold(0.5)
                        .queryEmbedding(new double[] {1.0})
                        .limit(1)
                        .build();

        // 执行操作
        dto.setScoreThreshold(null);

        // 验证结果
        assertNull(dto.getScoreThreshold());
    }

    // ==================== toString 测试 ====================

    @Test
    @DisplayName("should return formatted string with all values")
    void shouldReturnFormattedStringWithAllValues() {
        // 给定条件
        SearchDocumentDto dto =
                SearchDocumentDto.builder()
                        .vectorName("test-vector")
                        .queryEmbedding(new double[] {1.0, 2.0})
                        .limit(10)
                        .scoreThreshold(0.85)
                        .build();

        // 执行操作
        String result = dto.toString();

        // 验证结果
        assertEquals(
                "SearchDocumentDto(vectorName=test-vector, limit=10, scoreThreshold=0.850)",
                result);
    }

    @Test
    @DisplayName("should return formatted string with null vectorName")
    void shouldReturnFormattedStringWithNullVectorName() {
        // 给定条件
        SearchDocumentDto dto =
                SearchDocumentDto.builder()
                        .vectorName(null)
                        .queryEmbedding(new double[] {1.0})
                        .limit(5)
                        .scoreThreshold(0.5)
                        .build();

        // 执行操作
        String result = dto.toString();

        // 验证结果
        assertEquals("SearchDocumentDto(vectorName=null, limit=5, scoreThreshold=0.500)", result);
    }

    @Test
    @DisplayName("should return formatted string with null scoreThreshold")
    void shouldReturnFormattedStringWithNullScoreThreshold() {
        // 给定条件
        SearchDocumentDto dto =
                SearchDocumentDto.builder()
                        .vectorName("vector")
                        .queryEmbedding(new double[] {1.0})
                        .limit(3)
                        .scoreThreshold(null)
                        .build();

        // 执行操作
        String result = dto.toString();

        // 验证结果
        assertEquals("SearchDocumentDto(vectorName=vector, limit=3, scoreThreshold=null)", result);
    }

    @Test
    @DisplayName("should return formatted string with all null optional values")
    void shouldReturnFormattedStringWithAllNullOptionalValues() {
        // 给定条件
        SearchDocumentDto dto =
                SearchDocumentDto.builder()
                        .vectorName(null)
                        .queryEmbedding(new double[] {1.0})
                        .limit(0)
                        .scoreThreshold(null)
                        .build();

        // 执行操作
        String result = dto.toString();

        // 验证结果
        assertEquals("SearchDocumentDto(vectorName=null, limit=0, scoreThreshold=null)", result);
    }
}
