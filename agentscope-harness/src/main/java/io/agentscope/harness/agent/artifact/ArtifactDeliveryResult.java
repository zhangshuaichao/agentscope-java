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
package io.agentscope.harness.agent.artifact;

/**
 * {@link ArtifactDeliveryTarget#deliver} 尝试的结果。
 *
 * @param successful 产物是否已交付
 * @param conflict 是否因为同名产物已存在且 {@code force} 为 {@code false} 而拒绝交付
 * @param error 失败时的错误说明；成功时为 {@code null}
 * @param message 成功时供人阅读的确认详情（可以为 {@code null}）
 */
public record ArtifactDeliveryResult(
        boolean successful, boolean conflict, String error, String message) {

    public static ArtifactDeliveryResult success() {
        return new ArtifactDeliveryResult(true, false, null, null);
    }

    public static ArtifactDeliveryResult success(String message) {
        return new ArtifactDeliveryResult(true, false, null, message);
    }

    public static ArtifactDeliveryResult fail(String error) {
        return new ArtifactDeliveryResult(false, false, error, null);
    }

    public static ArtifactDeliveryResult conflict(String error) {
        return new ArtifactDeliveryResult(false, true, error, null);
    }
}
