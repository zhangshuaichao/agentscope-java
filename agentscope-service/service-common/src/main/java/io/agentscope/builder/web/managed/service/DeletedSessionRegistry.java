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
package io.agentscope.builder.web.managed.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 跟踪工作仍在进行时被删除的会话。<p>中断 Harness 轮次不是即时的：会话行消失后轮次可能继续运行数秒，其尾部写入会重新创建清理刚移除的行。会话范围的写路径查询此注册表，并丢弃这些写入。<p>只保留最近的 ID。会话 ID 不复用，因此遗忘旧 ID 最坏会使极长时间运行的轮次重新写入。<p>【学习提示】这是当前 JVM 内有界的删除标记缓存，不是跨副本、永久保留的墓碑；不能把它当作数据库级删除约束。 */
@Component
public class DeletedSessionRegistry {

    private static final int MAX_TRACKED = 512;

    private final Map<String, Boolean> tracked =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(64, 0.75f, false) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > MAX_TRACKED;
                        }
                    });

    /** 记录 {@code sessionId} 已删除，此后的相关写入必须丢弃。 */
    public void markDeleted(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            tracked.put(sessionId, Boolean.TRUE);
        }
    }

    /** 应丢弃 {@code sessionId} 的写入时返回 true。 */
    public boolean isDeleted(String sessionId) {
        return sessionId != null && tracked.containsKey(sessionId);
    }
}
