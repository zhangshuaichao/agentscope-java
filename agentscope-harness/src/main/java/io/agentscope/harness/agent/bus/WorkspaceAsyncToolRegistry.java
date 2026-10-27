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
package io.agentscope.harness.agent.bus;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 基于 {@link AbstractFilesystem} 的 {@link AsyncToolRegistry}。
 *
 * <p>每条记录保存为 {@code {registryRoot}/async-tools/{sessionId}/{recordId}.json}。
 * 后端保留这些文件时，记录可跨进程崩溃保留；
 * {@link io.agentscope.harness.agent.middleware.InboxMiddleware} 在恢复会话时检测过期 RUNNING 记录并通知模型。
 *
 * <p>适用于本地、远程和沙箱文件系统。
 *
 * <p>【学习提示】complete/fail 这里只更新状态，不保存 result/error 文本；结果通知由上层负责。
 * 状态更新先删除再写入，并非事务性原地替换，不能据此推断跨进程恰好一次语义。
 */
public class WorkspaceAsyncToolRegistry implements AsyncToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAsyncToolRegistry.class);
    private static final RuntimeContext RC = RuntimeContext.empty();

    private final AbstractFilesystem fs;
    private final String registryRoot;

    /**
     * @param filesystem 任意 {@link AbstractFilesystem} 实现
     * @param registryRoot 文件系统内的绝对路径，例如 {@code "/bus/async-tools"}
     */
    public WorkspaceAsyncToolRegistry(AbstractFilesystem filesystem, String registryRoot) {
        this.fs = filesystem;
        this.registryRoot =
                registryRoot.endsWith("/")
                        ? registryRoot.substring(0, registryRoot.length() - 1)
                        : registryRoot;
    }

    @Override
    public Mono<Void> register(AsyncToolRecord record) {
        return Mono.fromRunnable(
                () -> {
                    String path = recordPath(record.sessionId(), record.id());
                    ensureDir(sessionDir(record.sessionId()));
                    Map<String, Object> data =
                            Map.of(
                                    "id", record.id(),
                                    "sessionId", record.sessionId(),
                                    "toolName", record.toolName(),
                                    "toolCallId", record.toolCallId(),
                                    "status", record.status(),
                                    "createdAt", record.createdAt().toString());
                    fs.write(RC, path, JsonUtils.getJsonCodec().toJson(data));
                });
    }

    @Override
    public Mono<Void> complete(String id, String result) {
        return updateStatus(id, AsyncToolRecord.COMPLETED);
    }

    @Override
    public Mono<Void> fail(String id, String error) {
        return updateStatus(id, AsyncToolRecord.FAILED);
    }

    @Override
    public Mono<Void> markTimeout(String id) {
        return updateStatus(id, AsyncToolRecord.TIMEOUT);
    }

    @Override
    public Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl) {
        return Mono.fromCallable(
                () -> {
                    String dir = sessionDir(sessionId);
                    if (!fs.exists(RC, dir)) {
                        return List.<AsyncToolRecord>of();
                    }
                    LsResult ls = fs.ls(RC, dir);
                    if (!ls.isSuccess() || ls.entries() == null) {
                        return List.<AsyncToolRecord>of();
                    }
                    Instant cutoff = Instant.now().minus(ttl);
                    List<AsyncToolRecord> stale = new ArrayList<>();
                    for (FileInfo fi : ls.entries()) {
                        if (fi.isDirectory() || !fi.path().endsWith(".json")) {
                            continue;
                        }
                        AsyncToolRecord rec = readRecord(fi.path());
                        if (rec != null
                                && AsyncToolRecord.RUNNING.equals(rec.status())
                                && rec.createdAt().isBefore(cutoff)) {
                            stale.add(rec);
                        }
                    }
                    return stale;
                });
    }

    private Mono<Void> updateStatus(String id, String newStatus) {
        return Mono.fromRunnable(
                () -> {
                    String path = findRecordPath(id);
                    if (path == null) {
                        return;
                    }
                    AsyncToolRecord rec = readRecord(path);
                    if (rec == null) {
                        return;
                    }
                    fs.delete(RC, path);
                    Map<String, Object> data =
                            Map.of(
                                    "id", rec.id(),
                                    "sessionId", rec.sessionId(),
                                    "toolName", rec.toolName(),
                                    "toolCallId", rec.toolCallId(),
                                    "status", newStatus,
                                    "createdAt", rec.createdAt().toString());
                    fs.write(RC, path, JsonUtils.getJsonCodec().toJson(data));
                });
    }

    private AsyncToolRecord readRecord(String path) {
        ReadResult rr = fs.read(RC, path, 0, Integer.MAX_VALUE);
        if (!rr.isSuccess() || rr.fileData() == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data =
                    JsonUtils.getJsonCodec().fromJson(rr.fileData().content(), Map.class);
            return new AsyncToolRecord(
                    (String) data.get("id"),
                    (String) data.get("sessionId"),
                    (String) data.get("toolName"),
                    (String) data.get("toolCallId"),
                    (String) data.get("status"),
                    Instant.parse((String) data.get("createdAt")));
        } catch (Exception e) {
            log.warn("Failed to parse async tool record at {}: {}", path, e.getMessage());
            return null;
        }
    }

    private String findRecordPath(String id) {
        LsResult rootLs = fs.ls(RC, registryRoot);
        if (!rootLs.isSuccess() || rootLs.entries() == null) {
            return null;
        }
        for (FileInfo sessionDir : rootLs.entries()) {
            if (!sessionDir.isDirectory()) {
                continue;
            }
            String candidate = sessionDir.path() + "/" + id + ".json";
            if (fs.exists(RC, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String sessionDir(String sessionId) {
        return registryRoot + "/" + sanitize(sessionId);
    }

    private String recordPath(String sessionId, String id) {
        return sessionDir(sessionId) + "/" + id + ".json";
    }

    private void ensureDir(String dir) {
        if (!fs.exists(RC, dir)) {
            fs.write(RC, dir + "/.keep", "");
        }
    }

    private static String sanitize(String s) {
        return s != null ? s.replaceAll("[^a-zA-Z0-9._-]", "_") : "default";
    }
}
