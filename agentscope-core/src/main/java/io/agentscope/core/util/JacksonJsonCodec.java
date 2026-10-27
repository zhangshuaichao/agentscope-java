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

package io.agentscope.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Jackson 的 {@link JsonCodec} 实现。
 *
 * <p>这是 {@link JsonUtils} 默认采用的实现，使用如下 ObjectMapper 配置：
 * <ul>
 * <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false}：允许 JSON 含未知字段</li>
 * </ul>
 *
 * <p>用户可通过 {@link #getObjectMapper()} 访问底层 ObjectMapper，执行 JsonCodec 接口未涵盖的高级操作。
 *
 * @see JsonCodec
 * @see JsonUtils
 */
public class JacksonJsonCodec implements JsonCodec {

    private static final Logger log = LoggerFactory.getLogger(JacksonJsonCodec.class);

    private final ObjectMapper objectMapper;

    /**
     * 使用默认 ObjectMapper 配置创建新的 JacksonJsonCodec。
     */
    public JacksonJsonCodec() {
        this.objectMapper = createDefaultObjectMapper();
    }

    /**
     * 使用自定义 ObjectMapper 创建新的 JacksonJsonCodec。
     *
     * @param objectMapper 要使用的 ObjectMapper
     */
    public JacksonJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 使用标准配置创建默认 ObjectMapper。
     *
     * @return 已配置的 ObjectMapper
     */
    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * 获取底层 ObjectMapper 以执行高级操作。
     *
     * @return ObjectMapper 实例
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    @Override
    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON: {}", e.getMessage(), e);
            throw new JsonException("Failed to serialize object to JSON", e);
        }
    }

    @Override
    public String toPrettyJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to pretty JSON: {}", e.getMessage(), e);
            throw new JsonException("Failed to serialize object to pretty JSON", e);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new JsonException("Failed to deserialize JSON to " + type.getName(), e);
        }
    }

    @Override
    public <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new JsonException("Failed to deserialize JSON", e);
        }
    }

    @Override
    public <T> T convertValue(Object from, Class<T> toType) {
        try {
            return objectMapper.convertValue(from, toType);
        } catch (IllegalArgumentException e) {
            throw new JsonException("Failed to convert value to " + toType.getName(), e);
        }
    }

    @Override
    public <T> T convertValue(Object from, TypeReference<T> toTypeRef) {
        try {
            return objectMapper.convertValue(from, toTypeRef);
        } catch (IllegalArgumentException e) {
            throw new JsonException("Failed to convert value", e);
        }
    }

    @Override
    public Object convertValue(Object from, Type toType) {
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(toType);
            return objectMapper.convertValue(from, javaType);
        } catch (IllegalArgumentException e) {
            throw new JsonException("Failed to convert value to " + toType.getTypeName(), e);
        }
    }
}
