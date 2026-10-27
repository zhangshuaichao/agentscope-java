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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.AtomicSessionStorage;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文件系统包装器：每次委托调用都使用固定的 {@link RuntimeContext}，忽略调用方传入的上下文。
 *
 * <p>{@code HarnessAgent.workspaceFor(userId, sessionId)} 用它构建绑定明确用户身份的带外
 * {@link io.agentscope.harness.agent.workspace.WorkspaceManager} 视图。操作其他用户命名空间的
 * 控制器可向下游传入 {@link RuntimeContext#empty()}；此包装器确保底层命名空间工厂
 * 仍接收绑定身份，而不是调用方的空上下文。
 */
public final class BakedContextFilesystem implements AbstractFilesystem {

    private final AbstractFilesystem delegate;
    private final RuntimeContext bakedRc;

    public BakedContextFilesystem(AbstractFilesystem delegate, RuntimeContext bakedRc) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.bakedRc = bakedRc != null ? bakedRc : RuntimeContext.empty();
    }

    @Override
    public AtomicSessionStorage sessionStorage(RuntimeContext runtimeContext) {
        return delegate.sessionStorage(bakedRc);
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        return delegate.ls(bakedRc, path);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        return delegate.read(bakedRc, filePath, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return delegate.write(bakedRc, filePath, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        return delegate.edit(bakedRc, filePath, oldString, newString, replaceAll);
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        return delegate.grep(bakedRc, pattern, path, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        return delegate.glob(bakedRc, pattern, path);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        return delegate.uploadFiles(bakedRc, files);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        return delegate.downloadFiles(bakedRc, paths);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        return delegate.delete(bakedRc, path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        return delegate.move(bakedRc, fromPath, toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return delegate.exists(bakedRc, path);
    }
}
