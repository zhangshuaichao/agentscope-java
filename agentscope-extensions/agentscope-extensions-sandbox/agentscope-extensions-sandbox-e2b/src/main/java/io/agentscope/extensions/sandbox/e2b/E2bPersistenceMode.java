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
package io.agentscope.extensions.sandbox.e2b;

/**
 * E2B 沙箱工作区字节的持久化方式。
 */
public enum E2bPersistenceMode {
    /**
     * Tar 归档字节（默认，与其他 Harness 快照兼容）。
     */
    TAR,
    /**
     * 使用 E2B {@code POST /sandboxes/{id}/snapshots}，并在 Harness 快照流中保存 {@link E2bSnapshotRefs} 标记字节（恢复时以快照 ID 作为 {@code templateID} 重建沙箱）。
     */
    NATIVE_SNAPSHOT
}
