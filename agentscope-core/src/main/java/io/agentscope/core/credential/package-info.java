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

/**
 * 用于模型身份验证的服务商凭据。
 *
 * <p>{@link io.agentscope.core.credential.CredentialBase} 包含 {@code id} 字段，并暴露
 * {@code getChatModelClass()}（使用该凭据的 {@link io.agentscope.core.model.ChatModelBase}
 * 子类）和 {@code listModels()}（模型目录发现）。具体子类覆盖 DeepSeek、Kimi 与 xAI。
 * 若凭据对应的 Java 模型类尚未实现，{@code getChatModelClass()} 将抛出
 * {@link UnsupportedOperationException}，但凭据仍可通过 JSON 往返转换以保持存储兼容性。
 */
package io.agentscope.core.credential;
