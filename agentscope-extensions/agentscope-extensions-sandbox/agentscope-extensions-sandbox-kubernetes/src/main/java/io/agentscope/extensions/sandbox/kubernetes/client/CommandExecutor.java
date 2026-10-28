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
package io.agentscope.extensions.sandbox.kubernetes.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxRequestException;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.SandboxConnector;
import io.agentscope.extensions.sandbox.kubernetes.client.model.ExecutionResult;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 通过 {@code POST /execute} 在沙箱内执行命令。
 *
 * <p>【学习提示】当前 run(command, timeout) 的 timeout 参数未传给连接器，也未发送给远程进程；实际 HTTP 单次超时由连接器配置决定，且此处固定只尝试一次。因此不能依靠本参数保证命令在给定时长后被远程终止。
 */
public class CommandExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final SandboxConnector connector;

    public CommandExecutor(SandboxConnector connector) {
        this.connector = connector;
    }

    /**
     * 使用默认 60 秒参数调用重载；当前实现尚未应用该参数。
     *
     * @param command shell 命令
     * @return 执行结果
     */
    public ExecutionResult run(String command) {
        return run(command, DEFAULT_TIMEOUT);
    }

    /**
     * 运行命令；保留给定超时参数的接口，但当前实现未使用它。
     *
     * @param command shell 命令
     * @param timeout 请求超时参数（当前未使用）
     * @return 执行结果
     */
    public ExecutionResult run(String command, Duration timeout) {
        try {
            String json = MAPPER.writeValueAsString(Map.of("command", command));
            HttpRequest.BodyPublisher body =
                    HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
            HttpResponse<String> resp =
                    connector.sendRequest("POST", "execute", body, "application/json", 1);

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new SandboxRequestException(
                        "Execute failed (status="
                                + resp.statusCode()
                                + "): "
                                + truncate(resp.body(), 256),
                        resp.statusCode());
            }
            return MAPPER.readValue(resp.body(), ExecutionResult.class);
        } catch (SandboxRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxRequestException(
                    "Failed to execute command: " + e.getMessage(), null, e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
