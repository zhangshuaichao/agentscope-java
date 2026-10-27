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
package io.agentscope.core.model;

/**
 * 控制 {@link ModelRegistry} 是否缓存通过
 * {@link ModelCreationContext} 创建的模型。
 *
 * <p>策略概览：
 *
 * <ul>
 *   <li>{@link #DEFAULT}：保留旧行为。空上下文按模型 ID 缓存；
 *       非空上下文不缓存。
 *   <li>{@link #DISABLED}：永不缓存。每次解析都创建新的模型实例。
 *   <li>{@link #ENABLED}：显式启用缓存。缓存标识取调用方提供的
 *       {@code cacheId}，或仅根据标准简单字段推导的指纹。
 *       包含不透明选项／组件的上下文必须显式提供 {@code cacheId}。
 * </ul>
 */
public enum CachePolicy {
    /**
     * 保留注册表默认行为。旧版 {@link ModelRegistry#resolve(String)} 调用会缓存，
     * 基于非空上下文的解析则不缓存。
     */
    DEFAULT,

    /** 不缓存所创建的模型。 */
    DISABLED,

    /** 使用显式给定或安全推导的上下文缓存标识，缓存所创建的模型。 */
    ENABLED
}
