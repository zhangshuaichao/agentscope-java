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
 * 控制引擎如何评估工具执行请求的权限模式。
 *
 * <ul>
 *   <li>{@link #DEFAULT}：所有操作都需要显式允许规则。
 *   <li>{@link #ACCEPT_EDITS}：自动允许工作目录内的文件编辑。
 *   <li>{@link #EXPLORE}：只读模式，拒绝修改类工具。
 *   <li>{@link #BYPASS}：不经规则评估便允许所有操作。
 *   <li>{@link #DONT_ASK}：将 ASK 决定降为 DENY（用于无人值守执行）。
 * </ul>
 *
 * <p>【学习提示】以上为上游模式概述；具体边界应以 PermissionEngine 的实现为准：
 * BYPASS 仅是后置回退，不能越过先行的拒绝／询问规则及工具自检；
 * DEFAULT 也可能由工具自检直接允许。不能仅凭模式名称推断最终权限。
 */
public enum PermissionMode {
    DEFAULT("default"),
    ACCEPT_EDITS("accept_edits"),
    EXPLORE("explore"),
    BYPASS("bypass"),
    DONT_ASK("dont_ask");

    private final String value;

    PermissionMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 将字符串解析为 {@code PermissionMode}，不区分大小写。
     *
     * @param value JSON／配置中的字符串表示
     * @return 匹配的模式
     * @throws IllegalArgumentException 没有匹配的枚举值时抛出
     */
    @JsonCreator
    public static PermissionMode fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("PermissionMode value must not be null");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (PermissionMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown PermissionMode: " + value);
    }
}
