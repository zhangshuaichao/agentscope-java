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

import com.fasterxml.jackson.core.type.TypeReference;
import java.lang.reflect.Type;

/**
 * JSON 序列化与反序列化操作接口。
 *
 * <p>为 JSON 处理提供统一抽象，允许按需用自定义实现替换默认 Jackson 实现。
 *
 * <p>用法：
 * <pre>{@code
 * // 获取编解码器实例
 * JsonCodec codec = JsonUtils.getJsonCodec();
 *
 * // 将对象序列化为 JSON
 * String json = codec.toJson(myObject);
 *
 * // 将 JSON 反序列化为对象
 * MyClass obj = codec.fromJson(json, MyClass.class);
 *
 * // 按泛型类型反序列化
 * List<MyClass> list = codec.fromJson(json, new TypeReference<List<MyClass>>() {});
 * }</pre>
 *
 * @see JacksonJsonCodec
 * @see JsonUtils
 */
public interface JsonCodec {

    /**
     * 将对象序列化为 JSON 字符串。
     *
     * @param obj 要序列化的对象
     * @return JSON 字符串表示
     * @throws JsonException 序列化失败时抛出
     */
    String toJson(Object obj);

    /**
     * 将对象序列化为美化格式的 JSON 字符串。
     *
     * @param obj 要序列化的对象
     * @return 美化格式的 JSON 字符串表示
     * @throws JsonException 序列化失败时抛出
     */
    String toPrettyJson(Object obj);

    /**
     * 将 JSON 字符串反序列化为指定类型的对象。
     *
     * @param json 要反序列化的 JSON 字符串
     * @param type 目标类类型
     * @param <T> 类型参数
     * @return 反序列化后的对象
     * @throws JsonException 反序列化失败时抛出
     */
    <T> T fromJson(String json, Class<T> type);

    /**
     * 将 JSON 字符串反序列化为泛型对象。
     *
     * <p>反序列化为 {@code List<MyClass>} 等泛型类型时使用此方法。
     *
     * @param json 要反序列化的 JSON 字符串
     * @param typeRef 泛型类型引用
     * @param <T> 类型参数
     * @return 反序列化后的对象
     * @throws JsonException 反序列化失败时抛出
     */
    <T> T fromJson(String json, TypeReference<T> typeRef);

    /**
     * 将对象转换为另一类型。
     *
     * <p>适用于 Map 与类型化对象之间的双向转换。
     *
     * @param from 源对象
     * @param toType 目标类类型
     * @param <T> 类型参数
     * @return 转换后的对象
     * @throws JsonException 转换失败时抛出
     */
    <T> T convertValue(Object from, Class<T> toType);

    /**
     * 将对象转换为另一泛型类型。
     *
     * @param from 源对象
     * @param toTypeRef 泛型类型引用
     * @param <T> 类型参数
     * @return 转换后的对象
     * @throws JsonException 转换失败时抛出
     */
    <T> T convertValue(Object from, TypeReference<T> toTypeRef);

    /**
     * 使用 Java 反射 Type 将对象转换为另一类型。
     *
     * <p>保留 {@code List<MyClass>} 或 {@code Map<String, MyClass>} 等参数化类型的泛型信息。
     *
     * <p>用法示例：
     * <pre>{@code
     * // 针对声明为 List<OrderItem> 的方法参数
     * Type paramType = parameter.getParameterizedType();
     * List<OrderItem> items = (List<OrderItem>) codec.convertValue(value, paramType);
     * }</pre>
     *
     * @param from 源对象
     * @param toType 目标类型（可以是 Class、ParameterizedType 等）
     * @return 转换后的对象
     * @throws JsonException 转换失败时抛出
     */
    Object convertValue(Object from, Type toType);
}
