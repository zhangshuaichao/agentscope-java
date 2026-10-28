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
package io.agentscope.harness.agent.example.support;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * 仅供测试的 {@link SandboxFilesystemSpec}，由 {@link InMemorySandboxClient} 支持。
 *
 * <p>使用本地临时目录作为沙箱工作区，无需 Docker 或外部依赖。底层 {@link InMemorySandboxClient} 暴露创建／恢复计数器，供隔离作用域测试验证沙箱是新建还是恢复。
 */
public class InMemorySandboxFilesystemSpec extends SandboxFilesystemSpec {

    private final InMemorySandboxClient client;

    /**
     * 使用给定内存沙箱客户端创建规格。
     *
     * @param client 用于会话创建／恢复的内存客户端
     */
    public InMemorySandboxFilesystemSpec(InMemorySandboxClient client) {
        this.client = client;
    }

    /**
     * 使用新的默认 {@link InMemorySandboxClient} 创建规格。
     */
    public InMemorySandboxFilesystemSpec() {
        this(new InMemorySandboxClient());
    }

    /**
     * 返回底层 {@link InMemorySandboxClient}。
     *
     * @return 客户端
     */
    public InMemorySandboxClient getClient() {
        return client;
    }

    @Override
    protected SandboxClient<?> createClient() {
        return client;
    }

    @Override
    protected SandboxClientOptions clientOptions() {
        return null;
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return null;
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        WorkspaceSpec s = new WorkspaceSpec();
        s.setRoot("/workspace");
        return s;
    }
}
