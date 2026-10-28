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
package io.agentscope.claw2.marketplace;

import java.util.List;

/**
 * 由 claw 管理的技能市场，通过 UI 浏览与配置，独立于智能体运行时加载的 {@code skillRepositories}。
 *
 * <p>实现持有 Git 克隆、Nacos 客户端等状态资源；注册表替换或移除实例时必须关闭它们。
 */
public interface ClawMarketplace extends AutoCloseable {

    /** 用户创建市场时选择的稳定 ID。 */
    String id();

    /** UI 用于徽标及配置表单的类型标识（{@code "git"} / {@code "nacos"}）。 */
    String type();

    /** UI 显示的可读位置（URL、服务地址等）；接口约定不应包含凭据。 */
    String displayLocation();

    /** 市场是否支持写入；claw 当前只读，但 UI 可以展示此能力。 */
    default boolean writable() {
        return false;
    }

    /** 列出市场提供的所有技能。实现可以缓存，也可以每次访问上游；调用方应视其为潜在慢操作，并让 UI 按需加载。 */
    List<MarketSkillSummary> list();

    /** 获取指定技能的完整内容（SKILL.md 及附属资源）；不存在时返回 {@code null}。 */
    MarketSkillContent fetch(String name);

    /** 释放上游资源（关闭 Git、关闭 Nacos 客户端）；接口约定允许重复调用。 */
    @Override
    void close();
}
