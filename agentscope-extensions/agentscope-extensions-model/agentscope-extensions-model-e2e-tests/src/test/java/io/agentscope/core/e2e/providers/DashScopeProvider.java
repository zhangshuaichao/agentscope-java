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
package io.agentscope.core.e2e.providers;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeMultiAgentFormatter;
import java.util.HashSet;
import java.util.Set;

/** DashScope 原生 API 测试提供方，配置 qwen-plus、qwen-vl-max、qwen3-vl-plus 等模型及可选思考模式。 */
@ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
public class DashScopeProvider extends BaseModelProvider {

    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    private final boolean enableThinking;
    private final int thinkingBudget;

    public DashScopeProvider(String modelName, boolean multiAgentFormatter) {
        this(modelName, false, 0, multiAgentFormatter);
    }

    public DashScopeProvider(
            String modelName,
            boolean enableThinking,
            int thinkingBudget,
            boolean multiAgentFormatter) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
        this.enableThinking = enableThinking;
        this.thinkingBudget = thinkingBudget;
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        DashScopeChatModel.Builder builder =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(getModelName()).stream(true)
                        .enableThinking(enableThinking)
                        .formatter(
                                isMultiAgentFormatter()
                                        ? new DashScopeMultiAgentFormatter()
                                        : new DashScopeChatFormatter());

        if (enableThinking) {
            builder.defaultOptions(
                    GenerateOptions.builder().thinkingBudget(thinkingBudget).build());
        }

        return ReActAgent.builder().name(name).model(builder.build()).toolkit(toolkit);
    }

    @Override
    public String getProviderName() {
        return enableThinking ? "DashScope-Native-Thinking" : "DashScope-Native";
    }

    @Override
    public Set<ModelCapability> getCapabilities() {
        Set<ModelCapability> caps = new HashSet<>(super.getCapabilities());
        if (isMultiAgentFormatter()) {
            caps.add(ModelCapability.MULTI_AGENT_FORMATTER);
        }
        return caps;
    }

    // ==========================================================================
    // 提供方实例
    // ==========================================================================

    /** Qwen3-VL-Plus，高级视觉模型。<p>此测试能力配置支持图片和视频，不支持音频。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO
    })
    public static class Qwen3VlPlusDashScope extends DashScopeProvider {
        public Qwen3VlPlusDashScope() {
            super("qwen3-vl-plus", false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    /** 使用多智能体格式化器的 Qwen3-VL-Plus。<p>此测试能力配置支持图片和视频，不支持音频。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Qwen3VlPlusMultiAgentDashScope extends DashScopeProvider {
        public Qwen3VlPlusMultiAgentDashScope() {
            super("qwen3-vl-plus", true);
        }

        @Override
        public String getProviderName() {
            return "DashScope (Multi-Agent)";
        }
    }

    /** 启用思考模式的 Qwen-Plus。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING
    })
    public static class QwenPlusThinkingDashScope extends DashScopeProvider {
        public QwenPlusThinkingDashScope() {
            super("qwen-plus", true, 5000, false);
        }

        public QwenPlusThinkingDashScope(int thinkingBudget) {
            super("qwen-plus", true, thinkingBudget, false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    /** 启用思考模式及多智能体格式化器的 Qwen-Plus。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class QwenPlusThinkingMultiAgentDashScope extends DashScopeProvider {
        public QwenPlusThinkingMultiAgentDashScope() {
            super("qwen-plus", true, 5000, true);
        }

        public QwenPlusThinkingMultiAgentDashScope(int thinkingBudget) {
            super("qwen-plus", true, thinkingBudget, true);
        }

        @Override
        public String getProviderName() {
            return "DashScope (Multi-Agent)";
        }
    }

    /** Qwen-Plus，标准文本模型。 */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
    public static class QwenPlusDashScope extends DashScopeProvider {
        public QwenPlusDashScope() {
            super("qwen-plus", false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    /** 使用多智能体格式化器的 Qwen-Plus。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class QwenPlusMultiAgentDashScope extends DashScopeProvider {
        public QwenPlusMultiAgentDashScope() {
            super("qwen-plus", true);
        }

        @Override
        public String getProviderName() {
            return "DashScope (Multi-Agent)";
        }
    }

    /** Qwen3.5-Plus，原生多模态模型（图片、视频）。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO
    })
    public static class Qwen35PlusDashScope extends DashScopeProvider {
        public Qwen35PlusDashScope() {
            super("qwen3.5-plus", false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    /** 使用多智能体格式化器的 Qwen3.5-Plus。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Qwen35PlusMultiAgentDashScope extends DashScopeProvider {
        public Qwen35PlusMultiAgentDashScope() {
            super("qwen3.5-plus", true);
        }

        @Override
        public String getProviderName() {
            return "DashScope (Multi-Agent)";
        }
    }

    /** Qwen3.5-Flash，原生多模态模型（图片、视频）。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO
    })
    public static class Qwen35FlashDashScope extends DashScopeProvider {
        public Qwen35FlashDashScope() {
            super("qwen3.5-flash", false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    /** 使用多智能体格式化器的 Qwen3.5-Flash。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Qwen35FlashMultiAgentDashScope extends DashScopeProvider {
        public Qwen35FlashMultiAgentDashScope() {
            super("qwen3.5-flash", true);
        }

        @Override
        public String getProviderName() {
            return "DashScope (Multi-Agent)";
        }
    }

    /** Qwen3.5-397B-A17B，原生多模态 MoE 模型（图片、视频）。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO
    })
    public static class Qwen35_397bA17bDashScope extends DashScopeProvider {
        public Qwen35_397bA17bDashScope() {
            super("qwen3.5-397b-a17b", false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    /** 使用多智能体格式化器的 Qwen3.5-397B-A17B。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Qwen35_397bA17bMultiAgentDashScope extends DashScopeProvider {
        public Qwen35_397bA17bMultiAgentDashScope() {
            super("qwen3.5-397b-a17b", true);
        }

        @Override
        public String getProviderName() {
            return "DashScope (Multi-Agent)";
        }
    }
}
