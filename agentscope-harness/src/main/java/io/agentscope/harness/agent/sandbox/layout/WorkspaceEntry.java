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
package io.agentscope.harness.agent.sandbox.layout;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * {@link io.agentscope.harness.agent.sandbox.WorkspaceSpec} 中条目的基类。
 *
 * <p>每个条目描述沙箱工作区启动时应存在的单个文件或目录。条目可从内联内容、宿主本地文件或远程来源物化。
 *
 * <p>{@code ephemeral} 为 {@code true} 时，每次沙箱启动都重新应用该条目，即使已有快照。非临时条目保存在快照中，随工作区恢复。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = FileEntry.class, name = "file"),
    @JsonSubTypes.Type(value = DirEntry.class, name = "dir"),
    @JsonSubTypes.Type(value = LocalFileEntry.class, name = "local_file"),
    @JsonSubTypes.Type(value = LocalDirEntry.class, name = "local_dir"),
    @JsonSubTypes.Type(value = GitRepoEntry.class, name = "git_repo"),
    @JsonSubTypes.Type(value = WorkspaceProjectionEntry.class, name = "workspace_projection"),
    @JsonSubTypes.Type(value = BindMountEntry.class, name = "bind_mount"),
})
public abstract class WorkspaceEntry {

    private boolean ephemeral = false;

    /**
     * 返回此条目是否为临时条目。
     *
     * <p>会话恢复时，无论是否存在快照，临时条目都会重新应用，适用于不应依赖快照保留的动态配置。
     *
     * @return 此条目为临时条目时为 true
     */
    public boolean isEphemeral() {
        return ephemeral;
    }

    /**
     * 设置此条目是否为临时条目。
     *
     * @param ephemeral true 表示标记为临时条目
     */
    public void setEphemeral(boolean ephemeral) {
        this.ephemeral = ephemeral;
    }
}
