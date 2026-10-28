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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.harness.agent.sandbox.ExecResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 显式启用的真实沙箱诊断：仅环境设置 E2B_API_KEY
// 时运行，遵循仓库端到端测试约定；通过 E2B_API_URL /
// E2B_DOMAIN / E2B_TEMPLATE_ID 指向目标部署。
@EnabledIfEnvironmentVariable(named = "E2B_API_KEY", matches = ".+")
class E2bSandboxEnvsLiveTest {

    private static final Logger log = LoggerFactory.getLogger(E2bSandboxEnvsLiveTest.class);

    /** 创建时发送的环境变量必须对沙箱内执行的命令可见。 */
    @Test
    void envVarsAreVisibleToSandboxCommands() throws Exception {
        String apiKey = System.getenv("E2B_API_KEY");
        String templateId =
                System.getenv()
                        .getOrDefault(
                                "E2B_TEMPLATE_ID", System.getProperty("E2B_TEMPLATE_ID", "base"));

        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setApiKey(apiKey);
        opt.setTemplateId(templateId);
        // FC 的 envd 网关要求 Connect-JSON 帧，与 harness 部署配置一致。
        opt.setCodec(E2bCodec.JSON);
        String apiBaseUrl = System.getenv("E2B_API_URL");
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            opt.setApiBaseUrl(apiBaseUrl);
        }
        String domain = System.getenv("E2B_DOMAIN");
        if (domain != null && !domain.isBlank()) {
            opt.setDomain(domain);
        }

        E2bPlatformHttp platform = new E2bPlatformHttp(opt);
        E2bSandboxState state = new E2bSandboxState();
        state.setTemplateId(templateId);

        JsonNode sandboxNode =
                platform.createSandbox(
                        templateId,
                        120,
                        Map.of(
                                "AGENTSCOPE_ENVS_TEST", "sentinel-123",
                                "LARKSUITE_CLI_APP_ID", "cli_live_test"));
        platform.applySandboxFields(state, sandboxNode);
        state.setSandboxDomain(opt.getDomain());

        log.info(
                "sandboxId={} envdAccessTokenPresent={}",
                state.getSandboxId(),
                state.getEnvdAccessToken() != null && !state.getEnvdAccessToken().isBlank());

        try {
            E2bEnvdProcessClient client = new E2bEnvdProcessClient(opt);
            ExecResult result =
                    client.runShell(
                            state,
                            "/home/user",
                            "echo \"A=$AGENTSCOPE_ENVS_TEST B=$LARKSUITE_CLI_APP_ID\"",
                            30);
            assertEquals(0, result.exitCode());
            assertTrue(
                    result.stdout().contains("A=sentinel-123"),
                    "stdout should contain injected env value, got: " + result.stdout());
            assertTrue(
                    result.stdout().contains("B=cli_live_test"),
                    "stdout should contain injected env value, got: " + result.stdout());
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
