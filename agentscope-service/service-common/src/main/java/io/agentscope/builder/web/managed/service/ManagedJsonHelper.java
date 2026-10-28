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
package io.agentscope.builder.web.managed.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 托管智能体服务层共享的 JSON 与标识符辅助方法。 */
@Component
public class ManagedJsonHelper {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> OBJECT_LIST =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ManagedJsonHelper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 生成带前缀、包含 16 个十六进制字符的随机标识符。 */
    public static String randomId(String prefix) {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 将值序列化为 JSON；值为 {@code null} 时返回 {@code null}。 */
    public String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize JSON", ex);
        }
    }

    /** 将 JSON 反序列化为 {@code Map<String, Object>}。 */
    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to parse JSON map", ex);
        }
    }

    /** 将 JSON 反序列化为字符串列表。 */
    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /** 将 JSON 反序列化为对象映射列表（会话资源等）。 */
    public List<Map<String, Object>> readObjectList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, OBJECT_LIST);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /** 返回配置的 {@link ObjectMapper}。 */
    public ObjectMapper mapper() {
        return objectMapper;
    }
}
