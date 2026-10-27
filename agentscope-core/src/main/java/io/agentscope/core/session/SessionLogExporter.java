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

/** 持久化发件箱游标。ACK 丢失时重新投递同一个不可变事件，绝不重新执行它。 */
public final class SessionLogExporter {
    private final SessionLog log;
    private final SessionExportSink sink;

    public SessionLogExporter(SessionLog log, SessionExportSink sink) {
        this.log = log;
        this.sink = sink;
    }

    public synchronized void drain() {
        long cursor = log.exportCursor(sink.name());
        for (var event : log.scan(cursor, log.head().seq())) {
            // 【学习提示】先确认接收，再推进导出游标。若二者之间发生故障，
            // 下次会重复投递同一事件，因此接收端必须按 eventId 去重，而不是重做工具操作。
            sink.accept(event);
            log.advanceExportCursor(sink.name(), cursor, event.seq());
            cursor = event.seq();
        }
    }
}
