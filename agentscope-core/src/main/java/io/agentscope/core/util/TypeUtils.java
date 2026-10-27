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

import java.util.Optional;

/**
 * 类型安全操作工具类。
 */
public final class TypeUtils {

    private TypeUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 将对象安全转换为指定类型。
     *
     * @param obj 要转换的对象
     * @param clazz 目标类
     * @param <T> 目标类型
     * @return 转换后的对象
     * @throws ClassCastException 对象不是目标类实例时抛出
     */
    public static <T> T safeCast(Object obj, Class<T> clazz) {
        if (obj == null) {
            return null;
        }
        if (clazz.isInstance(obj)) {
            return clazz.cast(obj);
        }
        throw new ClassCastException(
                String.format("Cannot cast %s to %s", obj.getClass().getName(), clazz.getName()));
    }

    /**
     * 将对象安全转换为指定类型，并返回 Optional。
     *
     * @param obj 要转换的对象
     * @param clazz 目标类
     * @param <T> 目标类型
     * @return 包含转换后对象的 Optional；转换失败时为空
     */
    public static <T> Optional<T> safeCastOptional(Object obj, Class<T> clazz) {
        if (!clazz.isInstance(obj)) {
            return Optional.empty();
        }
        return Optional.of(clazz.cast(obj));
    }
}
