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
 * 单个文件上传操作的结果。
 *
 * @param path 请求的文件路径
 * @param error 失败时的错误说明；成功时为 {@code null}
 */
public record FileUploadResponse(String path, String error) {

    public static FileUploadResponse success(String path) {
        return new FileUploadResponse(path, null);
    }

    public static FileUploadResponse fail(String path, String error) {
        return new FileUploadResponse(path, error);
    }

    public boolean isSuccess() {
        return error == null;
    }
}
