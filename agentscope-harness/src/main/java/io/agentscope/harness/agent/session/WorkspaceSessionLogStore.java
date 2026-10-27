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
package io.agentscope.harness.agent.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 默认日志存储使用工作区后端，包括其分布式命名空间路由。
 */
public final class WorkspaceSessionLogStore implements SessionLogStore {
    private final AbstractFilesystem filesystem;

    public WorkspaceSessionLogStore(AbstractFilesystem filesystem) {
        this.filesystem = Objects.requireNonNull(filesystem);
    }

    public WorkspaceSessionLogStore(WorkspaceManager workspace) {
        this(
                workspace.getFilesystem() != null
                        ? workspace.getFilesystem()
                        : new LocalFilesystem(workspace.getWorkspace()));
    }

    /**
     * 在文件系统和显式调用方身份选定的命名空间中发现会话。
     * SESSION 隔离（包括缺少用户 ID 时 USER 模式的回退）仅暴露当前
     * 命名空间。跨会话发现应使用共享的用户级日志后端。固定上下文的
     * 文件系统在此仍要求匹配的用户 ID，因为逻辑键按用户划分。
     */
    @Override
    public List<SessionKey> list(RuntimeContext context) {
        RuntimeContext rc = context == null ? RuntimeContext.empty() : context;
        return filesystem.sessionStorage(rc).listPaths("agents/").stream()
                .filter(path -> path.endsWith("/session.json"))
                .map(path -> path.split("/"))
                .filter(
                        parts ->
                                (parts.length == 6 || parts.length == 7 && parts[5].equals("inbox"))
                                        && parts[2].equals("sessions"))
                .map(
                        parts ->
                                new SessionKey(
                                        decodeUser(parts[3]), decode(parts[1]), decode(parts[4])))
                .filter(key -> Objects.equals(key.userId(), normalizeUser(rc.getUserId())))
                .distinct()
                .sorted(
                        Comparator.comparing(SessionKey::agentId)
                                .thenComparing(SessionKey::sessionId))
                .toList();
    }

    private static String normalizeUser(String user) {
        return user == null || user.isEmpty() ? null : user;
    }

    private static String decodeUser(String segment) {
        return normalizeUser(decode(segment));
    }

    private static String decode(String segment) {
        if (!segment.startsWith("s_")) throw new IllegalArgumentException("Invalid session key");
        return new String(
                Base64.getUrlDecoder().decode(segment.substring(2)), StandardCharsets.UTF_8);
    }

    @Override
    public SessionLog open(SessionKey key, RuntimeContext context) {
        return new JournalSessionLog(
                filesystem.sessionStorage(context == null ? RuntimeContext.empty() : context),
                key.storagePath());
    }
}
