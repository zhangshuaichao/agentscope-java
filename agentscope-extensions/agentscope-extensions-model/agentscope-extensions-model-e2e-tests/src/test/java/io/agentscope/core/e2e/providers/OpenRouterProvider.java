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
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIMultiAgentFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** OpenRouter API 测试提供方。<p>通过 OpenAI 兼容接口访问不同 LLM，使 OpenAI HTTP 实现可与多个模型提供方配合；具体能力和额外参数仍按模型配置，不代表完全等价。 */
@ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
public class OpenRouterProvider extends BaseModelProvider {

    private static final String API_KEY_ENV = "OPENROUTER_API_KEY";
    private static final String BASE_URL_ENV = "OPENROUTER_BASE_URL";
    private static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api";

    private final GenerateOptions defaultOptions;

    public OpenRouterProvider(String modelName, boolean multiAgentFormatter) {
        this(modelName, multiAgentFormatter, null);
    }

    public OpenRouterProvider(
            String modelName, boolean multiAgentFormatter, GenerateOptions defaultOptions) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
        this.defaultOptions = defaultOptions;
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        String baseUrl = System.getenv(BASE_URL_ENV);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = System.getProperty(BASE_URL_ENV);
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_OPENROUTER_BASE_URL;
        }

        // 检查模型是否需要专用配置
        GenerateOptions options = this.defaultOptions;
        if (options == null && getModelName().contains("gemini")) {
            // 此测试的 OpenRouter Gemini 模型需 include_reasoning=true 才能使用工具
            options =
                    GenerateOptions.builder()
                            .additionalBodyParam("include_reasoning", true)
                            .build();
        }

        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .modelName(getModelName())
                        .stream(true)
                        .generateOptions(options)
                        .formatter(
                                isMultiAgentFormatter()
                                        ? new OpenAIMultiAgentFormatter()
                                        : new OpenAIChatFormatter())
                        .build();

        return ReActAgent.builder().name(name).model(model).toolkit(toolkit);
    }

    @Override
    public String getProviderName() {
        return "OpenRouter";
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
    // GPT 模型
    // ==========================================================================

    /** GPT-5.2 */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING, ModelCapability.IMAGE})
    public static class GPT52 extends OpenRouterProvider {
        public GPT52() {
            super("openai/gpt-5.2", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - GPT-5.2";
        }
    }

    /** 使用多智能体格式化器的 GPT-5.2。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class GPT52MultiAgent extends OpenRouterProvider {
        public GPT52MultiAgent() {
            super("openai/gpt-5.2", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - GPT-5.2 (MultiAgent)";
        }
    }

    // ==========================================================================
    // Claude 模型
    // ==========================================================================

    /** Claude 4.5 Haiku */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING, ModelCapability.IMAGE})
    public static class Claude45Haiku extends OpenRouterProvider {
        public Claude45Haiku() {
            super("anthropic/claude-haiku-4.5", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Claude 4.5 Haiku";
        }
    }

    /** 使用多智能体格式化器的 Claude 4.5 Haiku。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Claude45HaikuMultiAgent extends OpenRouterProvider {
        public Claude45HaikuMultiAgent() {
            super("anthropic/claude-haiku-4.5", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Claude 4.5 Haiku (MultiAgent)";
        }
    }

    /** 启用思考模式的 Claude 4.5 Haiku。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.THINKING,
        ModelCapability.THINKING_BUDGET
    })
    public static class Claude45HaikuThinking extends OpenRouterProvider {
        public Claude45HaikuThinking(int budget) {
            super(
                    "anthropic/claude-haiku-4.5",
                    false,
                    GenerateOptions.builder()
                            .additionalBodyParam(
                                    "thinking", Map.of("type", "enabled", "budget_tokens", budget))
                            .maxTokens(Math.max(budget + 2000, 4000))
                            .build());
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Claude 4.5 Haiku (Thinking)";
        }
    }

    // ==========================================================================
    // Qwen 模型
    // ==========================================================================

    /** Qwen3 VL，阿里巴巴的多模态视觉模型。 */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING, ModelCapability.IMAGE})
    public static class Qwen3VL extends OpenRouterProvider {
        public Qwen3VL() {
            super("qwen/qwen3-vl-235b-a22b-instruct", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Qwen3 VL";
        }
    }

    /** 使用多智能体格式化器的 Qwen3 VL。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Qwen3VLMultiAgent extends OpenRouterProvider {
        public Qwen3VLMultiAgent() {
            super("qwen/qwen3-vl-235b-a22b-instruct", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Qwen3 VL (MultiAgent)";
        }
    }

    // ==========================================================================
    // Gemini 模型
    // ==========================================================================

    /** 经 OpenRouter 使用 Gemini 3 Flash Preview 快速模型。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.THINKING
    })
    public static class Gemini3FlashPreview extends OpenRouterProvider {
        public Gemini3FlashPreview() {
            super("google/gemini-3-flash-preview", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Gemini 3 Flash Preview";
        }
    }

    /** 使用多智能体格式化器的 Gemini 3 Flash Preview。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Gemini3FlashPreviewMultiAgent extends OpenRouterProvider {
        public Gemini3FlashPreviewMultiAgent() {
            super("google/gemini-3-flash-preview", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Gemini 3 Flash Preview (MultiAgent)";
        }
    }

    /** 经 OpenRouter 使用 Gemini 3 Pro Preview 模型。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.THINKING
    })
    public static class Gemini3ProPreview extends OpenRouterProvider {
        public Gemini3ProPreview() {
            super("google/gemini-3-pro-preview", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Gemini 3 Pro Preview";
        }
    }

    /** 使用多智能体格式化器的 Gemini 3 Pro Preview。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Gemini3ProPreviewMultiAgent extends OpenRouterProvider {
        public Gemini3ProPreviewMultiAgent() {
            super("google/gemini-3-pro-preview", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - Gemini 3 Pro Preview (MultiAgent)";
        }
    }

    // ==========================================================================
    // DeepSeek 模型
    // ==========================================================================

    /** 经 OpenRouter 使用 DeepSeek V3.2 高效模型。 */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
    public static class DeepSeekV32 extends OpenRouterProvider {
        public DeepSeekV32() {
            super("deepseek/deepseek-v3.2", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - DeepSeek V3.2";
        }
    }

    /** 使用多智能体格式化器的 DeepSeek V3.2。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class DeepSeekV32MultiAgent extends OpenRouterProvider {
        public DeepSeekV32MultiAgent() {
            super("deepseek/deepseek-v3.2", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - DeepSeek V3.2 (MultiAgent)";
        }
    }

    /** 经 OpenRouter 使用 DeepSeek R1 推理模型。 */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.THINKING})
    public static class DeepSeekR1 extends OpenRouterProvider {
        public DeepSeekR1() {
            super("deepseek/deepseek-r1", false);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - DeepSeek R1";
        }

        @Override
        public boolean supportsToolCalling() {
            return false;
        }
    }

    /** 使用多智能体格式化器的 DeepSeek R1。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class DeepSeekR1MultiAgent extends OpenRouterProvider {
        public DeepSeekR1MultiAgent() {
            super("deepseek/deepseek-r1", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - DeepSeek R1 (MultiAgent)";
        }

        @Override
        public boolean supportsToolCalling() {
            return false;
        }
    }

    // ==========================================================================
    // GLM 模型
    // ==========================================================================

    /** 经 OpenRouter 使用智谱 GLM 4.6V。 */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING, ModelCapability.IMAGE})
    public static class GLM46 extends OpenRouterProvider {
        public GLM46() {
            super("z-ai/glm-4.6v", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - GLM 4.6V";
        }
    }

    /** 使用多智能体格式化器的 GLM 4.6V。 */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class GLM46MultiAgent extends OpenRouterProvider {
        public GLM46MultiAgent() {
            super("z-ai/glm-4.6v", true);
        }

        @Override
        public String getProviderName() {
            return "OpenRouter - GLM 4.6V (MultiAgent)";
        }
    }
}
