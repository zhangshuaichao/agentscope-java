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

/**
 * 表领域方言接口，每张表对应一个接口。
 *
 * <p>各接口为一张表定义抽象建表 DDL 及 ANSI 默认业务 SQL
 * （返回 {@link io.agentscope.extensions.jdbc.dialect.BoundSql}）。
 * 方法名以表短名为前缀，例如 {@code storeUpsert}、{@code sessionStateSelect}，
 * 避免聚合类实现全部接口时名称冲突。
 *
 * <p>{@code xxxTableName()} 默认只返回不带前缀的基础名；
 * 最终名称（前缀加基础名，或逐表覆盖）由聚合类的 final 解析器生成。
 */
package io.agentscope.extensions.jdbc.dialect.table;
