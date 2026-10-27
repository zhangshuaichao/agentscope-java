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
package io.agentscope.extensions.channel.common;

import java.util.Objects;

/**
 * 平台访问令牌及获取它的提供器选定的刷新截止时间。
 * @param value 平台 API 接受的令牌字符串，不得为 {@code null}；缺少令牌通过 {@link AccessTokenStore#get()} 返回 {@code null} 表示
 * @param refreshAtMs 墙上时钟的 Unix 毫秒时间；超过后提供器重新获取令牌，不再直接返回旧值
 */
public record CachedAccessToken(String value, long refreshAtMs) {

    public CachedAccessToken {
        Objects.requireNonNull(value, "value");
    }
}
