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
 * 文件编辑（字符串替换）操作的结果。
 *
 * @param path 已编辑文件的绝对路径；失败时为 {@code null}
 * @param error 失败时的错误消息；成功时为 {@code null}
 * @param occurrences 替换次数；失败时为 {@code null}
 */
public record EditResult(String path, String error, Integer occurrences) {

    public static EditResult ok(String path, int occurrences) {
        return new EditResult(path, null, occurrences);
    }

    public static EditResult fail(String error) {
        return new EditResult(null, error, null);
    }

    public boolean isSuccess() {
        return error == null;
    }
}
