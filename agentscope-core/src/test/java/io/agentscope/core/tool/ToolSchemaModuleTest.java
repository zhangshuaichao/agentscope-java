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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolSchemaModule} 测试。
 */
class ToolSchemaModuleTest {

    // --- 测试 POJO ---

    @SuppressWarnings("unused")
    static class AnnotatedPojo {

        @ToolParam(name = "city", description = "City name", required = true)
        private String city;

        @ToolParam(name = "zip", description = "Zip code", required = false)
        private String zip;
    }

    @SuppressWarnings("unused")
    static class UnannotatedPojo {

        private String name;

        private int age;
    }

    @SuppressWarnings("unused")
    static class MixedPojo {

        @ToolParam(name = "label", description = "A label")
        private String label;

        private String extra;
    }

    @SuppressWarnings("unused")
    static class BlankDescriptionPojo {

        @ToolParam(name = "value", description = "  ")
        private String value;
    }

    @SuppressWarnings("unused")
    static class NameOverridePojo {

        @ToolParam(name = "city_name", description = "The city name")
        private String city;

        @ToolParam(name = "zip_code", description = "The zip code", required = false)
        private String zip;

        @ToolParam(name = "", description = "A value")
        private String value;
    }

    // --- 辅助方法 ---

    private JsonNode generate(Class<?> clazz, ToolSchemaModule.Option... options) {
        ToolSchemaModule module = new ToolSchemaModule(options);
        SchemaGeneratorConfigBuilder builder =
                new SchemaGeneratorConfigBuilder(
                                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(module);
        return new SchemaGenerator(builder.build()).generateSchema(clazz);
    }

    private List<String> requiredList(JsonNode schema) {
        JsonNode req = schema.get("required");
        if (req == null || !req.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(req.spliterator(), false).map(JsonNode::asText).toList();
    }

    // --- 测试 ---

    @Test
    void annotatedDescription() {
        JsonNode schema = generate(AnnotatedPojo.class);
        JsonNode props = schema.get("properties");

        assertEquals("City name", props.get("city").get("description").asText());
        assertEquals("Zip code", props.get("zip").get("description").asText());
    }

    @Test
    void annotatedRequired() {
        JsonNode schema = generate(AnnotatedPojo.class);
        List<String> required = requiredList(schema);

        assertTrue(required.contains("city"));
        assertFalse(required.contains("zip"));
    }

    @Test
    void unannotatedFieldsRequiredByDefault() {
        JsonNode schema = generate(UnannotatedPojo.class);
        List<String> required = requiredList(schema);

        assertTrue(required.contains("name"));
        assertTrue(required.contains("age"));
    }

    @Test
    void unannotatedFieldsOptionalWhenOptionSet() {
        JsonNode schema =
                generate(
                        UnannotatedPojo.class,
                        ToolSchemaModule.Option.PROPERTY_REQUIRED_FALSE_BY_DEFAULT);
        List<String> required = requiredList(schema);

        assertFalse(required.contains("name"));
        assertFalse(required.contains("age"));
    }

    @Test
    void mixedAnnotatedAndUnannotated() {
        JsonNode schema = generate(MixedPojo.class);
        List<String> required = requiredList(schema);

        // @ToolParam 默认 required=true
        assertTrue(required.contains("label"));
        // 无注解时采用 requiredByDefault，即 true
        assertTrue(required.contains("extra"));
    }

    @Test
    void mixedWithOptionFalseByDefault() {
        JsonNode schema =
                generate(
                        MixedPojo.class,
                        ToolSchemaModule.Option.PROPERTY_REQUIRED_FALSE_BY_DEFAULT);
        List<String> required = requiredList(schema);

        // @ToolParam(required=true) 仍为必填
        assertTrue(required.contains("label"));
        // 无注解时可选
        assertFalse(required.contains("extra"));
    }

    @Test
    void blankDescriptionIsIgnored() {
        JsonNode schema = generate(BlankDescriptionPojo.class);
        JsonNode desc = schema.get("properties").get("value").get("description");

        assertNull(desc);
    }

    @Test
    void propertyNameOverride() {
        JsonNode schema = generate(NameOverridePojo.class);
        JsonNode props = schema.get("properties");
        List<String> required = requiredList(schema);

        // 名称覆盖：字段 "city" → "city_name"，"zip" → "zip_code"
        assertNull(props.get("city"));
        assertEquals("The city name", props.get("city_name").get("description").asText());
        assertNull(props.get("zip"));
        assertEquals("The zip code", props.get("zip_code").get("description").asText());
        assertTrue(required.contains("city_name"));
        assertFalse(required.contains("zip_code"));

        // 名称为空白时回退到字段名 "value"
        assertEquals("A value", props.get("value").get("description").asText());
    }
}
