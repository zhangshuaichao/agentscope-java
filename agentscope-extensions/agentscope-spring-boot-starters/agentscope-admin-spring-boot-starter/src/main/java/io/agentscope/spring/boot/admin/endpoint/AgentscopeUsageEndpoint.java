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
package io.agentscope.spring.boot.admin.endpoint;

import io.agentscope.spring.boot.admin.metrics.MetricsRecorder;
import io.agentscope.spring.boot.admin.metrics.UsageStats;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code GET /actuator/agentscope-usage}：按智能体名和模型名分组的进程累计词元用量。
 *
 * <p>计数仅在进程内，JVM 重启即重置。长期计费应把派生的 {@link io.agentscope.spring.boot.admin.audit.AdminAuditEvent} 送入数据仓库，而不能依赖此端点保存长期记录。
 */
@Endpoint(id = "agentscope-usage")
public class AgentscopeUsageEndpoint {

    private final MetricsRecorder recorder;

    public AgentscopeUsageEndpoint(MetricsRecorder recorder) {
        this.recorder = recorder;
    }

    @ReadOperation
    public Map<String, Object> usage() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("global", recorder.globalSnapshot());
        out.put("by_agent", recorder.snapshotByAgent());
        out.put("by_model", recorder.snapshotByModel());
        return out;
    }

    /**
 * 便于调用方仅查询单个智能体。
 */
    @ReadOperation
    public UsageStats forAgent(
            @org.springframework.boot.actuate.endpoint.annotation.Selector String agentName) {
        Map<String, UsageStats> snapshot = recorder.snapshotByAgent();
        return snapshot.getOrDefault(agentName, UsageStats.zero(agentName));
    }
}
