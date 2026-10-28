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

import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.ShutdownState;
import io.agentscope.spring.boot.admin.audit.AdminAuditLogger;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * {@code POST /actuator/agentscope-drain}：停止接收新智能体请求，等待在途请求完成，但不终止 JVM。
 *
 * <p>这是生产环境将节点移出负载均衡的控制开关：健康探针（例如 {@code /actuator/health}）可转为失败，在途推理自然完成，进程继续存活以供收集日志。
 *
 * <p>排空操作幂等，第二次调用为空操作。由于 Actuator {@code @WriteOperation} 不直接暴露原始 HTTP 请求头，写保护通过方法参数检查；{@code confirm} 用于明确确认，令牌另行校验。生产部署应在 {@code /actuator/**} 前配置 Spring Security。
 */
@Endpoint(id = "agentscope-drain")
public class AgentscopeDrainEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeDrainEndpoint.class);

    private final AdminProperties properties;
    private final AdminAuditLogger audit;

    public AgentscopeDrainEndpoint(AdminProperties properties, AdminAuditLogger audit) {
        this.properties = properties;
        this.audit = audit;
    }

    @ReadOperation
    public Map<String, Object> currentDrainStatus() {
        GracefulShutdownManager m = GracefulShutdownManager.getInstance();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", m.getState().name());
        out.put("accepting_requests", m.isAcceptingRequests());
        out.put("active_requests", m.getActiveRequestCount());
        return out;
    }

    @WriteOperation
    public Map<String, Object> drain(String confirm, String token) {
        WriteGuardSupport.check(properties, token, confirm);
        GracefulShutdownManager m = GracefulShutdownManager.getInstance();
        ShutdownState stateBefore = m.getState();
        boolean triggered = false;
        if (stateBefore == ShutdownState.RUNNING) {
            triggered = m.performGracefulShutdown();
            log.warn(
                    "agentscope-drain invoked; performGracefulShutdown returned {}; active={}",
                    triggered,
                    m.getActiveRequestCount());
        } else {
            log.info("agentscope-drain invoked but already in state {}", stateBefore);
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("state_before", stateBefore.name());
        attrs.put("triggered", triggered);
        audit.record(
                "system.drain",
                /* 操作者 */ null,
                /* 目标 */ "process",
                /* 写操作 */ true,
                /* 结果 */ "ok",
                attrs);
        Map<String, Object> out = new LinkedHashMap<>(attrs);
        out.put("state_after", m.getState().name());
        out.put("active_requests", m.getActiveRequestCount());
        return out;
    }
}
