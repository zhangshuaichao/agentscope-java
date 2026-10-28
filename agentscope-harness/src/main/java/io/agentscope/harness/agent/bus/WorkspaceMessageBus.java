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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 基于 {@link AbstractFilesystem} 的 {@link MessageBus} 实现。
 *
 * <p>适用于本地、远程和沙箱后端。跨进程交付要求后端真正共享，例如远程或 KV 存储；
 * 纯本地磁盘实现不提供跨机器共享能力。
 *
 * <p>模式 D 的发布订阅退化为轮询：{@link #subscribe} 返回的 {@code Flux} 默认每 3 秒发出空信号，
 * 可由构造器配置间隔；{@link #publish} 不执行操作。
 *
 * <p>【学习提示】queueDrain 实际执行列目录、读取、删除，并没有跨进程锁或原子认领。
 * 因此不要把接口描述理解为该文件实现保证并发消费者之间的恰好一次处理。
 * Mono.fromCallable 只延迟文件操作，不自动切换线程；阻塞后端仍需调用方安排调度器。
 *
 * <p>{@code busRoot} 下的文件布局：
 * <pre>
 * {busRoot}/
 *   queues/{key-hash}/
 *     {entryId}.json       — Mode A entries (drain = ls + read + delete)
 *   logs/{key-hash}/
 *     {entryId}.json       — Mode C entries (append-only)
 * </pre>
 *
 * <p>目录图中文说明：queues 下存放模式 A 的队列项，消费过程为列目录、读取、删除；logs 下存放模式 C 的追加式日志项。两个 key-hash 目录按通道键区分，entryId 标识消息项。
 */
public class WorkspaceMessageBus implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMessageBus.class);
    private static final RuntimeContext RC = RuntimeContext.empty();
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(3);

    private final AbstractFilesystem fs;
    private final String busRoot;
    private final Duration pollInterval;
    private final AtomicLong seq = new AtomicLong(System.nanoTime());

    /**
     * @param filesystem 任意 {@link AbstractFilesystem} 实现
     * @param busRoot 文件系统中保存总线数据的绝对路径，例如 {@code "/bus"}
     */
    public WorkspaceMessageBus(AbstractFilesystem filesystem, String busRoot) {
        this(filesystem, busRoot, DEFAULT_POLL_INTERVAL);
    }

    /**
     * @param filesystem 任意 {@link AbstractFilesystem} 实现
     * @param busRoot 文件系统中保存总线数据的绝对路径，例如 {@code "/bus"}
     * @param pollInterval {@link #subscribe} 心跳间隔；测试可设较小值，避免等待生产默认的 3 秒粒度
     */
    public WorkspaceMessageBus(
            AbstractFilesystem filesystem, String busRoot, Duration pollInterval) {
        this.fs = filesystem;
        this.busRoot = busRoot.endsWith("/") ? busRoot.substring(0, busRoot.length() - 1) : busRoot;
        this.pollInterval =
                pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()
                        ? DEFAULT_POLL_INTERVAL
                        : pollInterval;
    }

    // ---- 模式 A：消费并移除的队列 ----

    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return Mono.fromCallable(
                () -> {
                    String entryId = nextEntryId();
                    String dir = queueDir(key);
                    ensureDir(dir);
                    String path = dir + "/" + entryId + ".json";
                    String json = JsonUtils.getJsonCodec().toJson(payload);
                    fs.write(RC, path, json);
                    return entryId;
                });
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        return Mono.fromCallable(
                () -> {
                    String dir = queueDir(key);
                    List<FileInfo> files = listSorted(dir);
                    List<BusEntry> result = new ArrayList<>();
                    int count = Math.min(maxCount, files.size());
                    for (int i = 0; i < count; i++) {
                        FileInfo fi = files.get(i);
                        String content = readFileContent(fi.path());
                        if (content == null) {
                            // 临时读取失败，例如并发消费竞争或 I/O 错误。
                            // 不要删除；保留记录，让下次消费重试。
                            log.warn(
                                    "queueDrain: failed to read entry {}, skipping without delete",
                                    fi.path());
                            continue;
                        }
                        String entryId = extractEntryId(fi.path());
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload =
                                    JsonUtils.getJsonCodec().fromJson(content, Map.class);
                            result.add(new BusEntry(entryId, payload));
                        } catch (Exception e) {
                            // 损坏的 JSON 无法恢复；删除它以免持续阻塞队列，
                            // 同时记录日志，便于定位同一坏记录导致的循环。
                            log.warn(
                                    "queueDrain: corrupt JSON in entry {} ({}), deleting to"
                                            + " unblock queue",
                                    fi.path(),
                                    e.getMessage());
                            fs.delete(RC, fi.path());
                            continue;
                        }
                        fs.delete(RC, fi.path());
                    }
                    return result;
                });
    }

    @Override
    public Mono<Void> queueDelete(String key) {
        return Mono.fromRunnable(() -> fs.delete(RC, queueDir(key)));
    }

    @Override
    public Mono<Boolean> queuePeek(String key) {
        return Mono.fromCallable(() -> !listSorted(queueDir(key)).isEmpty());
    }

    // ---- 模式 C：可重放日志 ----

    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return Mono.fromCallable(
                () -> {
                    String entryId = nextEntryId();
                    String dir = logDir(key);
                    ensureDir(dir);
                    String path = dir + "/" + entryId + ".json";
                    String json = JsonUtils.getJsonCodec().toJson(payload);
                    fs.write(RC, path, json);

                    if (maxLen > 0) {
                        List<FileInfo> files = listSorted(dir);
                        if (files.size() > maxLen) {
                            int excess = files.size() - maxLen;
                            for (int i = 0; i < excess; i++) {
                                fs.delete(RC, files.get(i).path());
                            }
                        }
                    }
                    return entryId;
                });
    }

    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        return Mono.fromCallable(
                () -> {
                    String dir = logDir(key);
                    List<FileInfo> files = listSorted(dir);
                    if (files.isEmpty()) {
                        return List.<BusEntry>of();
                    }

                    int startIdx = 0;
                    if (since != null) {
                        for (int i = 0; i < files.size(); i++) {
                            if (extractEntryId(files.get(i).path()).equals(since)) {
                                startIdx = i + 1;
                                break;
                            }
                        }
                    }

                    List<BusEntry> result = new ArrayList<>();
                    int endIdx = Math.min(startIdx + maxCount, files.size());
                    for (int i = startIdx; i < endIdx; i++) {
                        FileInfo fi = files.get(i);
                        String content = readFileContent(fi.path());
                        if (content != null) {
                            String entryId = extractEntryId(fi.path());
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload =
                                    JsonUtils.getJsonCodec().fromJson(content, Map.class);
                            result.add(new BusEntry(entryId, payload));
                        }
                    }
                    return result;
                });
    }

    @Override
    public Mono<Void> logTrim(String key) {
        return Mono.fromRunnable(() -> fs.delete(RC, logDir(key)));
    }

    // ---- 模式 D：退化的发布订阅 ----

    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return Mono.empty();
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        return Flux.interval(pollInterval, Schedulers.boundedElastic())
                .map(tick -> Map.<String, Object>of());
    }

    // ---- 内部辅助方法 ----

    private String queueDir(String key) {
        return busRoot + "/queues/" + hashKey(key);
    }

    private String logDir(String key) {
        return busRoot + "/logs/" + hashKey(key);
    }

    private String nextEntryId() {
        return String.format("%020d", seq.incrementAndGet());
    }

    static String hashKey(String key) {
        int hash = key.hashCode() & 0x7fffffff;
        return key.replaceAll("[^a-zA-Z0-9._-]", "_") + "_" + Integer.toHexString(hash);
    }

    private String extractEntryId(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".json")) {
            return name.substring(0, name.length() - 5);
        }
        return name;
    }

    private List<FileInfo> listSorted(String dir) {
        if (!fs.exists(RC, dir)) {
            return List.of();
        }
        LsResult ls = fs.ls(RC, dir);
        if (!ls.isSuccess() || ls.entries() == null) {
            return List.of();
        }
        return ls.entries().stream()
                .filter(f -> !f.isDirectory() && f.path().endsWith(".json"))
                .sorted(Comparator.comparing(FileInfo::path))
                .toList();
    }

    private String readFileContent(String path) {
        ReadResult rr = fs.read(RC, path, 0, Integer.MAX_VALUE);
        if (!rr.isSuccess() || rr.fileData() == null) {
            return null;
        }
        return rr.fileData().content();
    }

    private void ensureDir(String dir) {
        if (!fs.exists(RC, dir)) {
            fs.write(RC, dir + "/.keep", "");
        }
    }
}
