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
package io.agentscope.core.permission;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 权限规则或工具自检的结果。
 *
 * <ul>
 *   <li>{@link #ALLOW}：继续执行，无须进一步检查。
 *   <li>{@link #DENY}：拒绝工具调用。
 *   <li>{@link #ASK}：交由用户确认。
 *   <li>{@link #PASSTHROUGH}：工具将决定权交还引擎，继续匹配规则。
 * </ul>
 */
public enum PermissionBehavior {
    ALLOW("allow"),
    DENY("deny"),
    ASK("ask"),
    PASSTHROUGH("passthrough");

    private final String value;

    PermissionBehavior(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 将字符串解析为 {@code PermissionBehavior}，不区分大小写。
     *
     * @param value JSON／配置中的字符串表示
     * @return 匹配的行为
     * @throws IllegalArgumentException 没有匹配的枚举值时抛出
     */
    @JsonCreator
    public static PermissionBehavior fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("PermissionBehavior value must not be null");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (PermissionBehavior behavior : values()) {
            if (behavior.value.equals(normalized)) {
                return behavior;
            }
        }
        throw new IllegalArgumentException("Unknown PermissionBehavior: " + value);
    }
}
