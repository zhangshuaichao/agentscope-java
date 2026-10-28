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
package io.agentscope.core.e2e;

import io.agentscope.core.e2e.providers.AnthropicProvider;
import io.agentscope.core.e2e.providers.DashScopeCompatibleProvider;
import io.agentscope.core.e2e.providers.DashScopeProvider;
import io.agentscope.core.e2e.providers.DeepSeekProvider;
import io.agentscope.core.e2e.providers.DeepSeekReasonerProvider;
import io.agentscope.core.e2e.providers.GLMProvider;
import io.agentscope.core.e2e.providers.GeminiProvider;
import io.agentscope.core.e2e.providers.ModelCapability;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.e2e.providers.OpenAIOfficialResponsesProvider;
import io.agentscope.core.e2e.providers.OpenRouterProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 根据可用 API 密钥创建 ModelProvider 的工厂。
 * <p>按环境变量动态启用：OPENAI_API_KEY 对应 OpenAI 原生；DASHSCOPE_API_KEY 对应
 * DashScope 原生、兼容及百炼；DEEPSEEK_API_KEY 对应 DeepSeek；GLM_API_KEY 对应智谱；
 * GOOGLE_API_KEY 对应 Gemini；ANTHROPIC_API_KEY 对应 Claude；OPENROUTER_API_KEY 对应 OpenRouter。
 * <p>用法：
 * <pre>{@code
 * // 获取全部基本能力提供方
 * Stream<ModelProvider> basics = ProviderFactory.getBasicProviders();
 *
 * // 获取具备指定能力的提供方
 * Stream<ModelProvider> imageProviders = ProviderFactory.getProviders(
 *     ModelCapability.BASIC, ModelCapability.IMAGE);
 *
 * // 检查提供方状态
 * String status = ProviderFactory.getApiKeyStatus();
 * }</pre>
 * <p>【学习提示】能力标注是此测试矩阵的静态配置，不是实时服务发现；
 * 有密钥只是允许运行测试，不保证模型权限、额度或远端服务可用。
 */
public class ProviderFactory {

    private static final String OPENAI_API_KEY = "OPENAI_API_KEY";
    private static final String DASHSCOPE_API_KEY = "DASHSCOPE_API_KEY";
    private static final String DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY";
    private static final String GLM_API_KEY = "GLM_API_KEY";
    private static final String GOOGLE_API_KEY = "GOOGLE_API_KEY";
    private static final String ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";
    private static final String OPENROUTER_API_KEY = "OPENROUTER_API_KEY";

    private static final List<String> ALL_KEYS =
            List.of(
                    OPENAI_API_KEY,
                    DASHSCOPE_API_KEY,
                    DEEPSEEK_API_KEY,
                    GLM_API_KEY,
                    GOOGLE_API_KEY,
                    ANTHROPIC_API_KEY,
                    OPENROUTER_API_KEY);

    /**
     * 获取全部已注册提供方，包括禁用项。
     * @return 全部提供方实例列表
     */
    private static List<ModelProvider> getAllProviders() {
        List<ModelProvider> providers = new ArrayList<>();

        // OpenAI 官方 SDK 提供方（Responses API）
        providers.add(new OpenAIOfficialResponsesProvider.Gpt54());
        providers.add(new OpenAIOfficialResponsesProvider.Gpt54MultiAgent());

        // DashScope 兼容 API 提供方
        providers.add(new DashScopeCompatibleProvider.QwenPlusOpenAI());
        providers.add(new DashScopeCompatibleProvider.QwenPlusMultiAgentOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen3OmniFlashOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen3OmniFlashMultiAgentOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen3VlPlusOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen3VlPlusMultiAgentOpenAI());

        // DashScope 兼容 API 提供方（Qwen3.5）
        providers.add(new DashScopeCompatibleProvider.Qwen35PlusOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen35PlusMultiAgentOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen35FlashOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen35FlashMultiAgentOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen35_397bA17bOpenAI());
        providers.add(new DashScopeCompatibleProvider.Qwen35_397bA17bMultiAgentOpenAI());

        // DashScope 原生 API 提供方
        providers.add(new DashScopeProvider.QwenPlusDashScope());
        providers.add(new DashScopeProvider.QwenPlusMultiAgentDashScope());
        providers.add(new DashScopeProvider.QwenPlusThinkingDashScope());
        providers.add(new DashScopeProvider.QwenPlusThinkingMultiAgentDashScope());
        providers.add(new DashScopeProvider.Qwen3VlPlusDashScope());
        providers.add(new DashScopeProvider.Qwen3VlPlusMultiAgentDashScope());
        providers.add(new DashScopeProvider.Qwen35PlusDashScope());
        providers.add(new DashScopeProvider.Qwen35PlusMultiAgentDashScope());
        providers.add(new DashScopeProvider.Qwen35FlashDashScope());
        providers.add(new DashScopeProvider.Qwen35FlashMultiAgentDashScope());
        providers.add(new DashScopeProvider.Qwen35_397bA17bDashScope());
        providers.add(new DashScopeProvider.Qwen35_397bA17bMultiAgentDashScope());

        // Gemini 提供方
        providers.add(new GeminiProvider.Gemini25FlashGemini());
        providers.add(new GeminiProvider.Gemini25FlashMultiAgentGemini());

        // Anthropic 提供方
        providers.add(new AnthropicProvider.ClaudeHaiku45Anthropic());
        providers.add(new AnthropicProvider.ClaudeHaiku45MultiAgentAnthropic());

        // DeepSeek 提供方
        providers.add(new DeepSeekProvider.DeepSeekChat());
        providers.add(new DeepSeekProvider.DeepSeekChatMultiAgent());
        providers.add(new DeepSeekReasonerProvider.DeepSeekR1());
        providers.add(new DeepSeekReasonerProvider.DeepSeekR1MultiAgent());

        // GLM 提供方
        providers.add(new GLMProvider.GLM46());
        providers.add(new GLMProvider.GLM46MultiAgent());
        providers.add(new GLMProvider.GLM46VPlus());
        providers.add(new GLMProvider.GLM46VMultiAgent());
        providers.add(new GLMProvider.GLM45());
        providers.add(new GLMProvider.GLM47());

        // OpenRouter 提供方
        providers.add(new OpenRouterProvider.GPT52());
        providers.add(new OpenRouterProvider.GPT52MultiAgent());
        providers.add(new OpenRouterProvider.Claude45Haiku());
        providers.add(new OpenRouterProvider.Claude45HaikuMultiAgent());
        providers.add(new OpenRouterProvider.Qwen3VL());
        providers.add(new OpenRouterProvider.Qwen3VLMultiAgent());
        providers.add(new OpenRouterProvider.Gemini3FlashPreview());
        providers.add(new OpenRouterProvider.Gemini3FlashPreviewMultiAgent());
        providers.add(new OpenRouterProvider.Gemini3ProPreview());
        providers.add(new OpenRouterProvider.Gemini3ProPreviewMultiAgent());
        providers.add(new OpenRouterProvider.DeepSeekV32());
        providers.add(new OpenRouterProvider.DeepSeekV32MultiAgent());
        providers.add(new OpenRouterProvider.DeepSeekR1());
        providers.add(new OpenRouterProvider.DeepSeekR1MultiAgent());
        providers.add(new OpenRouterProvider.GLM46());
        providers.add(new OpenRouterProvider.GLM46MultiAgent());

        return providers;
    }

    // ==========================================================================
    // API 密钥辅助方法
    // ==========================================================================

    protected static boolean hasApiKey(String keyName) {
        String key = System.getenv(keyName);
        if (key == null || key.isEmpty()) {
            key = System.getProperty(keyName);
        }
        return key != null && !key.isEmpty();
    }

    protected static boolean hasOpenAIKey() {
        return hasApiKey(OPENAI_API_KEY);
    }

    protected static boolean hasDeepSeekKey() {
        return hasApiKey(DEEPSEEK_API_KEY);
    }

    protected static boolean hasGLMKey() {
        return hasApiKey(GLM_API_KEY);
    }

    protected static boolean hasDashScopeKey() {
        return hasApiKey(DASHSCOPE_API_KEY);
    }

    protected static boolean hasGoogleKey() {
        return hasApiKey(GOOGLE_API_KEY);
    }

    protected static boolean hasAnthropicKey() {
        return hasApiKey(ANTHROPIC_API_KEY);
    }

    protected static boolean hasOpenRouterKey() {
        return hasApiKey(OPENROUTER_API_KEY);
    }

    // ==========================================================================
    // 按能力选择提供方
    // ==========================================================================

    /**
     * 获取具备全部指定能力的提供方。
     * @param required 所需能力
     * @return 具备全部所需能力且已启用的提供方流
     */
    public static Stream<ModelProvider> getProviders(ModelCapability... required) {
        Set<ModelCapability> requiredSet = Set.of(required);
        return getAllProviders().stream()
                .filter(ModelProvider::isEnabled)
                .filter(p -> p.getCapabilities().containsAll(requiredSet));
    }

    // ==========================================================================
    // 便捷方法（从 getEnabledXxxProviders 重命名为 getXxxProviders）
    // ==========================================================================

    /**
     * 获取用于核心功能测试、具备基本能力的已启用提供方。
     * @return 具备基本能力的已启用提供方流
     */
    public static Stream<ModelProvider> getBasicProviders() {
        return getProviders(ModelCapability.BASIC);
    }

    /**
     * 获取用于工具功能测试的已启用提供方。
     * @return 支持工具的已启用提供方流
     */
    public static Stream<ModelProvider> getToolProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.TOOL_CALLING);
    }

    /**
     * 获取用于图片功能测试的已启用提供方。
     * @return 支持图片的已启用提供方流
     */
    public static Stream<ModelProvider> getImageProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.IMAGE);
    }

    /**
     * 获取用于音频功能测试的已启用提供方。
     * @return 支持音频的已启用提供方流
     */
    public static Stream<ModelProvider> getAudioProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.AUDIO);
    }

    /**
     * 获取用于多模态功能测试的已启用提供方。
     * @return 支持多种模态的已启用提供方流
     */
    public static Stream<ModelProvider> getMultimodalProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.IMAGE, ModelCapability.AUDIO);
    }

    /**
     * 获取用于思考功能测试的已启用提供方。
     * @return 支持思考的已启用提供方流
     */
    public static Stream<ModelProvider> getThinkingProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.THINKING);
    }

    /**
     * 获取支持思考预算控制的已启用提供方。
     * @return 支持思考预算的已启用提供方流
     */
    public static Stream<ModelProvider> getThinkingBudgetProviders() {
        Stream.Builder<ModelProvider> builders = Stream.builder();

        if (hasDashScopeKey()) {
            builders.add(new DashScopeProvider.QwenPlusThinkingDashScope(1000));
            builders.add(new DashScopeProvider.QwenPlusThinkingMultiAgentDashScope(1000));
        }

        if (hasGLMKey()) {
            builders.add(new GLMProvider.GLM45());
            builders.add(new GLMProvider.GLM47());
        }

        if (hasOpenRouterKey()) {
            builders.add(new OpenRouterProvider.DeepSeekR1());
            builders.add(new OpenRouterProvider.DeepSeekR1MultiAgent());
            builders.add(new OpenRouterProvider.Claude45HaikuThinking(1024));
        }

        return builders.build();
    }

    /**
     * 获取用于视频功能测试的已启用提供方。
     * @return 支持视频的已启用提供方流
     */
    public static Stream<ModelProvider> getVideoProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.VIDEO);
    }

    /**
     * 获取用于多模态工具功能测试的已启用提供方。
     * @return 支持多模态工具的已启用提供方流
     */
    public static Stream<ModelProvider> getMultimodalToolProviders() {
        return getProviders(
                ModelCapability.BASIC, ModelCapability.IMAGE, ModelCapability.TOOL_CALLING);
    }

    /**
     * 获取支持多智能体格式化器的已启用提供方，供 MsgHub 测试使用。
     * @return 具备多智能体格式化能力的已启用提供方流
     */
    public static Stream<ModelProvider> getMultiAgentProviders() {
        return getProviders(ModelCapability.BASIC, ModelCapability.MULTI_AGENT_FORMATTER);
    }

    // ==========================================================================
    // 工具方法
    // ==========================================================================

    /**
     * 检查是否至少有一个 API 密钥，可运行部分 E2E 测试。
     * @return 至少存在一个可用 API 密钥时为 true
     */
    public static boolean hasAnyApiKey() {
        return ALL_KEYS.stream().anyMatch(ProviderFactory::hasApiKey);
    }

    /**
     * 获取可用 API 密钥名称的逗号分隔列表，供调试使用。
     * @return 描述可用密钥配置的字符串
     */
    public static String getApiKeyStatus() {
        String status =
                ALL_KEYS.stream()
                        .filter(ProviderFactory::hasApiKey)
                        .collect(Collectors.joining(", "));
        return status.isEmpty() ? "None" : status;
    }
}
