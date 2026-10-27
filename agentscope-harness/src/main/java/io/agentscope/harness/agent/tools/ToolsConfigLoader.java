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
package io.agentscope.harness.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 {@code workspace/tools.json} 加载为 {@link ToolsConfig}。
 *
 * <p>通过 {@link WorkspaceManager#readManagedWorkspaceFileUtf8} 读取，使沙箱/远程文件系统叠加路径
 * 与 {@code AGENTS.md} 一样得到遵守。解析前，在原始 JSON 文本上对 {@code ${ENV_VAR}}
 * 根据 {@link System#getenv} 执行替换，使请求头、环境变量项、URL 等中的密钥无需直接写入文件。
 * 解析失败不抛出异常，而是记录日志并返回 {@link Optional#empty()}，使智能体仍可使用默认工具集构建。
 *
 * <p>【学习提示】环境变量替换只是减少文件中的明文凭证，不等于运行时数据已脱敏。
 * 配置缺失或解析失败会回退默认工具集，不能把配置加载失败误认为“全部工具已禁用”。
 */
public final class ToolsConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ToolsConfigLoader.class);

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private ToolsConfigLoader() {}

    /**
     * 读取 {@code tools.json}，路径相对于 {@code wsManager} 管理的工作区。
     * 文件缺失、空白、不可读（例如尚无调用上下文时访问沙箱文件系统）或无法解析时，
     * 返回 {@link Optional#empty()}。
     */
    public static Optional<ToolsConfig> load(WorkspaceManager wsManager) {
        if (wsManager == null) {
            return Optional.empty();
        }
        String raw;
        try {
            raw =
                    wsManager.readManagedWorkspaceFileUtf8(
                            RuntimeContext.empty(), WorkspaceConstants.TOOLS_JSON);
        } catch (Exception e) {
            // 构建时沙箱或远程文件系统可能不可达，静默回退，
            // 让智能体仍可完成构建。
            log.debug(
                    "Could not read {} via workspace manager ({}); skipping.",
                    WorkspaceConstants.TOOLS_JSON,
                    e.getMessage());
            return Optional.empty();
        }
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String substituted = substituteEnv(raw);
        try {
            ToolsConfig cfg = MAPPER.readValue(substituted, ToolsConfig.class);
            return Optional.ofNullable(cfg);
        } catch (Exception e) {
            log.warn(
                    "Failed to parse {}: {}. Falling back to default toolkit.",
                    WorkspaceConstants.TOOLS_JSON,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 对 {@code ${VAR}} 占位符执行替换，将 {@code raw} 中的对应内容换为 {@link System#getenv} 的值。
     * 未设置的变量替换为空字符串并记录 WARN，
     * 使 {@code "Authorization": "Bearer ${GITHUB_TOKEN}"} 这样的配置无需将实际密钥写入工作区文件。
     */
    static String substituteEnv(String raw) {
        Matcher m = ENV_VAR.matcher(raw);
        StringBuilder out = new StringBuilder(raw.length());
        Set<String> warned = new HashSet<>();
        while (m.find()) {
            String var = m.group(1);
            String value = System.getenv(var);
            if (value == null) {
                if (warned.add(var)) {
                    log.warn(
                            "tools.json references env var ${{{}}} which is not set; substituting"
                                    + " empty string.",
                            var);
                }
                value = "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
