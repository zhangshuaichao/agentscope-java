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
package io.agentscope.harness.coding.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.coding.prompt.ReviewerSystemPrompt;
import java.nio.file.Path;

/**
 * 审查智能体工厂。
 * <ul>
 * <li>使用带 PR 上下文的审查系统提示词</li>
 * <li>仅接收审查专用工具（add_finding、update_finding、list_findings、publish_review、github_api_request、fetch_url、execute，不提供专门的写入/提交工具）</li>
 * <li>在与编码智能体分离的独立工作区运行</li>
 * </ul>
 *
 * <p>【学习提示】“审查角色”及提示词中的只读要求不等于技术上的只读沙箱。工具集中仍有通用 execute/GitHub API，发布审查也是远程写入；真正限制写操作需靠工具实现、权限策略、凭据范围和运行环境，不能仅从工具名称或独立工作区推导安全保证。
 */
public final class ReviewerAgentFactory {

    private ReviewerAgentFactory() {}

    /**
     * 使用默认设置为给定工作区构建审查智能体。
     */
    public static HarnessAgent create(Path workspace, Toolkit reviewerToolkit) {
        return create(workspace, reviewerToolkit, null, null, 0);
    }

    /**
     * 构建将 PR 上下文嵌入系统提示词的审查智能体。
     *
     * @param workspace 审查智能体工作区目录
     * @param reviewerToolkit 仅包含审查专用工具的工具集
     * @param repoOwner GitHub 仓库所有者
     * @param repoName GitHub 仓库名
     * @param prNumber PR 编号
     */
    public static HarnessAgent create(
            Path workspace,
            Toolkit reviewerToolkit,
            String repoOwner,
            String repoName,
            int prNumber) {
        Model model = CodingAgentFactory.buildModel();
        String workingDir = workspace.toAbsolutePath().toString();
        String sysPrompt = ReviewerSystemPrompt.build(workingDir, repoOwner, repoName, prNumber);

        return HarnessAgent.builder()
                .name("agentscope-reviewer-agent")
                .model(model)
                .sysPrompt(sysPrompt)
                .workspace(workspace)
                .toolkit(reviewerToolkit != null ? reviewerToolkit : new Toolkit())
                .maxIters(resolveMaxIters())
                .disableSubagents()
                .build();
    }

    private static int resolveMaxIters() {
        String env = System.getenv("REVIEWER_MAX_ITERS");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                // 忽略异常
            }
        }
        return 30;
    }
}
