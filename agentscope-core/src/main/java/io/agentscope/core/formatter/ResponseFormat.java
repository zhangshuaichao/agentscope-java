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
package io.agentscope.core.formatter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模型 API 的响应格式配置。
 *
 * <p>支持三种响应格式：
 * <ul>
 *   <li><b>text</b>：纯文本响应（默认）</li>
 *   <li><b>json_object</b>：合法的 JSON 对象响应</li>
 *   <li><b>json_schema</b>：符合指定 JSON Schema 的响应</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 纯文本
 * ResponseFormat format = ResponseFormat.text();
 *
 * // JSON 对象
 * ResponseFormat format = ResponseFormat.jsonObject();
 *
 * // 使用严格校验的 JSON Schema
 * JsonSchema schema = JsonSchema.builder()
 *     .name("MathResponse")
 *     .schema(Map.of("type", "object", ...))
 *     .strict(true)
 *     .build();
 * ResponseFormat format = ResponseFormat.jsonSchema(schema);
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseFormat {

    /**
     * 响应格式类型："text"、"json_object" 或 "json_schema"。
     */
    @JsonProperty("type")
    private String type;

    /**
     * JSON Schema 规范（仅适用于 json_schema 类型）。
     */
    @JsonProperty("json_schema")
    private JsonSchema jsonSchema;

    public ResponseFormat() {}

    private ResponseFormat(String type, JsonSchema jsonSchema) {
        this.type = type;
        this.jsonSchema = jsonSchema;
    }

    /**
     * 创建纯文本响应格式。
     *
     * @return 配置为纯文本的 ResponseFormat
     */
    public static ResponseFormat text() {
        return new ResponseFormat("text", null);
    }

    /**
     * 创建 JSON 对象响应格式。
     *
     * <p>模型将返回合法的 JSON 对象，但不按照特定模式校验。
     *
     * @return 配置为 JSON 对象的 ResponseFormat
     */
    public static ResponseFormat jsonObject() {
        return new ResponseFormat("json_object", null);
    }

    /**
     * 创建带结构化输出校验的 JSON Schema 响应格式。
     *
     * <p>模型将返回符合所给 JSON Schema 的响应。启用严格模式时，将按模式校验响应。
     *
     * @param schema 定义预期结构的 JSON Schema
     * @return 配置为 JSON Schema 校验的 ResponseFormat
     */
    public static ResponseFormat jsonSchema(JsonSchema schema) {
        return new ResponseFormat("json_schema", schema);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public JsonSchema getJsonSchema() {
        return jsonSchema;
    }

    public void setJsonSchema(JsonSchema jsonSchema) {
        this.jsonSchema = jsonSchema;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String type;
        private JsonSchema jsonSchema;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder jsonSchema(JsonSchema jsonSchema) {
            this.type = "json_schema";
            this.jsonSchema = jsonSchema;
            return this;
        }

        public ResponseFormat build() {
            return new ResponseFormat(type, jsonSchema);
        }
    }
}
