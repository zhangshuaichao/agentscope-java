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

import java.util.List;

/**
 * 抽象文件系统 grep 操作的结果。
 *
 * @param error 失败时的错误消息；成功时为 {@code null}
 * @param matches 成功时的 grep 匹配项列表；失败时为 {@code null}
 */
public record GrepResult(String error, List<GrepMatch> matches) {

    public static GrepResult success(List<GrepMatch> matches) {
        return new GrepResult(null, matches);
    }

    public static GrepResult fail(String error) {
        return new GrepResult(error, null);
    }

    public boolean isSuccess() {
        return error == null;
    }
}
