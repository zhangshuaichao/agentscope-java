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
package io.agentscope.harness.agent.filesystem.model;

import java.time.Instant;

/**
 * 存储文件内容及元数据的数据结构。
 *
 * @param content 以普通字符串表示的文件内容（UTF-8 文本或 Base64 编码的二进制内容）
 * @param encoding 内容编码：文本使用 {@code "utf-8"}，二进制内容使用 {@code "base64"}
 * @param createdAt ISO 8601 格式的文件创建时间戳（可为 null）
 * @param modifiedAt ISO 8601 格式的最近修改时间戳（可为 null）
 */
public record FileData(String content, String encoding, String createdAt, String modifiedAt) {

    public FileData(String content, String encoding) {
        this(content, encoding, null, null);
    }

    /**
     * 创建 UTF-8 文本 FileData，时间戳设为当前时刻。
     */
    public static FileData create(String content) {
        return create(content, "utf-8");
    }

    /**
     * 以指定编码创建 FileData，时间戳设为当前时刻。
     */
    public static FileData create(String content, String encoding) {
        String now = Instant.now().toString();
        return new FileData(content, encoding, now, now);
    }

    /**
     * 返回更新了内容及修改时间戳的副本。
     */
    public FileData withContent(String newContent) {
        return new FileData(newContent, encoding, createdAt, Instant.now().toString());
    }
}
