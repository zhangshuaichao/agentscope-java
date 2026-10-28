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
import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.Set;

/**
 * ModelProvider 实现的抽象基类，提供 API 密钥处理及能力管理。
 * <p>子类应通过 {@link ModelCapabilities} 注解声明能力。
 * <p>用法：
 * <pre>{@code
 * @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
 * public class MyProvider extends BaseModelProvider {
 *
 *     public MyProvider(String modelName, boolean multiAgent) {
 *         super("MY_API_KEY", modelName, multiAgent);
 *     }
 *
 *     @Override
 *     protected ReActAgent doCreateAgent(String name, Toolkit toolkit, String apiKey) {
 *         // 创建并返回智能体
 *     }
 *
 *     @Override
 *     public String getProviderName() {
 *         return "My Provider";
 *     }
 * }
 * }</pre>
 */
public abstract class BaseModelProvider implements ModelProvider {

    private final String apiKeyEnvVar;
    private final String modelName;
    private final boolean multiAgentFormatter;

    /**
     * 使用指定配置创建提供方。
     * @param apiKeyEnvVar API 密钥环境变量名
     * @param modelName 使用的模型名
     * @param multiAgentFormatter 是否使用多智能体格式化器
     */
    protected BaseModelProvider(
            String apiKeyEnvVar, String modelName, boolean multiAgentFormatter) {
        this.apiKeyEnvVar = apiKeyEnvVar;
        this.modelName = modelName;
        this.multiAgentFormatter = multiAgentFormatter;
    }

    /**
     * 从环境变量或系统属性获取 API 密钥。
     * @return API 密钥，未配置时为 null
     */
    protected String getApiKey() {
        String apiKey = System.getenv(apiKeyEnvVar);
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getProperty(apiKeyEnvVar);
        }
        return apiKey;
    }

    /**
     * 获取 API 密钥，未配置时抛异常。
     * @return API 密钥
     * @throws IllegalStateException 未配置 API 密钥时
     */
    protected String requireApiKey() {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    apiKeyEnvVar + " environment variable is required for " + getProviderName());
        }
        return apiKey;
    }

    @Override
    public final ReActAgent createAgent(String name, Toolkit toolkit) {
        return createAgentBuilder(name, toolkit).build();
    }

    @Override
    public ReActAgent.Builder createAgentBuilder(String name, Toolkit toolkit) {
        String apiKey = requireApiKey();
        return doCreateAgentBuilder(name, toolkit, apiKey);
    }

    /**
     * 使用已校验的 API 密钥创建智能体构建器。
     * @param name 智能体名称
     * @param toolkit 使用的工具包
     * @param apiKey 已校验的 API 密钥
     * @return 可继续配置的智能体构建器
     */
    protected abstract ReActAgent.Builder doCreateAgentBuilder(
            String name, Toolkit toolkit, String apiKey);

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 检查是否使用多智能体格式化器。
     * @return 使用时为 true
     */
    public boolean isMultiAgentFormatter() {
        return multiAgentFormatter;
    }

    @Override
    public boolean isEnabled() {
        String apiKey = getApiKey();
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public boolean supportsThinking() {
        return getCapabilities().contains(ModelCapability.THINKING);
    }

    @Override
    public boolean supportsToolCalling() {
        return getCapabilities().contains(ModelCapability.TOOL_CALLING);
    }

    @Override
    public Set<ModelCapability> getCapabilities() {
        // 先检查实际类上的注解
        ModelCapabilities annotation = this.getClass().getAnnotation(ModelCapabilities.class);
        if (annotation != null) {
            return Set.of(annotation.value());
        }

        // 检查父类注解
        Class<?> clazz = this.getClass().getSuperclass();
        while (clazz != null && clazz != Object.class) {
            annotation = clazz.getAnnotation(ModelCapabilities.class);
            if (annotation != null) {
                Set<ModelCapability> caps = new HashSet<>(Set.of(annotation.value()));
                // 使用多智能体格式化器时添加 MULTI_AGENT_FORMATTER
                if (multiAgentFormatter) {
                    caps.add(ModelCapability.MULTI_AGENT_FORMATTER);
                }
                return caps;
            }
            clazz = clazz.getSuperclass();
        }

        // 回退到基本能力
        Set<ModelCapability> caps = new HashSet<>();
        caps.add(ModelCapability.BASIC);
        if (multiAgentFormatter) {
            caps.add(ModelCapability.MULTI_AGENT_FORMATTER);
        }
        return caps;
    }

    /**
     * 获取 API 密钥环境变量名。
     * @return 环境变量名
     */
    public String getApiKeyEnvVar() {
        return apiKeyEnvVar;
    }

    @Override
    public String toString() {
        return getProviderName() + " [" + modelName + "]";
    }
}
