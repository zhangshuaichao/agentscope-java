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
package io.agentscope.extensions.mongodb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 递归转义并恢复任意 {@link Map} 值中的 MongoDB 字段名。
 *
 * <p>为兼容 MongoDB 对字段名中 {@code '.'} 和前导 {@code '$'} 的限制，调用方提供的状态／BaseStore {@code Map<String, Object>} 键在写入时需要编码。
 * 此工具写入时采用反斜杠前缀编码，读取时执行逆变换，避免这些键因字段命名规则导致编码写入失败。
 *
 * <p>编码无碰撞：{@code .} → {@code \E}、{@code $} → {@code \$}、{@code \0} → {@code \N}，字面反斜杠 → {@code \\}。
 * 对反斜杠自身转义确保往返一一对应：字面包含 {@code \E} 的键保存为 {@code \\E}，恢复为 {@code \E}，不会与已转义的点号混淆。
 */
public final class MongoKeyEscaper {

    private static final char ESCAPE_PREFIX = '\\';
    private static final char DOT_CODE = 'E';
    private static final char DOLLAR_CODE = '$';
    private static final char NULL_CODE = 'N';
    private static final char BACKSLASH_CODE = '\\';

    private MongoKeyEscaper() {}

    /**
     * 递归转义全部映射键，包括嵌套映射和列表元素。
     *
     * @param map 要转义的映射，不得为 {@code null}
     * @return 键已转义的新映射，非映射叶值与输入共享
     */
    public static Map<String, Object> escape(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(escapeKey(entry.getKey()), escapeValue(entry.getValue()));
        }
        return result;
    }

    /**
     * 递归恢复全部映射键，包括嵌套映射和列表元素。
     *
     * @param map 要反转义的映射，不得为 {@code null}
     * @return 键已恢复的新映射，非映射叶值与输入共享
     */
    public static Map<String, Object> unescape(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(unescapeKey(entry.getKey()), unescapeValue(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object escapeValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            return escape((Map<String, Object>) nested);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(escapeValue(item));
            }
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Object unescapeValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            return unescape((Map<String, Object>) nested);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(unescapeValue(item));
            }
            return result;
        }
        return value;
    }

    private static String escapeKey(String key) {
        if (key.indexOf('.') < 0
                && key.indexOf('$') < 0
                && key.indexOf('\0') < 0
                && key.indexOf(ESCAPE_PREFIX) < 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.') {
                sb.append(ESCAPE_PREFIX).append(DOT_CODE);
            } else if (c == '$') {
                sb.append(ESCAPE_PREFIX).append(DOLLAR_CODE);
            } else if (c == '\0') {
                sb.append(ESCAPE_PREFIX).append(NULL_CODE);
            } else if (c == ESCAPE_PREFIX) {
                sb.append(ESCAPE_PREFIX).append(BACKSLASH_CODE);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescapeKey(String key) {
        if (key.indexOf(ESCAPE_PREFIX) < 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ESCAPE_PREFIX && i + 1 < key.length()) {
                char next = key.charAt(i + 1);
                if (next == DOT_CODE) {
                    sb.append('.');
                } else if (next == DOLLAR_CODE) {
                    sb.append('$');
                } else if (next == NULL_CODE) {
                    sb.append('\0');
                } else if (next == BACKSLASH_CODE) {
                    sb.append(ESCAPE_PREFIX);
                } else {
                    // 未知转义序列（只可能来自转义机制引入前的数据），原样保留。
                    sb.append(c).append(next);
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
