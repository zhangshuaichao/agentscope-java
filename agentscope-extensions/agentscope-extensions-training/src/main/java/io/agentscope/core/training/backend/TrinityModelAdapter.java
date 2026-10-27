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

package io.agentscope.core.training.backend;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.training.runner.RunExecutionContext;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Trinity 模型适配器，以组合方式包装 OpenAIChatModel。
 * <p>将模型请求路由至 Trinity Chat API，并收集 msg_ids 关联到 RunExecutionContext。
 * 使用 OpenAI 兼容格式的委托模型，拦截响应中的 msg_id（response.id）供后续反馈。
 * <p>这是 TrainingRouter 创建和管理的内部组件，自动向执行上下文收集消息 ID。
 * @see RunExecutionContext
 */
public class TrinityModelAdapter extends ChatModelBase {

    private static final Logger logger = LoggerFactory.getLogger(TrinityModelAdapter.class);

    /**
     * 被包装的 OpenAIChatModel 实例。
     */
    private final OpenAIChatModel delegate;

    /**
     * 用于自动收集 msg_ids 的任务执行上下文。
     */
    private final RunExecutionContext executionContext;

    /**
     * 私有构造器。
     * @param delegate OpenAIChatModel 实例
     * @param executionContext 可选任务执行上下文
     */
    private TrinityModelAdapter(OpenAIChatModel delegate, RunExecutionContext executionContext) {
        this.delegate = delegate;
        this.executionContext = executionContext;
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        // 委派给 OpenAIChatModel。
        return delegate.stream(messages, tools, options)
                .doOnNext(
                        response -> {
                            // 自动向 RunExecutionContext 收集 msg_id。
                            String msgId = response.getId();
                            if (msgId != null && !msgId.isEmpty()) {
                                if (executionContext != null) {
                                    executionContext.addMsgId(msgId);
                                    logger.debug(
                                            "Auto-collected msg_id: {} for Task: {}, Run: {}",
                                            msgId,
                                            executionContext.getTaskId(),
                                            executionContext.getRunId());
                                } else {
                                    logger.warn(
                                            "No execution context available, msg_id not recorded:"
                                                    + " {}",
                                            msgId);
                                }
                            }
                        });
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    /**
     * 获取任务执行上下文。
     * @return 执行上下文，可为 null
     */
    public RunExecutionContext getExecutionContext() {
        return executionContext;
    }

    /**
     * 创建构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * TrinityModelAdapter 构建器。
     */
    public static class Builder {
        private String baseUrl;
        private String modelName;
        private String apiKey = "dummy"; // 当前适配配置使用占位 API key；实际服务访问保护仍取决于部署边界。
        private RunExecutionContext executionContext; // 由 TrainingRouter 传入的内部字段。

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置任务执行上下文，供 TrainingRouter 自动收集 msg_ids。
         * <p>虽然设计上作为内部装配入口，仍以实际方法可见性为准。
         * @param context 任务执行上下文
         * @return 当前构建器
         */
        public Builder executionContext(RunExecutionContext context) {
            this.executionContext = context;
            return this;
        }

        /**
         * 构建 TrinityModelAdapter。
         * @return TrinityModelAdapter 实例
         */
        public TrinityModelAdapter build() {
            // 记录实际使用的 baseUrl。
            logger.info(
                    "Creating TrinityModelAdapter with baseUrl={}, modelName={}",
                    baseUrl,
                    modelName);

            // 通过 OpenAIChatModel.builder() 创建委托对象。
            OpenAIChatModel openAIModel =
                    OpenAIChatModel.builder()
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .apiKey(apiKey)
                            .stream(false) // 本适配路径禁用流式请求，按 Trinity 当前对接要求处理。
                            .build();

            logger.debug("TrinityModelAdapter created successfully");

            return new TrinityModelAdapter(openAIModel, executionContext);
        }
    }
}
