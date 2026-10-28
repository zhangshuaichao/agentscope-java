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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 显式启用的真实沙箱诊断：仅当环境设置 E2B_API_KEY 时运行，
// 遵循仓库端到端测试约定。
@EnabledIfEnvironmentVariable(named = "E2B_API_KEY", matches = ".+")
class E2bEnvdProcessClientIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(E2bEnvdProcessClientIntegrationTest.class);

    @Test
    void signalKillViaRunShell() throws Exception {
        String apiKey = System.getenv("E2B_API_KEY");
        String templateId = System.getProperty("E2B_TEMPLATE_ID", "code-interpreter-v1");

        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setApiKey(apiKey);
        opt.setTemplateId(templateId);
        // 读取超时需足以覆盖 sleep 120 及处理时间
        opt.setReadTimeoutSeconds(180);
        opt.setConnectTimeoutSeconds(30);

        E2bPlatformHttp platform = new E2bPlatformHttp(opt);
        E2bSandboxState state = new E2bSandboxState();
        state.setTemplateId(templateId);

        JsonNode sandboxNode = platform.createSandbox(templateId, 120);
        platform.applySandboxFields(state, sandboxNode);
        state.setSandboxDomain(opt.getDomain());

        // 绝不记录访问令牌本身，它是 bearer 凭据；只记录是否存在。
        log.info(
                "sandboxId={} domain={} envdAccessTokenPresent={}",
                state.getSandboxId(),
                state.getSandboxDomain(),
                state.getEnvdAccessToken() != null && !state.getEnvdAccessToken().isBlank());

        try {
            E2bEnvdProcessClient client = new E2bEnvdProcessClient(opt);

            // 线程 A：运行 sleep 120，阻塞直到被终止
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> future =
                        executor.submit(
                                () -> {
                                    client.runShell(state, "/home/user", "sleep 120", 0);
                                    return null;
                                });

                // 稍等 sleep 启动，再用 SIGKILL 终止。
                // 终止子 sleep 进程而非 bash，让 bash 向 Go 处理器报告
                // 128+9=137；按精确名称执行 killall。
                Thread.sleep(3000);
                try {
                    client.runShell(state, "/home/user", "killall -9 -w sleep 2>/dev/null", 10);
                } catch (Exception e) {
                    log.warn("killall failed: {}", e.getMessage());
                    try {
                        client.runShell(
                                state,
                                "/home/user",
                                "kill -9 $(ps h -C sleep -o pid 2>/dev/null) 2>/dev/null",
                                10);
                    } catch (Exception e2) {
                        log.warn("ps kill also failed: {}", e2.getMessage());
                    }
                }

                // 线程 A 应以 ExecException 结束，进程被信号终止
                ExecutionException ex =
                        assertThrows(
                                ExecutionException.class, () -> future.get(180, TimeUnit.SECONDS));
                Throwable cause = ex.getCause();
                assertTrue(
                        cause instanceof SandboxException,
                        "Expected SandboxException, got: "
                                + cause.getClass().getName()
                                + ": "
                                + cause.getMessage());
                SandboxException.ExecException execEx = (SandboxException.ExecException) cause;
                // 验证信号终止：EndEvent.error 捕获到 stderr，
                // 如 "signal: killed"，且退出码非零；Go 的
                // ProcessState.ExitCode() 在 !Exited() 时预期为 -1。
                assertTrue(
                        execEx.getExitCode() != 0,
                        "Exit code should be non-zero after SIGKILL, got " + execEx.getExitCode());
                assertTrue(
                        execEx.getStderr().contains("signal: killed"),
                        "stderr should contain 'signal: killed' from EndEvent.error, got: '"
                                + execEx.getStderr()
                                + "'");
            } finally {
                executor.shutdownNow();
            }
        } finally {
            try {
                platform.killSandbox(state.getSandboxId());
                log.info("sandbox {} killed", state.getSandboxId());
            } catch (Exception e) {
                log.warn("failed to kill sandbox {}: {}", state.getSandboxId(), e.getMessage());
            }
        }
    }
}
