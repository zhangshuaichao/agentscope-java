/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.tools;

import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 {@link ToolsConfig#getAllow() allow} / {@link ToolsConfig#getDeny() deny} 列表
 * 从 {@code workspace/tools.json} 或 Managed Agents 工具集导出应用到 {@link Toolkit}。
 *
 * <p>语义：
 * <ul>
 * <li>{@code allow} 非空时，移除未列出的<em>目录内</em>工具。</li>
 * <li>{@link HarnessPlatformTools} 名称始终不受 {@code allow} 排除，以维持 Harness 运行时工作；
 * 显式 {@code deny} 仍可移除它们。</li>
 * <li>{@code deny} 中的名称始终移除，不受 {@code allow} 影响。</li>
 * <li>两者均为空或缺失时，不修改工具集。</li>
 * </ul>
 *
 * <p>名称未对应当前已注册工具时记录 WARN，除此之外忽略；工作区文件的拼写错误不应中止智能体。
 *
 * <p>【学习提示】allow 不是覆盖所有工具的绝对安全沙箱：平台工具有豁免，MCP 工具有自己的选择规则。
 * 显式 deny 与工具执行层的授权检查仍需分别考虑。
 */
public final class ToolFilter {

    private static final Logger log = LoggerFactory.getLogger(ToolFilter.class);

    private ToolFilter() {}

    /**
     * 判断名称为 {@code name} 的工具能否通过 {@code cfg} 的 allow/deny 规则。
     *
     * <p>构造提示词需要判断过滤后工具是否可用时，可使用此方法。
     */
    public static boolean isAllowed(String name, ToolsConfig cfg) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (cfg == null) {
            return true;
        }
        List<String> deny = cfg.getDeny();
        if (deny != null && deny.contains(name)) {
            return false;
        }
        List<String> allow = cfg.getAllow();
        // MCP 工具有独立的逐服务器选择规则，不依赖内置工具默认值。
        if (!cfg.isStrictAllow()
                && cfg.getMcpServers() != null
                && cfg.getMcpServers().entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getValue().isPrefixToolNames()
                                                && name.startsWith(e.getKey() + "__"))) {
            return true;
        }
        return (!cfg.isStrictAllow() && HarnessPlatformTools.isPlatformTool(name))
                || (allow != null && allow.contains(name))
                || (cfg.isDefaultToolsEnabled() && (allow == null || allow.isEmpty()));
    }

    /**
     * 从 {@code toolkit} 中移除被 {@code cfg} 的 allow/deny 列表排除的工具。
     * 若配置为 {@code null}，或 {@code cfg} 没有 allow/deny 条目，则不操作。
     */
    public static void apply(Toolkit toolkit, ToolsConfig cfg) {
        if (toolkit == null || cfg == null) {
            return;
        }
        List<String> allow = cfg.getAllow();
        List<String> deny = cfg.getDeny();
        boolean allowSet = allow != null && !allow.isEmpty();
        boolean denySet = deny != null && !deny.isEmpty();
        if (!allowSet && !denySet && cfg.isDefaultToolsEnabled()) {
            return;
        }

        Set<String> registered = new LinkedHashSet<>(toolkit.getToolNames());
        Set<String> allowSetView = allowSet ? new HashSet<>(allow) : null;
        Set<String> denySetView = denySet ? new HashSet<>(deny) : null;

        if (allowSetView != null) {
            warnUnknown(allowSetView, registered, "allow");
        }
        if (denySetView != null) {
            warnUnknown(denySetView, registered, "deny");
        }

        Set<String> toRemove = new LinkedHashSet<>();
        Set<String> protectedKept = new LinkedHashSet<>();
        for (String name : registered) {
            if (!isAllowed(name, cfg)) {
                toRemove.add(name);
            } else if (allowSetView != null
                    && !allowSetView.contains(name)
                    && HarnessPlatformTools.isPlatformTool(name)) {
                protectedKept.add(name);
            }
        }
        for (String name : toRemove) {
            toolkit.removeTool(name);
        }

        Set<String> remaining = new TreeSet<>(toolkit.getToolNames());
        if (protectedKept.isEmpty()) {
            log.info(
                    "tools.json filter applied: removed {} tool(s); {} tool(s) remain: {}",
                    toRemove.size(),
                    remaining.size(),
                    remaining);
        } else {
            log.info(
                    "tools.json filter applied: removed {} tool(s); kept {} platform tool(s)"
                            + " outside allow; {} tool(s) remain: {}",
                    toRemove.size(),
                    protectedKept.size(),
                    remaining.size(),
                    remaining);
        }
    }

    private static void warnUnknown(Set<String> declared, Set<String> registered, String which) {
        for (String name : declared) {
            if (!registered.contains(name)) {
                log.warn(
                        "tools.json '{}' references unknown tool '{}' (not currently registered).",
                        which,
                        name);
            }
        }
    }
}
