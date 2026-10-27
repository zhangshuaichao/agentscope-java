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
package io.agentscope.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
 * 保存在工具读取缓存中的文件内容快照。
 *
 * @param lines 捕获时的行内容（已防御性复制，绝不为 {@code null}）
 * @param updatedAt 文件修改时间戳，单位为纪元秒，含毫秒小数部分
 * @param bytes 缓存条目的近似大小，单位为千字节（UTF-8 字节总和 / 1024）
 * @param filePath 缓存文件的绝对路径（绝不为 {@code null}）
 */
public record ReadCacheEntry(
        @JsonProperty("lines") List<String> lines,
        @JsonProperty("updated_at") double updatedAt,
        @JsonProperty("bytes") double bytes,
        @JsonProperty("file_path") String filePath) {

    @JsonCreator
    public ReadCacheEntry {
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        lines = List.copyOf(lines);
    }
}
