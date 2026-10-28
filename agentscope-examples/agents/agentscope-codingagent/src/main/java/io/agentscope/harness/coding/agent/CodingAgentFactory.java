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
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.coding.model.FallbackModel;
import io.agentscope.harness.coding.prompt.CodingSystemPrompt;
import java.nio.file.Path;

/**
 * 编码智能体工厂。
 * <ul>
 * <li>面向编码的系统提示词（工作区环境、仓库设置、通过 {@code github_api_request} 使用 GitHub）</li>
 * <li>自定义工具集（http、fetch_url、web_search、github_api_request、request_pr_review）</li>
 * <li>长会话压缩</li>
 * <li>由环境变量配置模型（{@code CODING_MODEL_ID}）</li>
 * <li>在阶段 2 接入沙箱集成</li>
 * </ul>
 */
public final class CodingAgentFactory {

    private CodingAgentFactory() {}

    /**
     * 使用默认设置为给定工作区构建编码智能体。
     */
    public static HarnessAgent create(Path workspace, Toolkit toolkit) {
        return create(workspace, toolkit, null);
    }

    /**
     * 构建编码智能体。
     *
     * @param workspace 智能体工作区目录
     * @param toolkit 为编码智能体注册的自定义工具
     * @param linearCtx 可选 Linear 上下文字符串（可为 null，延后处理）
     */
    public static HarnessAgent create(Path workspace, Toolkit toolkit, String linearCtx) {
        Model model = buildModel();
        String sandboxType = resolveSandboxType();
        String workingDir = resolveSandboxWorkingDir();
        String sysPrompt = CodingSystemPrompt.build(workingDir, linearCtx);

        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("agentscope-coding-agent")
                        .model(model)
                        .sysPrompt(sysPrompt)
                        .workspace(workspace)
                        .toolkit(toolkit != null ? toolkit : new Toolkit())
                        .maxIters(resolveMaxIters())
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(40)
                                        .keepMessages(15)
                                        .flushBeforeCompact(true)
                                        .build());

        if ("docker".equalsIgnoreCase(sandboxType)) {
            String image = resolveSandboxImage();
            DockerFilesystemSpec sandboxSpec = new DockerFilesystemSpec();
            sandboxSpec.image(image);
            sandboxSpec.workspaceRoot(workingDir);
            sandboxSpec.isolationScope(IsolationScope.SESSION);
            builder.filesystem(sandboxSpec);
        }

        return builder.build();
    }

    /**
     * 解析容器内沙箱工作目录。
     */
    public static String resolveSandboxWorkingDir() {
        String env = System.getenv("SANDBOX_WORK_DIR");
        return (env != null && !env.isBlank()) ? env : "/home/agentscope/workspace";
    }

    public static String resolveSandboxType() {
        String env = System.getenv("SANDBOX_TYPE");
        return (env != null && !env.isBlank()) ? env.toLowerCase() : "none";
    }

    public static String resolveSandboxImage() {
        String env = System.getenv("SANDBOX_IMAGE");
        return (env != null && !env.isBlank()) ? env : "agentscope/coding-sandbox:latest";
    }

    // -----------------------------------------------------------------
    // 模型选择
    // -----------------------------------------------------------------

    /**
     * 根据 {@code CODING_MODEL_ID} 环境变量构建模型。
     *
     * <p>支持前缀：{@code dashscope:<name>}（默认）、{@code openai:<name>}、{@code anthropic:<name>}。
     *
     * <p>同时设置 {@code FALLBACK_MODEL_ID} 时，以 {@link FallbackModel} 包装主模型，对可重试错误透明故障切换。
     */
    public static Model buildModel() {
        Model primary = buildModelById(resolveModelId());
        String fallbackId = System.getenv("FALLBACK_MODEL_ID");
        if (fallbackId != null && !fallbackId.isBlank()) {
            return new FallbackModel(primary, buildModelById(fallbackId.trim()));
        }
        return primary;
    }

    private static Model buildModelById(String modelId) {
        if (modelId.startsWith("openai:")) {
            String name = modelId.substring("openai:".length());
            String apiKey = System.getenv("OPENAI_API_KEY");
            String baseUrl = System.getenv("OPENAI_BASE_URL");
            OpenAIChatModel.Builder b =
                    OpenAIChatModel.builder().apiKey(apiKey).modelName(name).stream(true);
            if (baseUrl != null && !baseUrl.isBlank()) {
                b.baseUrl(baseUrl);
            }
            return b.build();
        }
        if (modelId.startsWith("anthropic:")) {
            String name = modelId.substring("anthropic:".length());
            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
            AnthropicChatModel.Builder b =
                    AnthropicChatModel.builder().apiKey(apiKey).modelName(name).stream(true);
            if (baseUrl != null && !baseUrl.isBlank()) {
                b.baseUrl(baseUrl);
            }
            return b.build();
        }
        String name =
                modelId.startsWith("dashscope:")
                        ? modelId.substring("dashscope:".length())
                        : modelId;
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        return DashScopeChatModel.builder().apiKey(apiKey).modelName(name).stream(true).build();
    }

    public static String resolveModelId() {
        String env = System.getenv("CODING_MODEL_ID");
        return (env != null && !env.isBlank()) ? env : "dashscope:qwen-max";
    }

    private static int resolveMaxIters() {
        String env = System.getenv("CODING_MAX_ITERS");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                // 忽略异常
            }
        }
        return 50;
    }
}
