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
package io.agentscope.extensions.sandbox.e2b.mounts;

/**
 * E2B 云存储桶挂载配置的占位类型。
 *
 * @deprecated 已由 {@link io.agentscope.extensions.sandbox.e2b.E2bVolumeMount} 创建时附加挂载替代。保留一个过渡版本，使引用此类型的下游仍可编译；它不包含行为，之后将移除。
 */
@Deprecated
public interface E2bCloudBucketMountStrategy {
    // 有意留空：从 Harness 工作区规格接入 E2B 卷挂载时可扩展
}
