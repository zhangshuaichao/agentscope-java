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
package io.agentscope.core.session;

import java.time.Duration;
import java.util.List;

/** 阻塞式持久化 SPI；运行时集成将其调度到 boundedElastic。 */
public interface SessionLog {
    /** 持久化输入日志，独立于执行写入者及其事件序列。 */
    default SessionLog inbox() {
        throw new UnsupportedOperationException(
                "This session backend does not support a durable inbox");
    }

    record Head(long seq, long epoch, String owner, long leaseUntil, String commitId) {}

    record Writer(String owner, long epoch) {}

    default long exportCursor(String sink) {
        return 0;
    }

    default void advanceExportCursor(String sink, long expected, long next) {
        throw new UnsupportedOperationException("Export cursor persistence not implemented");
    }

    /** 稳定前缀扫描。实现可避免反复遍历提交链。 */
    default Iterable<SessionEvent> scan(long after, long through) {
        return () ->
                new java.util.Iterator<SessionEvent>() {
                    long cursor = after;
                    java.util.Iterator<SessionEvent> batch = List.<SessionEvent>of().iterator();

                    public boolean hasNext() {
                        if (cursor >= through) return false;
                        if (!batch.hasNext())
                            batch =
                                    readAfter(cursor, (int) Math.min(256, through - cursor))
                                            .iterator();
                        if (!batch.hasNext())
                            throw new SessionLogException("Missing committed prefix");
                        return true;
                    }

                    public SessionEvent next() {
                        if (!hasNext()) throw new java.util.NoSuchElementException();
                        var event = batch.next();
                        cursor = event.seq();
                        return event;
                    }
                };
    }

    Head head();

    Writer acquire(String owner, Duration lease);

    /**
     * 永久隔离此写入者身份，包括尚未开始的租约获取。
     * 其他写入者身份可以继续此会话。返回已封存写入者的稳定前缀；
     * 实现必须将封存与租约获取、提交操作串行化。
     */
    default Head sealWriter(String owner) {
        throw new UnsupportedOperationException("This session backend cannot seal a writer");
    }

    void renew(Writer writer, Duration lease);

    void release(Writer writer);

    Head commit(Writer writer, String batchId, long expectedSeq, List<SessionEvent> events);

    List<SessionEvent> readAfter(long after, int limit);
}
