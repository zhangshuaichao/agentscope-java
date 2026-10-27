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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 {@code workspace/tools.json} 加载的工作区级工具配置。
 *
 * <p>承担两个职责：
 * <ul>
 * <li>{@link #allow} / {@link #deny}：过滤<em>产品目录</em>中的工具，如文件系统、Shell、Web 等。</li>
 * <li>{@link #mcpServers}：声明外部 MCP 服务器提供的附加工具。</li>
 * </ul>
 *
 * <p>过滤语义：{@code allow} 非空时，移除未列出的目录内工具；
 * {@link HarnessPlatformTools}（子智能体、团队、任务、计划、技能、记忆辅助等）
 * 始终不受 {@code allow} 排除。{@code deny} 始终优先。值为空或缺失表示“不在这一侧进行过滤”。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolsConfig {

    /** 非空时隐藏未列出的目录内工具，平台工具豁免。 */
    @JsonProperty("allow")
    private List<String> allow;

    /** 名称出现在此列表中的工具一律移除，不受 {@link #allow} 影响。 */
    @JsonProperty("deny")
    private List<String> deny;

    /** MCP 服务器标识到连接及工具允许列表配置的映射。 */
    @JsonProperty("mcpServers")
    private Map<String, McpServerConfig> mcpServers;

    private boolean defaultToolsEnabled = true;
    private boolean strictAllow;

    public boolean isStrictAllow() {
        return strictAllow;
    }

    public void setStrictAllow(boolean value) {
        strictAllow = value;
    }

    public boolean isDefaultToolsEnabled() {
        return defaultToolsEnabled;
    }

    public void setDefaultToolsEnabled(boolean value) {
        defaultToolsEnabled = value;
    }

    public List<String> getAllow() {
        return allow;
    }

    public void setAllow(List<String> allow) {
        this.allow = allow;
    }

    public List<String> getDeny() {
        return deny;
    }

    public void setDeny(List<String> deny) {
        this.deny = deny;
    }

    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers != null ? new LinkedHashMap<>(mcpServers) : null;
    }
}
