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

/**
 * 结构化文件列表信息。仅 {@code path} 必填；其余字段尽力提供，可能因文件系统实现而缺失。
 *
 * @param path 文件绝对或相对路径
 * @param isDirectory 该条目是否为目录
 * @param size 文件大小，单位为字节（近似值）
 * @param modifiedAt ISO 8601 格式的最近修改时间戳；未知时为空字符串
 */
public record FileInfo(String path, boolean isDirectory, long size, String modifiedAt) {

    public static FileInfo ofFile(String path, long size, String modifiedAt) {
        return new FileInfo(path, false, size, modifiedAt != null ? modifiedAt : "");
    }

    public static FileInfo ofDir(String path, String modifiedAt) {
        return new FileInfo(path, true, 0, modifiedAt != null ? modifiedAt : "");
    }

    public static FileInfo ofFile(String path, long size, long lastModifiedMs) {
        return new FileInfo(
                path, false, size, java.time.Instant.ofEpochMilli(lastModifiedMs).toString());
    }

    public static FileInfo ofDir(String path, long lastModifiedMs) {
        return new FileInfo(
                path, true, 0, java.time.Instant.ofEpochMilli(lastModifiedMs).toString());
    }
}
