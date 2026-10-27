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
package io.agentscope.extensions.jdbc.dialect;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 已组装的 SQL 语句及其绑定参数。
 *
 * <p>全部方言业务 SQL 方法的统一返回类型。
 * 使用方执行 {@link #sql()}，按顺序绑定 {@link #params()}。
 *
 * @param sql 带 {@code ?} 占位符的 SQL 语句
 * @param params 按占位符顺序排列的绑定参数
 * @author shanhongyu
 */
public record BoundSql(String sql, List<Object> params) {

    /**
     * 接收可变参数的便捷构造方法。
     */
    public BoundSql(String sql, Object... params) {
        this(sql, Collections.unmodifiableList(Arrays.asList(params)));
    }
}
