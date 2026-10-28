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

package io.agentscope.core.nacos.prompt;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosPromptListener;
import com.alibaba.nacos.api.ai.listener.NacosPromptEvent;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NacosPromptListener {

    private static final Logger log = LoggerFactory.getLogger(NacosPromptListener.class);

    private static final Prompt EMPTY_SENTINEL = new Prompt("", "", "");

    private final AiService aiService;

    private final Map<String, Prompt> prompts;

    private final AbstractNacosPromptListener internalListener =
            new AbstractNacosPromptListener() {
                @Override
                public void onEvent(NacosPromptEvent event) {
                    if (event == null) {
                        return;
                    }
                    String key = event.getPromptKey();
                    Prompt prompt = event.getPrompt();
                    if (key == null || key.isEmpty()) {
                        log.warn("Received prompt event with null or empty promptKey");
                        return;
                    }
                    if (prompt != null && prompt.getTemplate() != null) {
                        prompts.put(key, prompt);
                        log.info(
                                "Prompt updated for key: {}, version: {}",
                                key,
                                prompt.getVersion());
                    } else {
                        log.warn(
                                "Received prompt event with null prompt or template for key: {}",
                                key);
                    }
                }
            };

    public NacosPromptListener(AiService aiService) {
        this.aiService = aiService;
        this.prompts = new ConcurrentHashMap<>(10);
    }

    public String getPrompt(String promptKey) throws NacosException {
        return getPrompt(promptKey, null);
    }

    public String getPrompt(String promptKey, Map<String, String> args) throws NacosException {
        return getPrompt(promptKey, args, null);
    }

    /**
     * 获取提示词模板，可指定默认值。
     * @param promptKey 提示词键
     * @param args 用于渲染的模板变量
     * @param defaultValue Nacos 中找不到提示词时使用的默认值
     * @return 渲染后的提示词字符串或默认值
     * @throws NacosException Nacos 服务发生错误时
     */
    public String getPrompt(String promptKey, Map<String, String> args, String defaultValue)
            throws NacosException {
        return getPrompt(promptKey, null, null, args, defaultValue);
    }

    /**
     * 按版本/标签获取提示词模板，可指定默认值。
     * <p>【学习提示】缓存只以 promptKey 为键，版本/标签仅在首次订阅加载时参与选择；
     * 同一实例再次用相同键请求不同版本不会自动形成独立缓存。监听事件更新该键的模板，
     * 模板渲染只是变量替换能力，不构成对远端提示词内容的安全审查。
     * @param promptKey 提示词键
     * @param version 目标提示词版本（例如 "1.0.0"），与 label 互斥
     * @param label 目标提示词标签（例如 "prod"），与 version 互斥
     * @param args 用于渲染的模板变量
     * @param defaultValue Nacos 中找不到提示词时使用的默认值
     * @return 渲染后的提示词字符串或默认值
     * @throws NacosException Nacos 服务发生错误时
     */
    public String getPrompt(
            String promptKey,
            String version,
            String label,
            Map<String, String> args,
            String defaultValue)
            throws NacosException {

        Prompt prompt = prompts.get(promptKey);
        if (prompt == null) {
            try {
                prompt = subscribeAndLoad(promptKey, version, label);
            } catch (NacosException e) {
                log.error("Failed to load prompt from Nacos for key: {}", promptKey, e);
                if (defaultValue != null) {
                    log.info("Using default value for prompt key: {}", promptKey);
                    return renderDefault(defaultValue, args);
                }
                throw e;
            }
            prompts.putIfAbsent(promptKey, prompt);
            prompt = prompts.get(promptKey);
        }

        if (prompt == EMPTY_SENTINEL
                || prompt.getTemplate() == null
                || prompt.getTemplate().isEmpty()) {
            if (defaultValue != null) {
                log.info("Using default value for prompt key: {}", promptKey);
                return renderDefault(defaultValue, args);
            }
            return "";
        }

        return prompt.render(args);
    }

    private Prompt subscribeAndLoad(String promptKey, String version, String label)
            throws NacosException {
        Prompt prompt = aiService.subscribePrompt(promptKey, version, label, internalListener);
        if (prompt != null) {
            log.info("Loaded prompt for key: {}, version: {}", promptKey, prompt.getVersion());
            return prompt;
        }
        log.warn("Prompt not found in Nacos for key: {}", promptKey);
        return EMPTY_SENTINEL;
    }

    private String renderDefault(String defaultValue, Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return defaultValue;
        }
        Prompt fallback = new Prompt(null, null, defaultValue);
        return fallback.render(args);
    }
}
