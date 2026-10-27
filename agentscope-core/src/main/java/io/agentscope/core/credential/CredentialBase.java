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
package io.agentscope.core.credential;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.model.ChatModelBase;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * 模型服务商的凭据元数据。
 *
 * <p>每个凭据包含特定 {@link ChatModelBase} 子类需要的连接信息
 * （API 密钥、基础 URL、主机等）。子类通过 {@link #getChatModelClass()} 声明
 * 使用它的模型类，也可通过 {@link #listModels()} 枚举可用模型目录。
 *
 * <p>{@code id} 是自动生成的 32 位十六进制字符串，用于标识此凭据实例；
 * JSON 保留该字段以支持往返转换，但它本身不用于身份验证。
 */
public abstract class CredentialBase {

    private final String id;

    protected CredentialBase(String id) {
        this.id = id == null ? UUID.randomUUID().toString().replace("-", "") : id;
    }

    @JsonProperty("id")
    public final String getId() {
        return id;
    }

    /**
     * 使用此凭据的 {@link ChatModelBase} 子类。没有对应 Java 模型实现的子类
     * 将抛出 {@link UnsupportedOperationException}。
     */
    public abstract Class<? extends ChatModelBase> getChatModelClass();

    /**
     * 枚举此凭据下可用的候选模型。默认实现抛出 {@link UnsupportedOperationException}；
     * 具体模型发现能力将由未来的 {@code ChatModelBase.listModels} 钩子提供。
     */
    public Mono<List<ModelCard>> listModels() {
        return Mono.error(
                new UnsupportedOperationException(
                        getClass().getSimpleName()
                                + " does not yet support listModels; provide a manual model"
                                + " catalog or wait for ChatModelBase.listModels support."));
    }
}
